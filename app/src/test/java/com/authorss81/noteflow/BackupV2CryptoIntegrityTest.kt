package com.authorss81.noteflow

import com.authorss81.noteflow.services.EncryptionService
import com.authorss81.noteflow.services.ImportExportService
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.BeforeClass
import org.junit.Test
import javax.crypto.AEADBadTagException

/**
 * Phase 104 (B2-CRYPTO-03): backup-v2 payload/DEK GCM binding.
 *
 * Before this fix the v2 zip payload was encrypted under the KEK with a bare
 * `cipher.init(...)` + `doFinal` and NO `updateAAD`, so nothing authenticated
 * the `magic|salt|payloadIv|wrappedDek` header that precedes it (a crafted file
 * could splice the header of one legitimate export onto the payload of
 * another), and the two KEK uses (wrap DEK vs encrypt payload) shared a key
 * with zero domain separation when a salt+IV pair was reused.
 *
 * These tests pin the fixed contract: a payload decrypts ONLY with its own
 * header (splicing fails the tag), and the DEK-wrap domain ('backup/dek-wrap')
 * and payload domain ('backup/payload' + header) can never decrypt each other's
 * ciphertexts. PBKDF2-600k runs once in [Companion.setUp].
 */
class BackupV2CryptoIntegrityTest {

    companion object {
        private const val PASSWORD = "BackupPassword123!"
        private val SALT = ByteArray(16) { it.toByte() }
        private lateinit var KEK: ByteArray
        private val DEK = ByteArray(32) { (0x10 + it).toByte() }

        @BeforeClass
        @JvmStatic
        fun setUp() {
            KEK = EncryptionService.deriveKey(PASSWORD, SALT)
        }
    }

    @Test
    fun `splicing another export's header onto a payload fails the GCM tag`() {
        val ivA = ByteArray(12) { 1 }
        val ivB = ByteArray(12) { 2 }
        val wrapA = EncryptionService.encryptAad(DEK, KEK, ImportExportService.BACKUP_DEK_WRAP_AAD)
        val wrapB = EncryptionService.encryptAad(DEK, KEK, ImportExportService.BACKUP_DEK_WRAP_AAD)
        val headerA = ImportExportService.buildBackupHeader(SALT, ivA, wrapA)
        val headerB = ImportExportService.buildBackupHeader(SALT, ivB, wrapB)

        val payloadBytesA = "legitimate export A notes".toByteArray(Charsets.UTF_8)
        val payloadBytesB = "legitimate export B notes".toByteArray(Charsets.UTF_8)
        val payloadA = ImportExportService.encryptBackupPayload(payloadBytesA, KEK, ivA, headerA)
        val payloadB = ImportExportService.encryptBackupPayload(payloadBytesB, KEK, ivB, headerB)

        // Positive controls: each payload decrypts with its OWN header/IV.
        assertArrayEquals(payloadBytesA, ImportExportService.decryptBackupPayload(payloadA, KEK, ivA, headerA))
        assertArrayEquals(payloadBytesB, ImportExportService.decryptBackupPayload(payloadB, KEK, ivB, headerB))

        // Attack: server/side party serves export A's header with export B's body.
        // The restore derives kek from the SAME salt, so only the header-binding
        // AAD can reject it (a wrong-password false negative is exactly the
        // hygiene gap this phase closes).
        assertThrows(AEADBadTagException::class.java) {
            ImportExportService.decryptBackupPayload(payloadB, KEK, ivA, headerA)
        }
        assertThrows(AEADBadTagException::class.java) {
            ImportExportService.decryptBackupPayload(payloadA, KEK, ivB, headerB)
        }
    }

    @Test
    fun `DEK-wrap and payload KEK uses are distinct AAD domains`() {
        val wrap = EncryptionService.encryptAad(DEK, KEK, ImportExportService.BACKUP_DEK_WRAP_AAD)

        // Correct domain unwraps the DEK.
        assertArrayEquals(DEK, EncryptionService.decryptAad(wrap, KEK, ImportExportService.BACKUP_DEK_WRAP_AAD))

        // The PAYLOAD domain must never unwrap a DEK-wrap ciphertext.
        assertThrows(AEADBadTagException::class.java) {
            EncryptionService.decryptAad(wrap, KEK, ImportExportService.BACKUP_PAYLOAD_AAD)
        }

        // And a payload ciphertext must never be accepted as a wrapped DEK. It is
        // presented in the full [version][IV][ct+tag] wire format so decryptAad
        // deterministically takes its versioned branch instead of depending on
        // the first ciphertext byte coincidentally matching PAYLOAD_VERSION.
        val iv = ByteArray(12) { 3 }
        val header = ImportExportService.buildBackupHeader(SALT, iv, wrap)
        val payload = ImportExportService.encryptBackupPayload(
            "payload in vault zip".toByteArray(Charsets.UTF_8), KEK, iv, header
        )
        assertArrayEquals(
            "payload in vault zip".toByteArray(Charsets.UTF_8),
            ImportExportService.decryptBackupPayload(payload, KEK, iv, header)
        )
        val versionedPayload = ByteArray(1 + iv.size + payload.size).also { out ->
            out[0] = 1
            System.arraycopy(iv, 0, out, 1, iv.size)
            System.arraycopy(payload, 0, out, 1 + iv.size, payload.size)
        }
        assertThrows(AEADBadTagException::class.java) {
            EncryptionService.decryptAad(versionedPayload, KEK, ImportExportService.BACKUP_DEK_WRAP_AAD)
        }
        // Wrapped DEK fed into the payload decrypt path is rejected too.
        assertThrows(AEADBadTagException::class.java) {
            ImportExportService.decryptBackupPayload(wrap, KEK, iv, header)
        }
    }

    @Test
    fun `pre-fix backups still restore (wrapped-DEK FIELD_AAD and zero-AAD payload)`() {
        // Old wrapped DEK format = EncryptionService.encrypt output:
        // [version 1][12-byte IV][ciphertext+tag] authenticated under the app's
        // FIELD_AAD constant (EncryptionService.kt:21), which decryptAad must
        // still accept via its pre-B2-CRYPTO-03 fallback. Built manually here
        // because android.util.Base64 returns null under unit-test defaults.
        val fieldAad = "Noteflow-Vault-Field-Encryption-v1".toByteArray(Charsets.UTF_8)
        val wrapIv = ByteArray(12) { 5 }
        val wrapCipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        wrapCipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(KEK, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, wrapIv)
        )
        wrapCipher.updateAAD(fieldAad)
        val wrapCipherText = wrapCipher.doFinal(DEK)
        val oldStyleWrapped = ByteArray(1 + wrapIv.size + wrapCipherText.size).also { out ->
            out[0] = 1
            System.arraycopy(wrapIv, 0, out, 1, wrapIv.size)
            System.arraycopy(wrapCipherText, 0, out, 1 + wrapIv.size, wrapCipherText.size)
        }
        assertArrayEquals(
            DEK,
            EncryptionService.decryptAad(oldStyleWrapped, KEK, ImportExportService.BACKUP_DEK_WRAP_AAD)
        )

        // Old payload: raw GCM doFinal(zipData) with no AAD at all.
        val iv = ByteArray(12) { 4 }
        val header = ImportExportService.buildBackupHeader(SALT, iv, oldStyleWrapped)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(KEK, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, iv)
        )
        val legacyPayload = cipher.doFinal("legacy vault zip".toByteArray(Charsets.UTF_8))

        assertArrayEquals(
            "legacy vault zip".toByteArray(Charsets.UTF_8),
            ImportExportService.decryptBackupPayload(legacyPayload, KEK, iv, header)
        )
    }
}