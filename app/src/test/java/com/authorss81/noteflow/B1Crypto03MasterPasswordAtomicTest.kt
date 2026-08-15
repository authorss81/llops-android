package com.authorss81.noteflow

import com.authorss81.noteflow.services.EncryptionService
import com.authorss81.noteflow.services.MasterPasswordCredential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1-CRYPTO-03 (phase-62): the master-password credential — salt + wrapped DEK
 * + format — is ONE versioned blob committed in a SINGLE atomic write, so a
 * process kill between two independent pref writes can never leave a "new salt +
 * old/missing wrappedDEK" half pair that bricks every future unlock.
 *
 * The pre-fix code wrote salt and wrapped DEK as two separate
 * SharedPreferences `.apply()` calls (`NoteflowViewModel.kt:1794-1795/1829-1830`).
 * A kill exactly between them left e.g. new salt + old/missing wrapper; every
 * subsequent verify hit AEADBadTag permanently, the phase-09 H2 handler
 * quarantined the vault as `*.corrupt-*`, and the user lost everything.
 *
 * What is provable on the pure JVM (no Robolectric; `isReturnDefaultValues`):
 *  - [MasterPasswordCredential.serialize]/[parse] round-trip, and `parse` FAILS
 *    CLOSED on absent / malformed / half-written values;
 *  - committing through the atomic single-write contract against a fake store
 *    whose write can be "torn" (mirroring the SharedPreferences temp-file+rename
 *    commit semantics) ALWAYS leaves a COMPLETE, parseable credential — the
 *    previous one after a failed write, the new one after a success — never a
 *    mixture of new salt + old wrapper;
 *  - a store that dies on the SECOND write (the exact audit fault injection)
 *    refuses the later password-change and the surviving vault still unlocks
 *    with its previous password — never bricked;
 *  - the legacy pre-fix two-key pair still resolves, so existing vaults keep
 *    unlocking until the next set/change migrates them;
 *  - source pins: `setMasterPassword`/`changeMasterPassword` commit through
 *    `SettingsManager.commitMasterPasswordCredential` (single `.commit()`), the
 *    two standalone pref statements are gone from the ViewModel, the unlock
 *    flows read via `masterPasswordCredentialOrLegacy`, and the round-trip
 *    decrypt validation is present.
 */
class B1Crypto03MasterPasswordAtomicTest {

    // ---------- helpers ----------

    /**
     * Fake credential store mirroring the SharedPreferences contract: a write is
     * all-or-nothing (atomic rename), and a torn/killed write leaves the
     * PREVIOUS value untouched. [failWriteOnAttempt] injects the audit's fault —
     * the Nth write dies exactly like a process kill between the two writes.
     */
    private class FakeCredentialStore {
        var blob: String? = null
        var writeAttempts = 0
        var failWriteOnAttempt: Int = -1

        fun write(newBlob: String): Boolean {
            writeAttempts++
            if (writeAttempts == failWriteOnAttempt) return false
            blob = newBlob
            return true
        }

        /** Mirrors production's atomic commit: serialize the pair → ONE write. */
        fun atomicCommit(salt: ByteArray, wrappedDek: String): Boolean =
            write(MasterPasswordCredential.serialize(salt, wrappedDek))
    }

    private fun deriveWrapped(dek: ByteArray, password: String, salt: ByteArray): String =
        EncryptionService.encrypt(dek, EncryptionService.deriveKey(password, salt))

    /** Mirrors `unwrapMasterDek`'s candidate loop: can this password unlock it? */
    private fun survivesUnlock(credential: MasterPasswordCredential, password: String): Boolean {
        val candidates = EncryptionService.deriveKeyCandidates(password, credential.saltBytes())
        var ok = false
        for (candidate in candidates) {
            try {
                EncryptionService.decrypt(credential.wrappedDek, candidate)
                ok = true
            } catch (e: javax.crypto.AEADBadTagException) {
                // wrong candidate — try the next
            } finally {
                candidate.fill(0.toByte())
            }
        }
        return ok
    }

    // ---------- serialization: one value, fail closed ----------

    @Test
    fun `blob round-trips and parse fails closed on malformed or half values`() {
        val salt = EncryptionService.generateSalt()
        val dek = EncryptionService.generateDek()
        val wrapped = deriveWrapped(dek, "correct horse battery staple", salt)
        val blob = MasterPasswordCredential.serialize(salt, wrapped)

        val parsed = MasterPasswordCredential.parse(blob)
        assertNotNull("a complete blob must parse", parsed)
        assertEquals("the format marker must be preserved", MasterPasswordCredential.FORMAT_VERSION, parsed!!.formatVersion)
        assertEquals("the wrapped DEK must round-trip verbatim", wrapped, parsed.wrappedDek)
        assertTrue("the salt bytes must round-trip exactly", parsed.saltBytes().contentEquals(salt))

        val saltB64 = java.util.Base64.getEncoder().encodeToString(salt)
        assertNull("an absent blob must fail closed", MasterPasswordCredential.parse(null))
        assertNull(
            "a half pair (salt but no wrapper) must fail closed",
            MasterPasswordCredential.parse("${MasterPasswordCredential.FORMAT_VERSION}|$saltB64")
        )
        assertNull(
            "a blank wrapper must fail closed",
            MasterPasswordCredential.parse("${MasterPasswordCredential.FORMAT_VERSION}|$saltB64|")
        )
        assertNull(
            "an unknown format version must fail closed",
            MasterPasswordCredential.parse("OLD2|$saltB64|$wrapped")
        )
        assertNull("undecodable salt base64 must fail closed", MasterPasswordCredential.parse("${MasterPasswordCredential.FORMAT_VERSION}|!!!not-base64!!!|$wrapped"))
        assertNull("garbage must fail closed", MasterPasswordCredential.parse("not-a-blob"))
    }

    // ---------- the audit scenario: a write dies (kill between two writes) ----------

    @Test
    fun `a torn write leaves the previous credential durable and the vault unlockable`() {
        val dek = EncryptionService.generateDek()
        val oldSalt = EncryptionService.generateSalt()
        val wrappedOld = deriveWrapped(dek, "old-password", oldSalt)
        val oldBlob = MasterPasswordCredential.serialize(oldSalt, wrappedOld)

        val store = FakeCredentialStore()
        store.blob = oldBlob
        store.failWriteOnAttempt = 1 // the very commit write is torn (battery pull mid-write)

        val newSalt = EncryptionService.generateSalt()
        val wrappedNew = deriveWrapped(dek, "new-password", newSalt)

        val ok = store.atomicCommit(newSalt, wrappedNew)
        assertFalse("a torn commit must report failure", ok)
        assertEquals(
            "the atomic write left the OLD credential byte-identical — no half pair",
            oldBlob, store.blob
        )
        val surviving = MasterPasswordCredential.parse(store.blob)
        assertNotNull("the surviving state must still be a complete, parseable credential", surviving)
        val cred = surviving!!
        assertTrue("the vault still unlocks with the OLD password", survivesUnlock(cred, "old-password"))
        assertFalse("the NEW password must not yet be the wrapper (nothing half-applied)", survivesUnlock(cred, "new-password"))
    }

    @Test
    fun `a store that dies on the second write refuses the change and never bricks the vault`() {
        val dek = EncryptionService.generateDek()
        val oldSalt = EncryptionService.generateSalt()
        val wrappedOld = deriveWrapped(dek, "old-password", oldSalt)
        val store = FakeCredentialStore()
        store.blob = MasterPasswordCredential.serialize(oldSalt, wrappedOld)
        // The audit's injected fault: whatever the SECOND write is, it dies —
        // exactly like a process kill landing between two independent writes.
        store.failWriteOnAttempt = 2

        val newSalt = EncryptionService.generateSalt()
        val wrappedNew = deriveWrapped(dek, "new-password", newSalt)

        // First password change: write #1 lands as the complete new pair.
        assertTrue("the first commit (write #1) must succeed", store.atomicCommit(newSalt, wrappedNew))
        // Second password change: write #2 dies.
        assertFalse("the failing second write must fail the second commit", store.atomicCommit(newSalt, wrappedNew))

        val surviving = MasterPasswordCredential.parse(store.blob)
        assertNotNull("after the failed second write the vault still holds a complete credential", surviving)
        val cred = surviving!!
        assertEquals(
            "the surviving pair is the WHOLE first pair, never new-salt + old-wrapper",
            wrappedNew, cred.wrappedDek
        )
        assertTrue("the vault stays unlockable (with the last fully committed password)", survivesUnlock(cred, "new-password"))
    }

    @Test
    fun `under every torn-write position the surviving value is always a complete pair`() {
        val dek = EncryptionService.generateDek()
        val oldSalt = EncryptionService.generateSalt()
        val oldBlob = MasterPasswordCredential.serialize(oldSalt, deriveWrapped(dek, "old-password", oldSalt))
        val newSalt = EncryptionService.generateSalt()
        val wrappedNew = deriveWrapped(dek, "new-password", newSalt)

        for (tornAt in listOf(1, 2, 3, 4)) {
            val store = FakeCredentialStore()
            store.blob = oldBlob
            store.failWriteOnAttempt = tornAt
            repeat(4) {
                store.atomicCommit(newSalt, wrappedNew)
                val current = store.blob
                assertNotNull(
                    "torn-at=$tornAt: every post-attempt state must be a complete, parseable " +
                        "credential — the half pair that used to brick the vault is structurally impossible",
                    MasterPasswordCredential.parse(current)
                )
            }
        }
    }

    // ---------- legacy compatibility: pre-fix two-key vaults keep unlocking ----------

    @Test
    fun `legacy pre-fix two-key pair still resolves as a usable credential`() {
        val salt = EncryptionService.generateSalt()
        val saltB64 = java.util.Base64.getEncoder().encodeToString(salt)
        val dek = EncryptionService.generateDek()
        val wrapped = deriveWrapped(dek, "original", salt)

        val credential = MasterPasswordCredential.fromLegacy(saltB64, wrapped)

        assertNotNull("a pre-phase-62 vault (two independent prefs) must still unlock", credential)
        assertTrue("the legacy salt bytes must resolve exactly", credential!!.saltBytes().contentEquals(salt))
        assertEquals("the legacy wrapped DEK must be used verbatim", wrapped, credential.wrappedDek)
        assertNull("a legacy pair missing one half must fail closed", MasterPasswordCredential.fromLegacy(null, wrapped))
        assertNull("a legacy pair missing the other half must fail closed", MasterPasswordCredential.fromLegacy(saltB64, null))
        assertNull("blank legacy halves must fail closed", MasterPasswordCredential.fromLegacy("", ""))
    }

    // ---------- source pins: wiring lives in the Android-bound layers ----------

    @Test
    fun `master-password flows commit one atomic blob and never two pref writes`() {
        val vm = readNoteflowViewModel()
        val setBlock = vm.substringAfter("suspend fun setMasterPassword", "END")
            .substringBefore("suspend fun changeMasterPassword", "END")
        val changeBlock = vm.substringAfter("suspend fun changeMasterPassword", "END")
            .substringBefore("private fun computeLockoutDelayMs", "END")

        assertTrue("setMasterPassword must commit the credential atomically", setBlock.contains("commitMasterPasswordCredential"))
        assertTrue("changeMasterPassword must commit the credential atomically", changeBlock.contains("commitMasterPasswordCredential"))
        assertTrue(
            "setMasterPassword must round-trip validate the wrapped DEK before committing",
            setBlock.contains("EncryptionService.decrypt(wrapped, derivedKek)")
        )
        assertTrue(
            "changeMasterPassword must round-trip validate the wrapped DEK before committing",
            changeBlock.contains("EncryptionService.decrypt(wrapped, derivedKek)")
        )
        assertFalse("the two-pref salt write must be gone from setMasterPassword", setBlock.contains("settings.masterPasswordSalt ="))
        assertFalse("the two-pref wrapped write must be gone from setMasterPassword", setBlock.contains("settings.masterPasswordWrappedDek ="))
        assertFalse("the two-pref salt write must be gone from changeMasterPassword", changeBlock.contains("settings.masterPasswordSalt ="))
        assertFalse("the two-pref wrapped write must be gone from changeMasterPassword", changeBlock.contains("settings.masterPasswordWrappedDek ="))
    }

    @Test
    fun `the atomic commit is one synchronous commit and the lock flows read the single accessor`() {
        val sm = readSettingsManager()
        val commitBlock = sm.substringAfter("fun commitMasterPasswordCredential", "END")
            .substringBefore("var failedUnlockAttempts", "END")
        assertTrue(
            "the atomic commit must land as ONE putString of the versioned blob",
            commitBlock.contains("putString(\"master_password_credential\", blob)")
        )
        assertTrue(
            "the legacy two-key pair must be removed in the SAME commit",
            commitBlock.contains(".remove(\"master_password_salt\")") &&
                commitBlock.contains(".remove(\"master_password_wrapped_dek\")")
        )
        assertTrue("the commit must be the synchronous disk-sync .commit()", commitBlock.contains(".commit()"))
        assertFalse("an async .apply() two-step write must not exist in the commit", commitBlock.contains(".apply()"))
        assertTrue(
            "SettingsManager must expose the blob-or-legacy accessor",
            sm.contains("masterPasswordCredentialOrLegacy")
        )
        assertTrue(
            "the accessor must read the versioned blob first",
            sm.contains("MasterPasswordCredential.parse(prefs.getString(\"master_password_credential\"")
        )
        assertTrue(
            "the accessor must fall back to the legacy pair",
            sm.contains("MasterPasswordCredential.fromLegacy(")
        )

        val vm = readNoteflowViewModel()
        assertTrue(
            "verifyMasterPassword must fail closed when no credential is present",
            vm.substringAfter("suspend fun verifyMasterPassword", "END")
                .substringBefore("private suspend fun unwrapMasterDek", "END")
                .contains("masterPasswordCredentialOrLegacy == null")
        )
        val unwrapBlock = vm.substringAfter("private suspend fun unwrapMasterDek", "END")
            .substringBefore("suspend fun isMasterPasswordValid", "END")
        assertTrue(
            "unwrapMasterDek must read the credential through the single accessor",
            unwrapBlock.contains("masterPasswordCredentialOrLegacy")
        )
        assertFalse(
            "the direct legacy-pref reads must be gone from the unlock path",
            unwrapBlock.contains("settings.masterPasswordSalt") || unwrapBlock.contains("settings.masterPasswordWrappedDek")
        )
    }

    // ---------- file readers ----------

    private fun readNoteflowViewModel(): String {
        val file = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt")
        assertTrue("NoteflowViewModel.kt must exist", file.isFile)
        return file.readText()
    }

    private fun readSettingsManager(): String {
        val file = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/SettingsManager.kt")
        assertTrue("SettingsManager.kt must exist", file.isFile)
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