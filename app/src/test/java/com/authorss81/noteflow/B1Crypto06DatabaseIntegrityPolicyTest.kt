package com.authorss81.noteflow

import com.authorss81.noteflow.services.DatabaseIntegrityPolicy
import com.authorss81.noteflow.services.DatabaseIntegrityVerdict
import com.authorss81.noteflow.utils.ConstantTime
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1-CRYPTO-06 (phase-91) behavioral + wiring tests: the vault tamper baseline
 * verification is FAIL-CLOSED.
 *
 * Pre-fix, `DatabaseSecurityHelper.verifyDatabaseIntegrity`
 * (`DatabaseSecurityHelper.kt:146-154`) collapsed every "cannot verify"
 * situation into `true` (verified):
 *  - a MISSING stored checksum ran `updateStoredChecksum(context); return true`
 *    — silently RE-BASELINING the HMAC against whatever SQLCipher file was on
 *    disk, so an attacker who can delete the `db_hmac_checksum` pref gets the
 *    app to bless a possibly-tampered file as "verified"; and
 *  - `computeDatabaseHmac` returning `null` (DB file absent/empty, keystore key
 *    missing, or a stream error) fell through `?: return true`.
 *
 * What is provable on the pure JVM:
 *  - [DatabaseIntegrityPolicy.verdictFor] is the ONE three-outcome decision:
 *    [DatabaseIntegrityVerdict.Verified] (baseline present + matching current
 *    HMAC), [DatabaseIntegrityVerdict.Mismatch] (baseline present + differing
 *    bytes — genuine tamper), [DatabaseIntegrityVerdict.CannotVerify] (baseline
 *    MISSING or current HMAC UN-COMPUTABLE) — which is never `true`.
 *  - the comparison routes through [ConstantTime.hexEqual]
 *    (full-length `MessageDigest.isEqual`, no early-exit string equality).
 *  - the helper's verification source contains NO write path (no
 *    `updateStoredChecksum`, no `return true`, no re-baseline).
 *  - the ViewModel surfaces a DISTINCT "cannot verify" state
 *    (`databaseIntegrityUnverified`), keeps the tamper banner for Mismatch
 *    (per-session dismissal), and only ever auto-arms a BRAND-NEW vault's
 *    baseline (`!vaultFilePresentAtStart && !hasStoredChecksum`) from
 *    `initializeDataCore` — never a re-baseline of an existing vault.
 *  - MainActivity renders the distinct non-alarming notice and reuses the same
 *    per-session dismissal.
 */
class B1Crypto06DatabaseIntegrityPolicyTest {

    // ---------- the decision table ----------

    @Test
    fun `a matching stored and current checksum is Verified`() {
        val verdict = DatabaseIntegrityPolicy.verdictFor("abc123", "abc123")
        assertEquals(DatabaseIntegrityVerdict.Verified, verdict)
        assertTrue(DatabaseIntegrityPolicy.isVerified(verdict))
        assertFalse(DatabaseIntegrityPolicy.isTamperedOrUnverifiable(verdict))
    }

    @Test
    fun `a differing current checksum is a Mismatch - genuine tamper`() {
        val verdict = DatabaseIntegrityPolicy.verdictFor("abc123", "abd123")
        assertEquals(DatabaseIntegrityVerdict.Mismatch, verdict)
        assertFalse(DatabaseIntegrityPolicy.isVerified(verdict))
        assertTrue(DatabaseIntegrityPolicy.isTamperedOrUnverifiable(verdict))
    }

    @Test
    fun `a MISSING stored checksum is CannotVerify - never re-baselined to verified`() {
        val verdict = DatabaseIntegrityPolicy.verdictFor(null, "abc123")
        assertEquals(DatabaseIntegrityVerdict.CannotVerify, verdict)
        assertFalse("a missing baseline must NEVER collapse into 'verified'", DatabaseIntegrityPolicy.isVerified(verdict))
        assertTrue(DatabaseIntegrityPolicy.isTamperedOrUnverifiable(verdict))
    }

    @Test
    fun `an UN-COMPUTABLE current checksum is CannotVerify - never trusted`() {
        val verdict = DatabaseIntegrityPolicy.verdictFor("abc123", null)
        assertEquals(DatabaseIntegrityVerdict.CannotVerify, verdict)
        assertFalse("an un-computable HMAC must NEVER collapse into 'verified'", DatabaseIntegrityPolicy.isVerified(verdict))
        assertTrue(DatabaseIntegrityPolicy.isTamperedOrUnverifiable(verdict))
    }

    @Test
    fun `both missing and both un-computable are CannotVerify`() {
        assertEquals(
            DatabaseIntegrityVerdict.CannotVerify,
            DatabaseIntegrityPolicy.verdictFor(null, null)
        )
        assertEquals(
            DatabaseIntegrityVerdict.CannotVerify,
            DatabaseIntegrityPolicy.verdictFor("abc123", null)
        )
        assertEquals(
            DatabaseIntegrityVerdict.CannotVerify,
            DatabaseIntegrityPolicy.verdictFor(null, "abc123")
        )
    }

    @Test
    fun `the comparison routes through ConstantTime full-length equality`() {
        // Pins the (B2-CRYPTO-01) constant-time compare — the fix must not have
        // reintroduced String.equals/== on the checksum.
        val policySource = readSource("services/DatabaseIntegrityPolicy.kt")
        assertTrue(
            "the verdict must compare via ConstantTime.hexEqual, never string equality",
            policySource.contains("ConstantTime.hexEqual(storedChecksum, currentChecksum)")
        )
        assertFalse("String.equals on the checksum is banned (CWE-650)", policySource.contains("storedChecksum == currentChecksum"))
        assertFalse("String.equals on the checksum is banned (CWE-650)", policySource.contains("storedChecksum!!.equals"))
    }

    // ---------- the helper never writes and never re-baselines ----------

    @Test
    fun `DatabaseSecurityHelper verifyDatabaseIntegrity is fail-closed and write-free`() {
        val helper = readSource("services/DatabaseSecurityHelper.kt")
        // verifyDatabaseIntegrity is the LAST function in the object, so the tail
        // after its signature is exactly its body + closing braces.
        val verifyBlock = helper.substringAfter("fun verifyDatabaseIntegrity(context: Context)")
        assertTrue(
            "the helper must route through the pure-JVM decision table",
            verifyBlock.contains("DatabaseIntegrityPolicy.verdictFor(stored, current)")
        )
        assertFalse(
            "the FAIL-OPEN re-baseline `updateStoredChecksum(context); return true` is GONE",
            verifyBlock.contains("updateStoredChecksum")
        )
        assertFalse(
            "no verification branch may collapse into a bare `return true`",
            verifyBlock.contains("return true")
        )
    }

    @Test
    fun `the helper exposes a read-only hasStoredChecksum and its write-only counterpoint`() {
        val helper = readSource("services/DatabaseSecurityHelper.kt")
        assertTrue("a read-only baseline-exists check must exist", helper.contains("fun hasStoredChecksum(context: Context): Boolean"))
        val hasBlock = helper.substringAfter("fun hasStoredChecksum(context: Context): Boolean")
            .substringBefore("\n    fun ", "END")
        assertFalse("hasStoredChecksum must be READ-ONLY", hasBlock.contains(".edit()"))
        // The baseline pref is write-only through updateStoredChecksum / rearmBaselineFromFile
        // (the trusted arm sites) — never inside a verification.
        assertTrue("the pref key must be the db_hmac_checksum the helper owns", helper.contains("PREF_DB_CHECKSUM"))
    }

    // ---------- the ViewModel surfaces a distinct cannot-verify state ----------

    @Test
    fun `the ViewModel declares and wires a distinct cannot-verify state`() {
        val vm = readSource("ui/viewmodel/NoteflowViewModel.kt")
        assertTrue(
            "a StateFlow distinct from databaseTampered must exist",
            vm.contains("private val _databaseIntegrityUnverified = MutableStateFlow(false)")
        )
        assertTrue(
            "the immutable flow must be exposed for the UI",
            vm.contains("val databaseIntegrityUnverified: StateFlow<Boolean> = _databaseIntegrityUnverified.asStateFlow()")
        )
    }

    @Test
    fun `the verdict maps CannotVerify to the notice and Mismatch to the tamper banner`() {
        val vm = readSource("ui/viewmodel/NoteflowViewModel.kt")
        val mapBlock = vm.substringAfter("private fun applyDatabaseIntegrityVerdict(")
            .substringBefore("private suspend fun verifyDatabaseIntegrityNow(", "END")
        assertTrue(
            "a stored+matching baseline must clear both states",
            mapBlock.contains("DatabaseIntegrityVerdict.Verified -> {") &&
                mapBlock.contains("_databaseTampered.value = false") &&
                mapBlock.contains("_databaseIntegrityUnverified.value = false")
        )
        assertTrue(
            "a genuine mismatch must surface the alarming banner (uneventually through the session gate)",
            mapBlock.contains("DatabaseIntegrityVerdict.Mismatch -> {") &&
                mapBlock.contains("integrityWarningDismissal.mayShow()")
        )
        assertTrue(
            "a cannot-verify state must surface the distinct notice, fail-closed",
            mapBlock.contains("DatabaseIntegrityVerdict.CannotVerify -> {") &&
                mapBlock.contains("_databaseIntegrityUnverified.value = !freshUnarmedVault")
        )
        assertFalse(
            "the verification path must never re-baseline",
            mapBlock.contains("stampDatabaseChecksum")
        )
    }

    @Test
    fun `the fresh-vault baseline arm is guarded to first-run only`() {
        val vm = readSource("ui/viewmodel/NoteflowViewModel.kt")
        val initCore = vm.substringAfter("private suspend fun initializeDataCore()")
            .substringBefore("init {", "END")
        assertTrue(
            "the fresh-vault arm must require a file ABSENT at start AND no baseline",
            initCore.contains("!vaultFilePresentAtStart && !DatabaseSecurityHelper.hasStoredChecksum(appContext)")
        )
        assertTrue(
            "the guarded arm must stamp the baseline once the fresh vault is created",
            initCore.contains("repository.stampDatabaseChecksum(appContext)")
        )
        assertTrue(
            "the process-start vault-presence probe must exist",
            vm.contains("private val vaultFilePresentAtStart: Boolean")
        )
    }

    @Test
    fun `the cannot-verify dismissal is per-session like the tamper banner`() {
        val vm = readSource("ui/viewmodel/NoteflowViewModel.kt")
        val dismissBlock = vm.substringAfter("fun dismissDatabaseIntegrityWarning(")
            .substringBefore("fun setDatabaseIntegrityCheckEnabled", "END")
        assertTrue(
            "the notice hides through the shared per-session gate",
            dismissBlock.contains("integrityWarningDismissal.onDismiss(dontShowAgain)")
        )
        assertTrue(
            "dismissing the notice must clear the distinct state for this session",
            dismissBlock.contains("_databaseIntegrityUnverified.value = false")
        )
        assertFalse(
            "dismissal must NEVER permanently flip the persisted check flag",
            dismissBlock.contains("databaseIntegrityCheckEnabled")
        )
    }

    // ---------- MainActivity surfaces the distinct non-alarming notice ----------

    @Test
    fun `MainActivity surfaces the distinct cannot-verify notice and per-session dismissal`() {
        val activity = readSource("MainActivity.kt")
        assertTrue(
            "the notice banner must exist under the distinct state",
            activity.contains("if (databaseIntegrityUnverified) {")
        )
        assertTrue(
            "the notice must be collected for recomposition",
            activity.contains("viewModel.databaseIntegrityUnverified.collectAsState()")
        )
        assertTrue(
            "the notice text must come from the policy constant (single wording source)",
            activity.contains("DatabaseIntegrityPolicy.CANNOT_VERIFY_NOTICE")
        )
        assertTrue(
            "the notice must be honestly distinguishable from the tamper banner (non-alarming)",
            activity.contains("Vault Integrity Could Not Be Verified")
        )
        assertTrue(
            "the notice must reuse the honest per-session dismissal label",
            activity.contains("Don't show again this session")
        )
        assertTrue(
            "the notice dismissal must route through the shared dismiss handler",
            activity.contains("viewModel.dismissDatabaseIntegrityWarning(dontShowAgain)")
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