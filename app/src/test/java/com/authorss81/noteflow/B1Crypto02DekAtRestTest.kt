package com.authorss81.noteflow

import com.authorss81.noteflow.services.DekAtRestMode
import com.authorss81.noteflow.services.DekAtRestPolicy
import com.authorss81.noteflow.services.DekDeviceBlob
import com.authorss81.noteflow.services.DekDeviceStore
import com.authorss81.noteflow.services.EncryptionService
import com.authorss81.noteflow.services.SecurityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1-CRYPTO-02 (phase-45) behavioral tests over the REAL [SecurityService] code
 * paths, driven through the [DekDeviceStore] seam (the production store is
 * `SharedPrefsDekDeviceStore`; tests substitute an in-memory fake so no
 * AndroidKeyStore/Context is required).
 *
 * What is provable on the pure JVM (no AndroidKeyStore provider): the
 * fail-closed invariants — an absent or auth-gated device blob makes
 * [SecurityService.readDek] return null with no credential, and
 * [SecurityService.clearDek] empties the store — plus the exact enforcement
 * sequence the master-password flows run (`NoteflowViewModel.enforceDekAtRestPolicy`).
 * `storeDek`, in contrast, needs the AndroidKeyStore provider and is exercised
 * only at build/device runtime; this suite pins that password-only mode can never
 * write to the store and that PASSWORD_ONLY mode clears before `setMasterPassword`
 * reports success.
 */
class B1Crypto02DekAtRestTest {

    // ---------- helpers ----------

    private class FakeDekDeviceStore : DekDeviceStore {
        var blob: DekDeviceBlob? = null
        var writes = 0
        var clears = 0

        override fun read(): DekDeviceBlob? = blob
        override fun write(blob: DekDeviceBlob) {
            this.blob = blob
            writes++
        }
        override fun clear(): Boolean {
            blob = null
            clears++
            return true
        }
    }

    /** Mirror of `NoteflowViewModel.enforceDekAtRestPolicy`. */
    private fun enforce(security: SecurityService, hasPassword: Boolean, biometrics: Boolean, dek: ByteArray?): Boolean {
        return when (DekAtRestPolicy.modeFor(hasPassword, biometrics)) {
            DekAtRestMode.PASSWORD_ONLY -> security.clearDek()
            DekAtRestMode.BIOMETRIC_GATED_AUTH_COPY -> if (dek != null) security.storeDek(dek, authRequired = true) else false
            DekAtRestMode.DEVICE_WRAPPED_NOT_AUTHGATED -> true
        }
    }

    // ---------- the B1-CRYPTO-02 scenarios ----------

    @Test
    fun `setting a master password with biometrics off removes the non-auth device copy`() {
        val store = FakeDekDeviceStore()
        // Pre-fix state: getOrCreateDek wrapped the real vault DEK under the
        // NON-auth AndroidKeyStore key and left it in prefs.
        store.blob = DekDeviceBlob(encoded = "cHJlLWZpeC1ub24tYXV0aC1jb3B5", authRequired = false)
        val security = SecurityService(store)

        // setMasterPassword's final act: enforceDekAtRestPolicy() with the new
        // password-derived wrapper already committed and biometrics still OFF.
        enforce(security, hasPassword = true, biometrics = false, dek = ByteArray(32) { 1 })

        assertNull("non-auth device copy must be purged from prefs", store.read())
        assertEquals("SecurityService.clearDek must be invoked", 1, store.clears)
        assertEquals("nothing may be re-written in PASSWORD_ONLY mode", 0, store.writes)
        assertNull("readDek() without any credential must fail closed", security.readDek())
    }

    @Test
    fun `every password unlock with biometrics off also purges the non-auth copy`() {
        val store = FakeDekDeviceStore()
        store.blob = DekDeviceBlob("cHJlLWZpeC1ub24tYXV0aC1jb3B5", false)
        val security = SecurityService(store)

        // verifyMasterPassword success path re-asserts the policy per unlock.
        enforce(security, hasPassword = true, biometrics = false, dek = ByteArray(32) { 2 })

        assertNull(store.read())
        assertNull(security.readDek())
    }

    @Test
    fun `disable-biometrics path removes the device copy instead of re-wrapping non-auth`() {
        val store = FakeDekDeviceStore()
        // Old setBiometricEnabled(false, …) called storeDek(dek, authRequired=false),
        // resurrecting the bypass. The new flow sets the flag then enforces policy.
        store.blob = DekDeviceBlob("b2xkLWF1dGgtZ2F0ZWQtYmxvYg", true)
        val security = SecurityService(store)

        enforce(security, hasPassword = true, biometrics = false, dek = ByteArray(32) { 3 })

        assertNull("disabling biometrics must clear the device copy", store.read())
        assertNull(security.readDek())
    }

    @Test
    fun `biometric-gated device copy cannot be read without the biometric flow`() {
        val store = FakeDekDeviceStore()
        store.blob = DekDeviceBlob("YXV0aC1yZXF1aXJlZC1ibG9i", authRequired = true)
        val security = SecurityService(store)

        assertNull("readDek must refuse an auth-gated blob (biometric unlock required)", security.readDek())
        assertNull(
            "getOrCreateDek must not mint/re-persist a fresh DEK while an auth-gated blob is present",
            security.getOrCreateDek()
        )
    }

    @Test
    fun `absent device blob - readDek fails closed with no password`() {
        val security = SecurityService(FakeDekDeviceStore())
        assertNull("no stored blob ⇒ readDek returns null", security.readDek())
    }

    @Test
    fun `master password is the only DEK wrapper when biometrics are off - passwordless read impossible`() {
        val store = FakeDekDeviceStore()
        val security = SecurityService(store)

        // Simulate the full first-run sequence:
        // 1. passwordless boot minted a device copy (init path) …
        // 2. … then the user set a master password (enforce with biometry off).
        enforce(security, hasPassword = true, biometrics = false, dek = ByteArray(32) { 9 })

        assertNull(store.read())
        assertNull(security.readDek())
        // The vault now opens ONLY through the password-derived KEK path
        // (verifyMasterPassword / unwrapMasterDek) which requires the salt +
        // wrapped DEK stored in SettingsManager — never via readDek.
    }

    // ---------- regression: passwordless boot still works ----------

    @Test
    fun `passwordless boot keeps a readable device copy`() {
        val store = FakeDekDeviceStore()
        // The passwordless init path (NoteflowViewModel.init) mints + persists the
        // non-gated device copy; the policy must leave it untouched so the vault
        // boots without a credential.
        store.blob = DekDeviceBlob("cHJlLXZhdWx0LmRldmljZS1jb3B5", authRequired = false)
        val security = SecurityService(store)

        val enforced = enforce(security, hasPassword = false, biometrics = false, dek = ByteArray(32) { 4 })

        assertTrue("passwordless mode must enforce successfully", enforced)
        assertEquals("the passwordless device copy must not be cleared", 0, store.clears)
        assertEquals("nothing may be re-written either", 0, store.writes)
        assertNotNull("the device copy stays at rest for the boot credential", store.read())
    }

    // ---------- phase-45 review fix: locked open must never mint a DEK ----------

    @Test
    fun `locked open never mints a fresh DEK over a password-protected vault`() {
        val store = FakeDekDeviceStore()
        // Master password + biometrics OFF: the device copy was cleared at
        // setMasterPassword, so a locked open (VaultKeyHolder.dek == null) hits
        // getOrCreateDek with an empty store.
        val security = SecurityService(store)

        val minted = security.getOrCreateDek(allowPasswordlessMint = false)

        assertNull("a locked open must fail closed instead of minting a fresh DEK", minted)
        assertNull("and must not leave a fresh non-auth blob at rest", store.read())
        assertEquals("no write may be emitted for a password-protected vault", 0, store.writes)
    }

    @Test
    fun `passwordless vault may still mint when a device copy is missing`() {
        val store = FakeDekDeviceStore()
        val security = SecurityService(store)

        val minted = security.getOrCreateDek(allowPasswordlessMint = true)

        assertNotNull(
            "passwordless boot is allowed to mint its own DEK (storeDek persistence is " +
                "device-runtime-only and needs AndroidKeyStore, unavailable on the JVM)",
            minted
        )
    }

    @Test
    fun `clearDek empties the store including the stale auth-gating flag`() {
        val store = FakeDekDeviceStore()
        store.blob = DekDeviceBlob("InBvdGVudGlhbC10YW1wZXItcHJvb2YtaW0", authRequired = true)
        val security = SecurityService(store)

        security.clearDek()

        assertNull(store.read())
        assertNull(security.readDek())
        val salt = EncryptionService.generateSalt()
        val dek = EncryptionService.generateDek()
        val wrapped = EncryptionService.encrypt(dek, EncryptionService.deriveKey("correct horse battery staple", salt))
        assertNull(
            "the password-wrapped DEK is independent of the device store",
            store.read()
        )
    }

    // ---------- wiring pin: the master-password flows must call the policy ----------
    // The enforcement sequence lives in the Android-bound NoteflowViewModel, which
    // cannot be instantiated in a pure-JVM test. Pin the wiring at source level
    // (same technique as SecurityCryptoAbsenceTest) so a future refactor cannot
    // silently drop the clearDek call and resurrect the bypass.

    @Test
    fun `master-password flows wire the at-rest policy enforcement`() {
        val source = readNoteflowViewModelSource()
        assertTrue(
            "setMasterPassword must enforce the policy after committing the wrapped DEK",
            source.substringAfter("suspend fun setMasterPassword", "END")
                .substringBefore("suspend fun changeMasterPassword", "END")
                .contains("enforceDekAtRestPolicy()")
        )
        assertTrue(
            "changeMasterPassword must enforce the policy after re-wrapping the DEK",
            source.substringAfter("suspend fun changeMasterPassword", "END")
                .substringBefore("private fun computeLockoutDelayMs", "END")
                .contains("enforceDekAtRestPolicy()")
        )
        assertTrue(
            "verifyMasterPassword (every password unlock) must enforce the policy",
            source.substringAfter("suspend fun verifyMasterPassword", "END")
                .substringBefore("suspend fun isMasterPasswordValid", "END")
                .contains("enforceDekAtRestPolicy()")
        )
        assertTrue(
            "setBiometricEnabled must route through the policy (never a non-auth store)",
            source.substringAfter("suspend fun setBiometricEnabled", "END")
                .substringBefore("fun getBiometricCipher", "END")
                .contains("enforceDekAtRestPolicy()")
        )
        assertTrue(
            "verifyBiometricsAndUnlock must re-assert the policy",
            source.substringAfter("fun verifyBiometricsAndUnlock", "END")
                .substringBefore("fun disableBiometricFallback", "END")
                .contains("enforceDekAtRestPolicy()")
        )
        assertTrue(
            "the pre-fix inline non-auth store on disable must be gone from setBiometricEnabled",
            !source.substringAfter("suspend fun setBiometricEnabled", "END")
                .substringBefore("fun getBiometricCipher", "END")
                .contains("storeDek(dek, authRequired = enabled)")
        )
    }

    @Test
    fun `db factory must gate passwordless minting on the master-password state`() {
        val source = readNoteflowDatabaseSource()
        val factoryBlock = source.substringAfter("NoteflowSqlcipherFactory", "END")
        assertTrue(
            "NoteflowSqlcipherFactory must never mint a DEK when a master password exists",
            factoryBlock.contains("allowPasswordlessMint")
        )
        assertTrue(
            "the gate must read the master-password state from settings",
            factoryBlock.contains("hasMasterPassword")
        )
    }

    private fun readNoteflowDatabaseSource(): String {
        val file = java.io.File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/data/db/NoteflowDatabase.kt"
        )
        assertTrue("NoteflowDatabase.kt must exist", file.isFile)
        return file.readText()
    }

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