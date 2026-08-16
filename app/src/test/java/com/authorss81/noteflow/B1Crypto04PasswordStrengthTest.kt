package com.authorss81.noteflow

import com.authorss81.noteflow.services.PasswordStrengthPolicy
import com.authorss81.noteflow.services.PasswordStrengthVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1-CRYPTO-04 (phase-63): a NEW master password must be strong enough that an
 * offline GPU/FPGA PBKDF2-SHA-256 attacker cannot crack the wrapped DEK from a
 * copied vault in hours-to-days.
 *
 * The pre-fix state accepted any 6+ grapheme password with no complexity/entropy
 * check (`NoteflowViewModel.kt` used `MIN_PASSWORD_LENGTH = 6`; a 6-7 char
 * lowercase/numeric password falls to a GPU rig in hours). This phase adds the
 * pure-JVM [PasswordStrengthPolicy] as the authoritative gate on
 * `setMasterPassword`/`changeMasterPassword` (and the human-readable verdict in
 * the Dialogs.kt set/change dialogs):
 *   - length ≥ [PasswordStrengthPolicy.MIN_STRENGTH_GRAPHEMES] (8) — stronger
 *     than the old floor, still below the [com.authorss81.noteflow.services.EncryptionService.MAX_PASSWORD_GRAPHEMES] cap;
 *   - no sequential / keyboard-row / single-run-repeat patterns (`12345678`,
 *     `qwerty`, `aaaaaaaa`);
 *   - ≥ 3 distinct graphemes (kills the `ababab…` tiny-keyspace class);
 *   - character-class diversity for short passwords (< 12 graphemes need ≥ 3 of
 *     upper/lower/digit/symbol); passphrases ≥ 12 pass on length alone.
 *
 * Crucially the policy is measured on the NFKC-NORMALIZED password (the exact
 * byte string `EncryptionService.deriveKey` hashes, B2-CRYPTO-07), so full-width
 * `１２３４５６７８` folds to `12345678` and is rejected as sequential, and it is
 * enforced ONLY at set/change — `verifyMasterPassword`/`unwrapMasterDek`/
 * `isMasterPasswordValid` never touch it, so a pre-existing weaker vault keeps
 * unlocking and rotating. The UI lockout is documented UI-only (the vault is
 * only as strong as its password).
 */
class B1Crypto04PasswordStrengthTest {

    // ---------- rejected: short ----------

    @Test
    fun `short passwords are rejected`() {
        assertEquals(PasswordStrengthVerdict.TOO_SHORT, PasswordStrengthPolicy.evaluate(""))
        assertEquals(PasswordStrengthVerdict.TOO_SHORT, PasswordStrengthPolicy.evaluate("a"))
        assertEquals(PasswordStrengthVerdict.TOO_SHORT, PasswordStrengthPolicy.evaluate("1234"))
        assertEquals(
            "the pre-fix floor was 6 — 6 graphemes must now be rejected",
            PasswordStrengthVerdict.TOO_SHORT,
            PasswordStrengthPolicy.evaluate("123456")
        )
        assertEquals(PasswordStrengthVerdict.TOO_SHORT, PasswordStrengthPolicy.evaluate("abcdef"))
        assertEquals(PasswordStrengthVerdict.TOO_SHORT, PasswordStrengthPolicy.evaluate("hunter2"))
        assertEquals(PasswordStrengthVerdict.TOO_SHORT, PasswordStrengthPolicy.evaluate("secret1"))
    }

    @Test
    fun `over-long passwords are rejected`() {
        val long = "A1!B2@C3#D4@E5%F6^G7&H8*I9(J0)K1!L2@M3#N4@O5%P6^Q7&R8*S9(T0)".repeat(8)
        assertTrue(long.length > 128)
        assertEquals(PasswordStrengthVerdict.TOO_LONG, PasswordStrengthPolicy.evaluate(long))
    }

    // ---------- rejected: sequential / keyboard / repeated ----------

    @Test
    fun `sequential ascending and descending passwords are rejected`() {
        assertEquals(PasswordStrengthVerdict.SEQUENTIAL, PasswordStrengthPolicy.evaluate("12345678"))
        assertEquals(PasswordStrengthVerdict.SEQUENTIAL, PasswordStrengthPolicy.evaluate("abcdefgh"))
        assertEquals(PasswordStrengthVerdict.SEQUENTIAL, PasswordStrengthPolicy.evaluate("987654321"))
        assertEquals(PasswordStrengthVerdict.SEQUENTIAL, PasswordStrengthPolicy.evaluate("zyxwvuts"))
        assertEquals(PasswordStrengthVerdict.SEQUENTIAL, PasswordStrengthPolicy.evaluate("2468qwerty"))
    }

    @Test
    fun `keyboard-row passwords are rejected`() {
        assertEquals(PasswordStrengthVerdict.SEQUENTIAL, PasswordStrengthPolicy.evaluate("qwertyuiop"))
        assertEquals(PasswordStrengthVerdict.SEQUENTIAL, PasswordStrengthPolicy.evaluate("asdfghjkl1"))
        assertEquals(PasswordStrengthVerdict.SEQUENTIAL, PasswordStrengthPolicy.evaluate("1qaz2wsx"))
        assertEquals(PasswordStrengthVerdict.SEQUENTIAL, PasswordStrengthPolicy.evaluate("qwerty123"))
    }

    @Test
    fun `repeated or near-single-keyspace passwords are rejected`() {
        assertEquals(PasswordStrengthVerdict.WEAK, PasswordStrengthPolicy.evaluate("aaaaaaaa"))
        assertEquals(PasswordStrengthVerdict.WEAK, PasswordStrengthPolicy.evaluate("11111111"))
        assertEquals(PasswordStrengthVerdict.WEAK, PasswordStrengthPolicy.evaluate("12121212"))
        assertEquals(PasswordStrengthVerdict.WEAK, PasswordStrengthPolicy.evaluate("a".repeat(40)))
        assertEquals(PasswordStrengthVerdict.WEAK, PasswordStrengthPolicy.evaluate("12".repeat(20)))
    }

    // ---------- rejected: low class diversity on short passwords ----------

    @Test
    fun `short passwords with low class diversity are rejected`() {
        assertEquals(PasswordStrengthVerdict.LOW_DIVERSITY, PasswordStrengthPolicy.evaluate("password1"))
        assertEquals(PasswordStrengthVerdict.LOW_DIVERSITY, PasswordStrengthPolicy.evaluate("letmein1"))
        assertEquals(PasswordStrengthVerdict.LOW_DIVERSITY, PasswordStrengthPolicy.evaluate("iloveyou9"))
        assertEquals(
            "lowercase-only 8..11 graphemes must be rejected",
            PasswordStrengthVerdict.LOW_DIVERSITY,
            PasswordStrengthPolicy.evaluate("monkey123")
        )
    }

    // ---------- rejected through NFKC normalization ----------

    @Test
    fun `full-width compatibility characters fold to the normalized form and are judged on it`() {
        // U+FF11..U+FF18 full-width digits — NFKC-normalizes to "12345678",
        // which is sequential. The policy must judge the stored+derived form.
        assertEquals(PasswordStrengthVerdict.SEQUENTIAL, PasswordStrengthPolicy.evaluate("１２３４５６７８"))
        // And a full-width-short password is judged short.
        assertEquals(PasswordStrengthVerdict.TOO_SHORT, PasswordStrengthPolicy.evaluate("１２３４５６"))
    }

    // ---------- accepted set ----------

    @Test
    fun `strong diversified passwords are accepted`() {
        assertEquals(PasswordStrengthVerdict.ACCEPTED, PasswordStrengthPolicy.evaluate("CorrectHorseBatteryStaple9!"))
        assertEquals(PasswordStrengthVerdict.ACCEPTED, PasswordStrengthPolicy.evaluate("Tr0ub4dor&3"))
        assertEquals(PasswordStrengthVerdict.ACCEPTED, PasswordStrengthPolicy.evaluate("W3lcome2Vault!"))
        assertEquals(PasswordStrengthVerdict.ACCEPTED, PasswordStrengthPolicy.evaluate("D4rkMn2!"))
        assertEquals(PasswordStrengthVerdict.ACCEPTED, PasswordStrengthPolicy.evaluate("Éléphant9!"))
    }

    @Test
    fun `long passphrases pass on length even with low class diversity`() {
        // >= 12 graphemes: length-alone acceptance (passphrase exception).
        assertEquals(PasswordStrengthVerdict.ACCEPTED, PasswordStrengthPolicy.evaluate("correct horse battery staple"))
        assertEquals(PasswordStrengthVerdict.ACCEPTED, PasswordStrengthPolicy.evaluate("correcthorsebatterystaple"))
        assertEquals(PasswordStrengthVerdict.ACCEPTED, PasswordStrengthPolicy.evaluate("Sunshine#2026"))
    }

    @Test
    fun `accepted passwords always satisfy the underlying length bounds`() {
        for (pwd in listOf(
            "CorrectHorseBatteryStaple9!",
            "Tr0ub4dor&3",
            "W3lcome2Vault!",
            "D4rkMn2!",
            "correct horse battery staple",
        )) {
            val graphemes = com.authorss81.noteflow.services.EncryptionService.normalizedGraphemeCount(pwd)
            assertTrue("accepted password must be >= 8 graphemes (was $graphemes)", graphemes >= 8)
        }
    }

    // ---------- source pins: enforcement + scope ----------

    @Test
    fun `set and change master password route through the strength policy and never at unlock`() {
        val vm = readNoteflowViewModel()
        val evaluateCount = "PasswordStrengthPolicy.evaluate".toRegex().findAll(vm).count()
        assertEquals(
            "PasswordStrengthPolicy.evaluate must be wired exactly at setMasterPassword and " +
                "changeMasterPassword (authoritative gate), never anywhere else",
            2,
            evaluateCount
        )
        val setBlock = vm.substringAfter("suspend fun setMasterPassword", "END").substringBefore("suspend fun changeMasterPassword", "END")
        assertTrue("setMasterPassword must reject on the policy verdict", setBlock.contains("PasswordStrengthPolicy.evaluate(password).accepted"))
        val changeBlock = vm.substringAfter("suspend fun changeMasterPassword", "END").substringBefore("private fun computeLockoutDelayMs", "END")
        assertTrue(
            "changeMasterPassword must gate only the NEW password on the policy",
            changeBlock.contains("PasswordStrengthPolicy.evaluate(newPassword).accepted")
        )
        assertFalse(
            "the old pre-fix bare length gate must be gone from set/change",
            setBlock.contains("isValidPasswordLength") || changeBlock.contains("isValidPasswordLength")
        )
        val unlockBlock = vm.substringAfter("suspend fun verifyMasterPassword", "END").substringBefore("suspend fun setBiometricEnabled", "END")
        assertFalse(
            "unlock must never strength-gate — a pre-existing weaker vault keeps unlocking",
            unlockBlock.contains("PasswordStrengthPolicy")
        )
        val isValidBlock = vm.substringAfter("suspend fun isMasterPasswordValid", "END").substringBefore("suspend fun setBiometricEnabled", "END")
        assertFalse(
            "isMasterPasswordValid (side-effect-free oracle) must stay strength-gated-free",
            isValidBlock.contains("PasswordStrengthPolicy")
        )
    }

    @Test
    fun `the set and change dialogs surface the exact verdict message`() {
        val dialogs = readDialogs()
        assertTrue(
            "set-password dialog must show the policy verdict's human-readable message",
            dialogs.contains("val verdict = PasswordStrengthPolicy.evaluate(password)") &&
                dialogs.contains("errorMessage = verdict.message")
        )
        assertTrue(
            "change-password dialog must show the policy verdict's human-readable message",
            dialogs.contains("val verdict = PasswordStrengthPolicy.evaluate(newPass)") &&
                dialogs.contains("changeError = verdict.message")
        )
    }

    @Test
    fun `the strength policy is only referenced by the VM gate and the dialogs`() {
        val repoRoot = repoRoot()
        val references = mutableListOf<Pair<String, Int>>()
        java.io.File(repoRoot, "app/src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { f ->
                val rel = f.path.removePrefix(repoRoot.path).removePrefix("/")
                val count = "PasswordStrengthPolicy".toRegex().findAll(f.readText()).count()
                if (count > 0) references.add(rel to count)
            }
        val expected = setOf(
            "app/src/main/kotlin/com/authorss81/noteflow/services/PasswordStrengthPolicy.kt",
            // B2-CRYPTO-04 (phase-84): the backup-password policy reuses the SAME
            // strength decision table (backups ride to public Downloads/WebDAV and
            // must clear the identical bar as the vault master password).
            "app/src/main/kotlin/com/authorss81/noteflow/services/BackupPasswordPolicy.kt",
            "app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt",
            "app/src/main/kotlin/com/authorss81/noteflow/ui/components/Dialogs.kt",
        )
        val actual = references.map { it.first }.toSet()
        assertEquals(
            "the strength policy must not leak into any other subsystem",
            expected,
            actual
        )
    }

    @Test
    fun `policy documents that lockout is UI-only and vault strength equals password strength`() {
        val policy = java.io.File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/services/PasswordStrengthPolicy.kt"
        ).readText()
        assertTrue(
            "the finding's 'lockout is UI-only' caveat must be documented in the policy",
            policy.contains("UI-only") && policy.contains("offline attacker")
        )
        assertTrue(
            "Argon2id / TEE-bound attempt gating must be documented as the follow-up, not implemented",
            policy.contains("Argon2id") || policy.contains("TEE-bound")
        )
    }

    // ---------- file readers ----------

    private fun readNoteflowViewModel(): String {
        val file = java.io.File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt"
        )
        assertTrue("NoteflowViewModel.kt must exist", file.isFile)
        return file.readText()
    }

    private fun readDialogs(): String {
        val file = java.io.File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/ui/components/Dialogs.kt"
        )
        assertTrue("Dialogs.kt must exist", file.isFile)
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
