package com.authorss81.noteflow

import com.authorss81.noteflow.services.DatabaseHmacPolicy
import com.authorss81.noteflow.services.IntegrityWarningDismissalGate
import java.io.File
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * B1-DB-6 (phase-87) behavioral + wiring tests: the tamper HMAC now covers the
 * WAL frames AND the banner dismissal is scoped per-session.
 *
 * Finding: `DatabaseSecurityHelper.computeDatabaseHmac` streamed only
 * `noteflow.sqlite`, but the vault runs WRITE_AHEAD_LOGGING
 * (`NoteflowDatabase.kt` `JournalMode.WRITE_AHEAD_LOGGING`), so committed-but-
 * uncheckpointed data lives in `-wal` which the HMAC never covered — a WAL-only
 * mutation before the next checkpoint edits/forges data undetected. Separately,
 * the banner's "Don't show again" checkbox permanently flipped
 * `databaseIntegrityCheckEnabled = false` and even a plain OK persisted
 * `databaseIntegrityWarningDismissed = true`, so one tap could permanently
 * knock out the vault's only tamper tripwire.
 *
 * What is provable on the pure JVM:
 *  - [DatabaseHmacPolicy.streamDbAndWal] authenticates (main + `-wal`): a WAL
 *    frame mutation, a newly-appended WAL file, or any mutation of either
 *    covered file is detected at the next verification; identical file states
 *    verify cleanly; an empty WAL contributes the same bytes as an absent one
 *    (a cleanly-checkpointed vault never false-positives by itself).
 *  - [IntegrityWarningDismissalGate] scopes the dismissal to the session:
 *    "Don't show again" suppresses the banner for the rest of the session only,
 *    a plain OK suppresses nothing, a fresh session re-arms, and a persisted
 *    check-enable flag is never flipped from the dismissal path.
 *  - source pins hold that `DatabaseSecurityHelper` streams through the policy
 *    and the ViewModel neither writes nor reads the persistent disable/dismiss
 *    flags from the tamper-banner lifecycle.
 */
class B1Db06WalCoverageAndDismissalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val hmacKey = SecretKeySpec("B1Db06TestKey".toByteArray(Charsets.UTF_8), "HmacSHA256")

    /** Mirrors `DatabaseSecurityHelper.computeDatabaseHmac` (main+`-wal` via the policy). */
    private fun baselineHex(dbFile: File): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(hmacKey)
        val consumed = DatabaseHmacPolicy.streamDbAndWal(mac, dbFile)
        assertTrue("the fixture must have a readable main file", consumed > 0L)
        return mac.doFinal().joinToString("") { "%02x".format(it) }
    }

    private fun writeMain(dir: File, content: ByteArray): File {
        val f = File(dir, "noteflow.sqlite")
        f.writeBytes(content)
        return f
    }

    private fun writeWal(dbFile: File, content: ByteArray): File {
        val wal = DatabaseHmacPolicy.walFile(dbFile)
        wal.writeBytes(content)
        return wal
    }

    private fun mainContent(): ByteArray {
        // 12 KiB of deterministic main-file bytes (a realistic non-empty SQLCipher page).
        return ByteArray(12_288) { (it % 251).toByte() }
    }

    private fun walFrames(): ByteArray {
        // Deterministic WAL-frame-shaped bytes: a salted header + 4 frame pages.
        val header = ByteArray(32) { 0x5a.toByte() }
        val frames = ByteArray(16_384) { (it % 253).toByte() }
        return header + frames
    }

    // ---------- WAL coverage: a WAL-only mutation is detected ----------

    @Test
    fun `a WAL frame mutation is detected at the next verification`() {
        val dir = tmp.newFolder()
        val dbFile = writeMain(dir, mainContent())
        val wal = writeWal(dbFile, walFrames())

        val stamp = baselineHex(dbFile)

        // Attacker mutates a single WAL frame byte (no main-file change).
        val tampered = wal.readBytes()
        tampered[tampered.lastIndex] = (tampered.last() + 1).toByte()
        wal.writeBytes(tampered)

        assertNotEquals(
            "a WAL-only mutation must change the authenticated state and be detected",
            stamp,
            baselineHex(dbFile)
        )
    }

    @Test
    fun `appending a WAL file after the stamp is detected`() {
        val dir = tmp.newFolder()
        val dbFile = writeMain(dir, mainContent())

        val stamp = baselineHex(dbFile)

        // A WAL appears later (committed-but-uncheckpointed data, or injected frames).
        writeWal(dbFile, walFrames())

        assertNotEquals(
            "new WAL frames after the stamp must be detected",
            stamp,
            baselineHex(dbFile)
        )
    }

    @Test
    fun `removing the WAL after a stamp that covered it is detected`() {
        val dir = tmp.newFolder()
        val dbFile = writeMain(dir, mainContent())
        val wal = writeWal(dbFile, walFrames())

        val stamp = baselineHex(dbFile)

        // Checkpoint runs: WAL merged then dropped — the on-disk bytes changed.
        wal.delete()

        assertNotEquals(
            "the removal of covered WAL frames changes the file state and must be detected",
            stamp,
            baselineHex(dbFile)
        )
    }

    @Test
    fun `main-file mutation with a WAL present is still detected`() {
        val dir = tmp.newFolder()
        val dbFile = writeMain(dir, mainContent())
        writeWal(dbFile, walFrames())

        val stamp = baselineHex(dbFile)

        dbFile.writeBytes(dbFile.readBytes().also { it[0] = (it[0] + 7).toByte() })

        assertNotEquals("a main-file mutation must still be detected", stamp, baselineHex(dbFile))
    }

    @Test
    fun `identical file states verify cleanly`() {
        val dir = tmp.newFolder()
        val dbFile = writeMain(dir, mainContent())
        writeWal(dbFile, walFrames())

        val first = baselineHex(dbFile)
        val second = baselineHex(dbFile)

        assertEquals("an unchanged main+wal state must verify against its own baseline", first, second)
    }

    @Test
    fun `an empty WAL contributes the same bytes as an absent WAL`() {
        val dir = tmp.newFolder()
        val dbFile = writeMain(dir, mainContent())

        val absent = baselineHex(dbFile)

        writeWal(dbFile, ByteArray(0))

        assertEquals(
            "a cleanly-emptied WAL (fully checkpointed) must not move the baseline",
            absent,
            baselineHex(dbFile)
        )
    }

    // ---------- per-session dismissal gate ----------

    @Test
    fun `a fresh session may show the banner`() {
        val gate = IntegrityWarningDismissalGate()
        assertTrue("a new session's tripwire is armed", gate.mayShow())
    }

    @Test
    fun `a plain OK does not suppress the banner for the session`() {
        val gate = IntegrityWarningDismissalGate()
        gate.onDismiss(dontShowAgain = false)
        assertTrue(
            "dismissing without 'don't show again' must leave the tripwire armed — a later " +
                "in-session re-verification may surface the banner again",
            gate.mayShow()
        )
    }

    @Test
    fun `dont-show-again suppresses the banner for the rest of the session`() {
        val gate = IntegrityWarningDismissalGate()
        gate.onDismiss(dontShowAgain = true)
        assertFalse("don't show again hides the banner for this session", gate.mayShow())
    }

    @Test
    fun `the dismissal is per-session - a fresh launch re-arms the tripwire`() {
        val sessionOne = IntegrityWarningDismissalGate()
        sessionOne.onDismiss(dontShowAgain = true)
        assertFalse("session one dismissed", sessionOne.mayShow())

        // A later launch creates a NEW session — the tripwire is armed again.
        val sessionTwo = IntegrityWarningDismissalGate()
        assertTrue("a fresh session must re-arm the tripwire (dismissal is NOT permanent)", sessionTwo.mayShow())
    }

    @Test
    fun `re-enabling the integrity check clears any in-session dismissal`() {
        val gate = IntegrityWarningDismissalGate()
        gate.onDismiss(dontShowAgain = true)
        assertFalse(gate.mayShow())

        gate.onReenable()
        assertTrue("re-enabling the check must clear the session dismissal", gate.mayShow())
    }

    @Test
    fun `a dont-show-again tap never permanently disables the check across sessions`() {
        // Models the ViewModel lifecycle: one shared persisted check flag + a fresh
        // per-session gate on every launch. Session 1 dismisses with the checkbox;
        // the persisted enabled flag must survive, and session 2 must re-run the check.
        var checkEnabled = true

        val sessionOne = IntegrityWarningDismissalGate()
        sessionOne.onDismiss(dontShowAgain = true)
        assertFalse(sessionOne.mayShow())
        // B1-DB-6 pre-fix, `dismissDatabaseIntegrityWarning(true)` wrote
        // `databaseIntegrityCheckEnabled = false` here — the same single tap that
        // produced sessionOne's banner would permanently kill session two's tripwire.
        assertTrue("dismissal must NOT flip the persisted check-enable flag", checkEnabled)

        // Next launch: fresh session, check still enabled -> the tripwire runs again.
        val sessionTwo = IntegrityWarningDismissalGate()
        assertTrue("session two still runs the (armed) integrity check", sessionTwo.mayShow() && checkEnabled)
    }

    // ---------- source-level wiring pins ----------

    @Test
    fun `DatabaseSecurityHelper streams the baseline through the WAL-aware policy`() {
        val helper = readSource("services/DatabaseSecurityHelper.kt")
        val hmacBlock = helper.substringAfter("private fun computeDatabaseHmac", "END")
        assertTrue("the HMAC must stream through the WAL-aware policy", hmacBlock.contains("DatabaseHmacPolicy.streamDbAndWal(mac, dbFile)"))
        assertFalse("the pre-fix main-file-only inline loop must be gone", helper.contains("dbFile.inputStream().use"))

        val policy = readSource("services/DatabaseHmacPolicy.kt")
        assertTrue("the policy must stream the WAL companion", policy.contains("val wal = walFile(dbFile)"))
        assertTrue("the policy must name SQLite's -wal suffix", policy.contains("File(dbFile.path + \"-wal\")"))
    }

    @Test
    fun `the dismiss function never flips or reads the persistent flags`() {
        val vm = readSource("ui/viewmodel/NoteflowViewModel.kt")
        val dismissBody = vm.substringAfter("fun dismissDatabaseIntegrityWarning(")
            .substringBefore("fun setDatabaseIntegrityCheckEnabled", "END")
        assertTrue("the dismiss must route through the per-session gate", dismissBody.contains("integrityWarningDismissal.onDismiss(dontShowAgain)"))
        assertFalse(
            "the dismiss must NEVER permanently flip databaseIntegrityCheckEnabled",
            dismissBody.contains("databaseIntegrityCheckEnabled")
        )
        assertFalse(
            "the dismiss must no longer write the persisted warning-dismissed latch",
            dismissBody.contains("databaseIntegrityWarningDismissed")
        )
    }

    @Test
    fun `the persisted dismissal latch is no longer read anywhere in the ViewModel`() {
        val vm = readSource("ui/viewmodel/NoteflowViewModel.kt")
        assertFalse(
            "no `settings.databaseIntegrityWarningDismissed` read/write may survive (comment-only mention is fine)",
            vm.contains("settings.databaseIntegrityWarningDismissed")
        )
        val initRegion = vm.substringAfter("init {")
            .substringBefore("private val _failedUnlockAttempts", "END")
        assertTrue("the init banner gate must consult the session gate", initRegion.contains("integrityWarningDismissal.mayShow()"))
        assertTrue("re-enabling the check must clear the session dismissal", vm.contains("integrityWarningDismissal.onReenable()"))
    }

    @Test
    fun `the banner checkbox is honestly labelled session-scoped`() {
        val activity = readSource("MainActivity.kt")
        assertTrue(
            "the checkbox must tell the user the dismissal lasts only this session",
            activity.contains("Don't show again this session")
        )
    }

    // ---------- helpers ----------

    private fun readSource(relative: String): String {
        val file = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/$relative")
        assertTrue("$relative must exist", file.isFile)
        return file.readText()
    }

    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (File(dir, "gradle/libs.versions.toml").isFile &&
                File(dir, "app").isDirectory
            ) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}