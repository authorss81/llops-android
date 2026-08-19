package com.authorss81.noteflow

import com.authorss81.noteflow.services.DekDeviceBlob
import com.authorss81.noteflow.services.DekDeviceStore
import com.authorss81.noteflow.services.DekReadResult
import com.authorss81.noteflow.services.KeystoreKeyLostException
import com.authorss81.noteflow.services.SecurityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * B1-CRYPTO-05 (phase-64) behavioral + wiring tests.
 *
 * Finding: `SecurityService.readDek` returned null on ANY failure — including a
 * stored blob whose AndroidKeyStore wrapping key was LOST — and
 * `getOrCreateDek` then silently minted a brand-new DEK and OVERWROTE the stored
 * wrapper. The next SQLCipher open tried the new passphrase against the
 * still-encrypted vault, the phase-09 H2 quarantiner reported the survivable
 * vault as `*.corrupt-*`, and there was no diagnostic distinguishing "key lost"
 * from "data corrupt".
 *
 * What is provable on the pure JVM (no AndroidKeyStore/Context): the sealed
 * [DekReadResult] distinction (NoBlob vs KeyLost vs AuthRequired), that
 * [SecurityService.getOrCreateDek] THROWS [KeystoreKeyLostException] instead of
 * re-keying over a stored-but-undecryptable blob, that the store keeps the
 * non-secret wrapper-alias marker, and the source-level wiring pins (passwordless
 * init, setMasterPassword, dbGate, MainActivity recovery screen).
 */
class B1Crypto05SilentRekeyTest {

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

    /** A stored NON-auth device copy whose wrapping keystore key is gone. */
    private fun lostKeyBlob(): DekDeviceBlob =
        DekDeviceBlob(
            encoded = "YQ==", // decodes to 1 byte — far short of a 12-byte IV, unreadable
            authRequired = false,
            wrapperAlias = "noteflow_dek_key",
            wrapperVersion = 1
        )

    // ---------- behavior: the sealed read distinction ----------

    @Test
    fun `missing blob reports NoBlob - distinct from stored-but-unreadable`() {
        val security = SecurityService(FakeDekDeviceStore())
        val result = security.readDekResult()
        assertTrue("no stored blob must be NoBlob, never KeyLost", result is DekReadResult.NoBlob)
        assertNull("legacy readDek still fails closed on NoBlob", security.readDek())
    }

    @Test
    fun `stored-but-undecryptable blob reports KeyLost with the wrapper alias marker`() {
        val store = FakeDekDeviceStore()
        store.blob = lostKeyBlob()
        val security = SecurityService(store)

        val result = security.readDekResult()
        assertTrue("a present-but-unreadable blob must be KeyLost", result is DekReadResult.KeyLost)
        assertEquals(
            "the non-secret wrapper-alias marker must survive into the KeyLost result",
            "noteflow_dek_key",
            (result as DekReadResult.KeyLost).wrapperAlias
        )
        assertNull("legacy readDek flattens KeyLost to null (never the vault DEK)", security.readDek())
    }

    @Test
    fun `missing vs corrupt blob are reported differently`() {
        val empty = SecurityService(FakeDekDeviceStore())
        val store = FakeDekDeviceStore()
        store.blob = lostKeyBlob()
        val corrupt = SecurityService(store)

        assertTrue("missing ⇒ NoBlob", empty.readDekResult() is DekReadResult.NoBlob)
        assertTrue("stored-but-unreadable ⇒ KeyLost", corrupt.readDekResult() is DekReadResult.KeyLost)
    }

    @Test
    fun `auth-gated blob stays AuthRequired and is never read or minted over`() {
        val store = FakeDekDeviceStore()
        store.blob = DekDeviceBlob("YXV0aC1yZXF1aXJlZC1ibG9i", authRequired = true)
        val security = SecurityService(store)

        assertTrue("auth-gated blob must be AuthRequired, not KeyLost", security.readDekResult() is DekReadResult.AuthRequired)
        assertNull("getOrCreateDek must not mint over an auth-gated blob", security.getOrCreateDek())
        assertEquals("no write may be emitted for an auth-gated blob", 0, store.writes)
    }

    // ---------- behavior: getOrCreateDek NEVER re-keys over a lost key ----------

    @Test
    fun `getOrCreateDek throws instead of silently re-keying over an undecryptable blob`() {
        val store = FakeDekDeviceStore()
        store.blob = lostKeyBlob()
        val security = SecurityService(store)

        val ex = assertThrows(KeystoreKeyLostException::class.java) {
            security.getOrCreateDek()
        }
        assertEquals("the marker alias must ride the exception for diagnostics", "noteflow_dek_key", ex.wrapperAlias)
        assertEquals(
            "the stored blob must NEVER be overwritten on key loss",
            lostKeyBlob().encoded,
            store.blob?.encoded
        )
        assertEquals("no re-key write may be emitted on key loss", 0, store.writes)
    }

    @Test
    fun `getOrCreateDek with minting disabled also refuses KeyLost - never falls through to null`() {
        // Returning null here would let the DB factory's locked-open guard treat the
        // vault as merely "locked" and a later caller mint somewhere else; on key
        // loss the ONLY correct behavior is the typed exception → recovery screen.
        val store = FakeDekDeviceStore()
        store.blob = lostKeyBlob()
        val security = SecurityService(store)

        assertThrows(KeystoreKeyLostException::class.java) {
            security.getOrCreateDek(allowPasswordlessMint = false)
        }
        assertEquals("no re-key write may be emitted on key loss", 0, store.writes)
        assertNotNull("the lost wrapper must survive untouched", store.blob)
    }

    @Test
    fun `true first run (no blob) may still mint a passwordless DEK`() {
        val security = SecurityService(FakeDekDeviceStore())
        assertNotNull("NoBlob + allowPasswordlessMint must mint", security.getOrCreateDek(allowPasswordlessMint = true))
        val locked = SecurityService(FakeDekDeviceStore())
        assertNull("NoBlob + mint disabled must fail closed", locked.getOrCreateDek(allowPasswordlessMint = false))
    }

    // ---------- behavior: the non-secret wrapper-alias marker ----------

    @Test
    fun `store keeps the wrapper alias marker so key loss is distinguishable from no blob`() {
        val store = FakeDekDeviceStore()
        store.blob = DekDeviceBlob(
            encoded = "b2xkLXVucmVhZGFibGUtYmxvYg",
            authRequired = false,
            wrapperAlias = "noteflow_dek_key",
            wrapperVersion = 1
        )
        val security = SecurityService(store)
        val lost = security.readDekResult() as DekReadResult.KeyLost
        assertEquals("the persisted marker must identify the lost wrapper alias", "noteflow_dek_key", lost.wrapperAlias)
    }

    // ---------- wiring pins (source-level, like B1Crypto02DekAtRestTest) ----------

    @Test
    fun `passwordless init routes through readDekResult - no silent mint on a stored blob`() {
        val source = readNoteflowViewModelSource()
        val initBlock = source.substringAfter("init {", "END")
            .substringBefore("fun setThemeMode", "END")
        assertTrue(
            "passwordless boot must distinguish NoBlob/Unlocked/KeyLost via readDekResult",
            initBlock.contains("security.readDekResult()")
        )
        assertTrue(
            "a lost device key must surface the keystore-key-lost recovery, not mint",
            initBlock.contains("_keystoreKeyLost.value = keystoreLostBlockedForCurrentEvent")
        )
        assertTrue(
            "no detection site may set the key-lost state unconditionally (phase-163 keyed gate)",
            !initBlock.contains("_keystoreKeyLost.value = true")
        )
        assertTrue(
            "the pre-fix silent mint (`var dek = security.readDek(); if (dek == null) generateDek()`) must be gone",
            !initBlock.contains("var dek = security.readDek()")
        )
    }

    @Test
    fun `setMasterPassword refuses a lost device key instead of minting over it`() {
        val source = readNoteflowViewModelSource()
        val setBlock = source.substringAfter("suspend fun setMasterPassword", "END")
            .substringBefore("suspend fun changeMasterPassword", "END")
        assertTrue(
            "setMasterPassword must route the device-copy read through readDekResult",
            setBlock.contains("security.readDekResult()")
        )
        assertTrue(
            "setMasterPassword must throw KeystoreKeyLostException on KeyLost (never mint)",
            setBlock.contains("KeystoreKeyLostException")
        )
    }

    @Test
    fun `dbGate gates on the keystore-key-lost flag so no open races the recovery screen`() {
        val source = readNoteflowViewModelSource()
        val gateBlock = source.substringAfter("private val dbGate", "END")
        assertTrue("dbGate must include the keystore-key-lost flag", gateBlock.contains("_keystoreKeyLost"))
        assertTrue("dbGate must still include the corruption flag", gateBlock.contains("_corruptionBlocked"))
    }

    @Test
    fun `viewmodel exposes the key-lost state and both recovery exits`() {
        val source = readNoteflowViewModelSource()
        assertTrue("keystoreKeyLost StateFlow must be exposed", source.contains("val keystoreKeyLost: StateFlow<Boolean>"))
        assertTrue(
            "restore-from-backup recovery path must exist",
            source.contains("fun attemptKeystoreKeyLostRecoveryFromBackup")
        )
        assertTrue(
            "explicit start-fresh recovery path must exist",
            source.contains("fun startFreshAfterKeystoreKeyLoss()")
        )
    }

    @Test
    fun `main activity routes the key-lost state to the dedicated recovery screen`() {
        val source = readMainActivitySource()
        assertTrue(
            "MainActivity must collect the keystore-key-lost state",
            source.contains("viewModel.keystoreKeyLost.collectAsState()")
        )
        assertTrue(
            "MainActivity must render KeystoreKeyLostScreen when the state is set",
            source.contains("KeystoreKeyLostScreen(viewModel = viewModel)")
        )
    }

    @Test
    fun `storeDek stamps the non-secret wrapper alias and version markers`() {
        val source = readSecurityServiceSource()
        assertTrue(
            "storeDek must persist the wrapper alias marker",
            source.substringAfter("fun storeDek", "END").contains("wrapperAlias = alias")
        )
        assertTrue(
            "storeDek must persist the wrapper version marker",
            source.substringAfter("fun storeDek", "END").contains("WRAPPER_VERSION")
        )
    }

    @Test
    fun `SharedPrefsDekDeviceStore persists and clears the wrapper marker keys`() {
        val source = readSecurityServiceSource()
        val storeBlock = source.substringAfter("class SharedPrefsDekDeviceStore", "END")
        assertTrue("write() must persist KEY_WRAPPER_ALIAS", storeBlock.contains("putString(KEY_WRAPPER_ALIAS"))
        assertTrue("write() must persist KEY_WRAPPER_VERSION", storeBlock.contains("putInt(KEY_WRAPPER_VERSION"))
        assertTrue("clear() must drop KEY_WRAPPER_ALIAS", storeBlock.contains("remove(KEY_WRAPPER_ALIAS)"))
        assertTrue("clear() must drop KEY_WRAPPER_VERSION", storeBlock.contains("remove(KEY_WRAPPER_VERSION)"))
    }

    @Test
    fun `getOrCreateDek throws on KeyLost - the mint path can never run over a stored blob`() {
        val source = readSecurityServiceSource()
        val createBlock = source.substringAfter("fun getOrCreateDek", "END")
        assertTrue(
            "the KeyLost branch must throw instead of minting",
            createBlock.contains("is DekReadResult.KeyLost -> throw KeystoreKeyLostException")
        )
        assertTrue(
            "the mint must only be reachable from NoBlob",
            createBlock.contains("DekReadResult.NoBlob -> {")
        )
    }

    // ---------- helpers ----------

    private fun readSecurityServiceSource(): String {
        val file = java.io.File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/services/SecurityService.kt"
        )
        assertTrue("SecurityService.kt must exist", file.isFile)
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

    private fun readMainActivitySource(): String {
        val file = java.io.File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt"
        )
        assertTrue("MainActivity.kt must exist", file.isFile)
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
