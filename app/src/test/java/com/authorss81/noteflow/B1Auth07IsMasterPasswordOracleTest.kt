package com.authorss81.noteflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1-AUTH-07 (phase-92) behavioral + wiring tests. Finding:
 * `NoteflowViewModel.isMasterPasswordValid` (the create-backup dialog's pre-export
 * master-password check) was a side-effect-free verifier — it ignored
 * `lockoutActive()` and never bumped the persisted `failedUnlockAttempts` /
 * `lockoutUntilEpochMs` counters that `verifyMasterPassword` enforces. On an
 * unlocked device an attacker could hammer the "Create password-protected backup"
 * dialog: every attempt ran full PBKDF2 and returned a clean pass/fail with zero
 * throttling, an in-app offline-equivalent oracle that never tripped the LockScreen's
 * 5-attempt exponential lockout.
 *
 * Fix: `isMasterPasswordValid` now shares the SAME persisted counters/lockout as
 * `verifyMasterPassword` (one shared failure helper + one shared success reset),
 * refuses while a lockout is active BEFORE any PBKDF2 work, and a tripped 5th
 * failure performs a real data-layer lock so no live keyed SQLCipher connection
 * sits behind the LockScreen (B1-AUTH-02 posture).
 *
 * What is provable on the pure JVM: a faithful behavioral model of the two
 * verifiers + the shared helpers (identical decision table), plus source-level
 * wiring pins on NoteflowViewModel.kt / HomeScreen.kt (same technique as
 * B1Auth02LockedOpenTest).
 */
class B1Auth07IsMasterPasswordOracleTest {

    // ---------- behavioral model: mirrors NoteflowViewModel exactly ----------

    /**
     * Mirror of NoteflowViewModel's master-password verification surface after
     * the B1-AUTH-07 fix. [recordFailedMasterPasswordVerification] /
     * [resetMasterPasswordVerificationCounters] / [verifyMasterPassword] /
     * [isMasterPasswordValid] replicate the production decision table; the
     * clock is injectable so time-based lockout expiry is deterministic.
     */
    private class LockoutModel(
        var failedUnlockAttempts: Int = 0,
        var lockoutUntilEpochMs: Long = 0L,
        val credentialPresent: Boolean = true,
        val clock: () -> Long = { 0L },
    ) {
        var pbkdf2WorkCalls = 0
        var zeroizeDekCalls = 0
        var dataLayerLocks = 0
        var tickerStarts = 0

        fun lockoutActive(): Boolean = lockoutUntilEpochMs > clock()

        fun computeLockoutDelayMs(failures: Int): Long {
            val exponent = (failures - MAX_FAILED_ATTEMPTS).coerceAtLeast(0)
            val delay = 30_000L * (1L shl exponent.coerceAtMost(5))
            return delay.coerceAtMost(15 * 60 * 1000L)
        }

        fun recordFailedMasterPasswordVerification() {
            val newCount = failedUnlockAttempts + 1
            failedUnlockAttempts = newCount
            if (newCount >= MAX_FAILED_ATTEMPTS) {
                val delayMs = computeLockoutDelayMs(newCount)
                lockoutUntilEpochMs = clock() + delayMs
                lock()
                tickerStarts++
            }
        }

        fun resetMasterPasswordVerificationCounters() {
            failedUnlockAttempts = 0
            lockoutUntilEpochMs = 0L
        }

        /** Mirrors lock(): DEK zeroized, data layer torn down. */
        private fun lock() {
            zeroizeDekCalls++
            dataLayerLocks++
        }

        /** PBKDF2-equivalent unwrap — the genuine cost of a guessed password. */
        private fun unwrap(passwordValid: Boolean): ByteArray? {
            pbkdf2WorkCalls++
            return if (passwordValid) ByteArray(32) { 7 } else null
        }

        fun verifyMasterPassword(passwordValid: Boolean): Boolean {
            if (lockoutActive()) return false
            if (!credentialPresent) return false
            val dek = unwrap(passwordValid)
            if (dek == null) {
                recordFailedMasterPasswordVerification()
                return false
            }
            dek.fill(0)
            resetMasterPasswordVerificationCounters()
            return true
        }

        fun isMasterPasswordValid(passwordValid: Boolean): Boolean {
            if (lockoutActive()) return false
            if (!credentialPresent) return false
            val dek = unwrap(passwordValid)
            if (dek == null) {
                recordFailedMasterPasswordVerification()
                return false
            }
            dek.fill(0)
            resetMasterPasswordVerificationCounters()
            return true
        }

        companion object {
            const val MAX_FAILED_ATTEMPTS = 5
        }
    }

    @Test
    fun `repeated invalid backup-password submissions trip the SAME 5-attempt lockout as unlock attempts`() {
        val m = LockoutModel()
        repeat(4) { i ->
            assertFalse(
                "the backup-dialog verifier must reject a wrong master password",
                m.isMasterPasswordValid(passwordValid = false)
            )
            assertEquals(
                "each rejection bumps the shared failed-attempt counter (attempt ${i + 1})",
                i + 1,
                m.failedUnlockAttempts
            )
            assertFalse("no lockout before the 5-attempt threshold", m.lockoutActive())
        }
        assertEquals("zero data-layer locks before the threshold", 0, m.dataLayerLocks)

        assertFalse("the 5th wrong submission is still rejected", m.isMasterPasswordValid(passwordValid = false))
        assertEquals("the 5th failure crosses the persisted lockout", 5, m.failedUnlockAttempts)
        assertTrue("the recomputed backoff is active (30s)", m.lockoutActive())
        assertEquals("lockoutUntilEpochMs = now + 30s", 30_000L, m.lockoutUntilEpochMs - m.clock())
        assertEquals("the data-layer lock runs at the threshold", 1, m.dataLayerLocks)
        assertEquals("the lockout ticker starts behind the LockScreen countdown", 1, m.tickerStarts)
    }

    @Test
    fun `an active lockout refuses BOTH surfaces before any PBKDF2 work - oracle closed`() {
        val now = { 5_000L }
        val m = LockoutModel(lockoutUntilEpochMs = 15_000L, clock = now)
        assertTrue("precondition: a lockout is active", m.lockoutActive())

        assertFalse("the backup-dialog check refuses while locked", m.isMasterPasswordValid(passwordValid = true))
        assertFalse("the unlock verifier refuses while locked too", m.verifyMasterPassword(passwordValid = true))

        assertEquals("ZERO PBKDF2 work may run while locked — the oracle is closed", 0, m.pbkdf2WorkCalls)
        assertEquals("no counter churn while locked", 0, m.failedUnlockAttempts)
        assertEquals("no data-layer churn while locked", 0, m.dataLayerLocks)
    }

    @Test
    fun `both verification surfaces share one counter set - the 5th failure via either locks`() {
        val m = LockoutModel()
        repeat(3) { assertFalse(m.isMasterPasswordValid(passwordValid = false)) }
        assertEquals(3, m.failedUnlockAttempts)

        assertFalse("the unlock verifier consumes the SAME counter set", m.verifyMasterPassword(passwordValid = false))
        assertEquals(4, m.failedUnlockAttempts)

        assertFalse(m.isMasterPasswordValid(passwordValid = false))
        assertEquals(
            "5 failures accumulated across BOTH surfaces trip the lockout the LockScreen relies on",
            5,
            m.failedUnlockAttempts
        )
        assertTrue(m.lockoutActive())
        assertEquals(1, m.dataLayerLocks)

        assertFalse(
            "after the shared lockout tripped, even a CORRECT password is refused by the backup dialog",
            m.isMasterPasswordValid(passwordValid = true)
        )
        assertEquals(
            "the lockout guard wins before any PBKDF2 work (5 real guesses, then refused)",
            5,
            m.pbkdf2WorkCalls
        )
    }

    @Test
    fun `a verified master password on the backup dialog clears the shared counters`() {
        val m = LockoutModel()
        repeat(3) { m.isMasterPasswordValid(passwordValid = false) }
        assertEquals(3, m.failedUnlockAttempts)

        assertTrue("the correct master password verifies", m.isMasterPasswordValid(passwordValid = true))
        assertEquals("a verified password clears the counters (same as unlock)", 0, m.failedUnlockAttempts)
        assertFalse("the lockout is cleared by a verified password", m.lockoutActive())
    }

    @Test
    fun `the lockout survives an app restart exactly like unlock attempts`() {
        val m1 = LockoutModel()
        repeat(5) { m1.isMasterPasswordValid(passwordValid = false) }
        assertTrue(m1.lockoutActive())

        // App restart: SettingsManager re-reads the persisted counters from prefs.
        val restarted = LockoutModel(
            failedUnlockAttempts = m1.failedUnlockAttempts,
            lockoutUntilEpochMs = m1.lockoutUntilEpochMs,
            clock = m1.clock
        )
        assertTrue("restarted app still sees the persisted lockout", restarted.lockoutActive())
        assertFalse("the backup-dialog check still refuses after restart", restarted.isMasterPasswordValid(passwordValid = true))
        assertEquals("no PBKDF2 work runs against a persisted lockout", 0, restarted.pbkdf2WorkCalls)

        // Time passes past the backoff window: the lockout lapses (startLockoutTicker
        // clears lockoutUntilEpochMs once remaining <= 0).
        val expired = LockoutModel(failedUnlockAttempts = 5, lockoutUntilEpochMs = 0L, clock = m1.clock)
        assertFalse("a lapsed lockout no longer blocks", expired.lockoutActive())
    }

    @Test
    fun `the lockout delay follows the same exponential backoff table`() {
        val m = LockoutModel()
        assertEquals("5th failure -> 30s", 30_000L, m.computeLockoutDelayMs(5))
        assertEquals("6th -> 1m", 60_000L, m.computeLockoutDelayMs(6))
        assertEquals("8th -> 4m", 240_000L, m.computeLockoutDelayMs(8))
        assertEquals("10th -> already the 15m cap (2^5 reaches the ceiling)", 15 * 60 * 1000L, m.computeLockoutDelayMs(10))
        assertEquals("the backoff is capped at 15 minutes", 15 * 60 * 1000L, m.computeLockoutDelayMs(50))
    }

    @Test
    fun `a vault without a master-password credential refuses both surfaces without counting`() {
        val m = LockoutModel(credentialPresent = false)
        assertFalse(m.isMasterPasswordValid(passwordValid = true))
        assertFalse(m.verifyMasterPassword(passwordValid = true))
        assertEquals(0, m.failedUnlockAttempts)
        assertEquals("no PBKDF2 work when there is no credential to check", 0, m.pbkdf2WorkCalls)
    }

    // ---------- wiring pins: the Android-bound wiring (source-level) ----------

    @Test
    fun `isMasterPasswordValid routes through the shared lockout counters`() {
        val vm = readNoteflowViewModelSource()
        val validBlock = vm.substringAfter("suspend fun isMasterPasswordValid", "END")
            .substringBefore("suspend fun setBiometricEnabled", "END")
        assertTrue(
            "the backup-dialog verifier must enforce the lockout gate FIRST (before any PBKDF2 work)",
            validBlock.contains("if (lockoutActive()) return false")
        )
        assertTrue(
            "it must refuse a vault with no master-password credential",
            validBlock.contains("masterPasswordCredentialOrLegacy == null")
        )
        assertTrue(
            "a failed attempt must bump the SAME shared counter helper as the unlock path",
            validBlock.contains("recordFailedMasterPasswordVerification()")
        )
        assertTrue(
            "a verified password must clear the shared counters",
            validBlock.contains("resetMasterPasswordVerificationCounters()")
        )
        assertTrue(
            "the verified DEK must still be zeroized before the counters reset",
            validBlock.contains("dek.fill(0.toByte())")
        )
        assertFalse(
            "it must stay free of the password-strength gate (only set/rotate gate strength)",
            validBlock.contains("PasswordStrengthPolicy")
        )
    }

    @Test
    fun `the unlock verifier delegates its bookkeeping to the SAME shared helpers`() {
        val vm = readNoteflowViewModelSource()
        val verifyBody = vm.substringAfter("suspend fun verifyMasterPassword(password: String): Boolean {")
            .substringBefore("private suspend fun unwrapMasterDek", "END")
        assertTrue(
            "verifyMasterPassword's failure path must use the shared failure helper",
            verifyBody.contains("recordFailedMasterPasswordVerification()")
        )
        assertTrue(
            "verifyMasterPassword's success path must use the shared reset helper",
            verifyBody.contains("resetMasterPasswordVerificationCounters()")
        )
        assertFalse(
            "the old inline counter bookkeeping must be gone from the verify catch (now in the shared helper)",
            verifyBody.contains("settings.failedUnlockAttempts = newCount")
        )
    }

    @Test
    fun `the shared failure helper performs the persisted lockout plus a data-layer lock`() {
        val vm = readNoteflowViewModelSource()
        val helper = vm.substringAfter("private fun recordFailedMasterPasswordVerification() {")
            .substringBefore("private fun resetMasterPasswordVerificationCounters()", "END")
        assertTrue("counters are persisted via settings", helper.contains("settings.failedUnlockAttempts"))
        assertTrue("the 5-attempt threshold is enforced", helper.contains("MAX_FAILED_ATTEMPTS"))
        assertTrue("the exponential backoff is applied", helper.contains("computeLockoutDelayMs(newCount)"))
        assertTrue("the lockout is persisted", helper.contains("settings.lockoutUntilEpochMs"))
        assertTrue("the threshold ALSO performs a real lock (no live keyed connection)", helper.contains("lock()"))
        assertTrue("the LockScreen countdown ticker starts", helper.contains("startLockoutTicker()"))
    }

    @Test
    fun `the model backoff table is pinned to the production constants and formula`() {
        val vm = readNoteflowViewModelSource()
        assertTrue(
            "production MAX_FAILED_ATTEMPTS must match the model threshold (5)",
            vm.contains("const val MAX_FAILED_ATTEMPTS = 5")
        )
        val backoff = vm.substringAfter("private fun computeLockoutDelayMs(failures: Int): Long {")
            .substringBefore("private fun startLockoutTicker", "END")
        assertTrue(
            "the 30s seed must match the model's computeLockoutDelayMs",
            backoff.contains("30_000L")
        )
        assertTrue(
            "the exponent base (failures - MAX_FAILED_ATTEMPTS) must match the model",
            backoff.contains("(failures - MAX_FAILED_ATTEMPTS)")
        )
        assertTrue(
            "the 2^5 shift cap must match the model's '2^5 reaches the ceiling'",
            backoff.contains("exponent.coerceAtMost(5)")
        )
        assertTrue(
            "the 15-minute cap must match the model's coerceAtMost ceiling",
            backoff.contains("15 * 60 * 1000L")
        )
    }

    @Test
    fun `the create-backup dialog verifies BEFORE exporting and surfaces lockout honestly`() {
        val home = java.io.File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/ui/screens/HomeScreen.kt"
        ).readText()
        val dialog = home.substringAfter("if (showBackupPasswordDialog)")
            .substringBefore("if (showRestartConfirmDialog)", "END")
        val checkIdx = dialog.indexOf("isMasterPasswordValid(backupPasswordInput)")
        val exportIdx = dialog.indexOf("ImportExportService.exportBackup(")
        assertTrue("the backup dialog must verify the master password", checkIdx >= 0)
        assertTrue(
            "re-authentication must happen immediately BEFORE the password-protected export",
            checkIdx >= 0 && checkIdx < exportIdx
        )
        assertTrue("the dialog must check the shared lockout state", dialog.contains("lockoutActive()"))
        assertTrue(
            "a tripped/active lockout is surfaced honestly, never as a generic incorrect-password",
            dialog.contains("Too many failed attempts")
        )
        assertTrue("a plain wrong password keeps its truthful message", dialog.contains("Incorrect master password"))
    }

    // ---------- helpers ----------

    private fun readNoteflowViewModelSource(): String {
        val file = java.io.File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt"
        )
        assertTrue("NoteflowViewModel.kt must exist", file.isFile)
        return file.readText()
    }

    private fun repoRoot(): java.io.File {
        val cwd = java.io.File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (java.io.File(dir, "gradle/libs.versions.toml").isFile &&
                java.io.File(dir, "app").isDirectory
            ) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}