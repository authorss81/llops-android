package com.authorss81.noteflow

import com.authorss81.noteflow.services.EncryptionService
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Phase 107 (B2-CRYPTO-09): vault field ciphertexts are bound to their exact
 * record context via per-record AAD (`v2|<table>|<recordId>|<fieldName>`).
 *
 * Pre-phase-107, every field (pages.title/extractedText, strokes.textContent/
 * pointsJson, media_embeds.textContent, note_versions.title/extractedText) was
 * authenticated under the SAME global constant FIELD_AAD ("Noteflow-Vault-Field-
 * Encryption-v1"), so a ciphertext lifted from one record rendered verbatim in
 * any other — the record-transplant attack the finding describes.
 *
 * These tests prove: a ciphertext bound to record A fails to decrypt in record
 * B (same column) and in the same record under a different column; legacy
 * global-AAD rows still read via the migration fallback (and ONLY on a tag
 * mismatch of the versioned layout); fresh writes are bound via isFieldBoundToRecord.
 */
class FieldRecordAadTest {

    private val dek = ByteArray(32) { (0x30 + it).toByte() }

    private fun b64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    private fun rawOf(encryptedBase64: String): ByteArray =
        java.util.Base64.getDecoder().decode(encryptedBase64)

    private fun fieldAad(table: String, recordId: String, fieldName: String): ByteArray =
        ("Noteflow-Vault-Field-Encryption-v2|" + table + "|" + recordId + "|" + fieldName)
            .toByteArray(Charsets.UTF_8)

    /** Crafts a legacy pre-phase-107 payload: [1][12-byte IV][ct+tag] under the global v1 FIELD_AAD. */
    private fun buildLegacyFieldPayload(plaintext: ByteArray): String {
        val iv = ByteArray(12) { 3 }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD("Noteflow-Vault-Field-Encryption-v1".toByteArray(Charsets.UTF_8))
        val cipherText = cipher.doFinal(plaintext)
        val combined = ByteArray(1 + iv.size + cipherText.size)
        combined[0] = 1
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(cipherText, 0, combined, 1 + iv.size, cipherText.size)
        return b64(combined)
    }

    private fun assertFailsTransplant(encrypted: String) {
        assertThrows(AEADBadTagException::class.java) {
            EncryptionService.decryptField(encrypted, dek, "pages", "RECORD_B", "title")
        }
    }

    @Test
    fun `round trip - field bound to its record decrypts`() {
        val plain = "Record A title".toByteArray(Charsets.UTF_8)
        val encrypted = EncryptionService.encryptField(plain, dek, "pages", "RECORD_A", "title")
        assertArrayEquals(plain, EncryptionService.decryptField(encrypted, dek, "pages", "RECORD_A", "title"))
    }

    @Test
    fun `transplant to a different record id fails the GCM tag`() {
        val encrypted = EncryptionService.encryptField("Record A title".toByteArray(Charsets.UTF_8), dek, "pages", "RECORD_A", "title")
        assertFailsTransplant(encrypted)
    }

    @Test
    fun `transplant to a different column in the same record fails`() {
        val encrypted = EncryptionService.encryptField("Record A title".toByteArray(Charsets.UTF_8), dek, "pages", "RECORD_A", "title")
        assertThrows(AEADBadTagException::class.java) {
            EncryptionService.decryptField(encrypted, dek, "pages", "RECORD_A", "extractedText")
        }
        assertThrows(AEADBadTagException::class.java) {
            EncryptionService.decryptField(encrypted, dek, "strokes", "RECORD_A", "title")
        }
    }

    @Test
    fun `transplant to a different table with the same record id and column fails`() {
        val encrypted = EncryptionService.encryptField("note versions text".toByteArray(Charsets.UTF_8), dek, "note_versions", "R1", "title")
        assertThrows(AEADBadTagException::class.java) {
            EncryptionService.decryptField(encrypted, dek, "pages", "R1", "title")
        }
    }

    @Test
    fun `decryptFieldOrNull returns null on a transplanted ciphertext`() {
        val encrypted = EncryptionService.encryptField("Record A title".toByteArray(Charsets.UTF_8), dek, "pages", "RECORD_A", "title")
        assertNull(EncryptionService.decryptFieldOrNull(encrypted, dek, "pages", "RECORD_B", "title"))
        assertEquals("Record A title", EncryptionService.decryptFieldOrNull(encrypted, dek, "pages", "RECORD_A", "title"))
    }

    @Test
    fun `legacy global-AAD row still decrypts via the migration fallback in its own record`() {
        val legacy = buildLegacyFieldPayload("legacy title".toByteArray(Charsets.UTF_8))
        assertEquals(
            "legacy title",
            String(EncryptionService.decryptField(legacy, dek, "pages", "LEGACY_ID", "title"), Charsets.UTF_8)
        )
    }

    @Test
    fun `legacy row is flagged NOT record-bound so migration converts it`() {
        val legacy = buildLegacyFieldPayload("legacy title".toByteArray(Charsets.UTF_8))
        // A legacy payload has NO per-record binding — isFieldBoundToRecord (the
        // migration's detector) must return false in ANY record context so the
        // pass re-encrypts it. During the migration window a transplanted legacy
        // ciphertext is still readable via the v1 fallback; migration is what
        // closes that window (the pass runs on every unlock once per vault).
        assertFalse(EncryptionService.isFieldBoundToRecord(legacy, dek, "pages", "OTHER_ID", "title"))
    }

    @Test
    fun `legacy row reader detects it is NOT record-bound and IS decryptable`() {
        val legacy = buildLegacyFieldPayload("legacy title".toByteArray(Charsets.UTF_8))
        assertFalse(EncryptionService.isFieldBoundToRecord(legacy, dek, "pages", "LEGACY_ID", "title"))
        // The migration reader (decrypt with global FIELD_AAD) still reads it.
        assertEquals(
            "legacy title",
            String(EncryptionService.decrypt(legacy, dek), Charsets.UTF_8)
        )
    }

    @Test
    fun `fresh writes are detected as record-bound`() {
        val encrypted = EncryptionService.encryptField("fresh title".toByteArray(Charsets.UTF_8), dek, "pages", "R9", "title")
        assertTrue(EncryptionService.isFieldBoundToRecord(encrypted, dek, "pages", "R9", "title"))
        assertFalse(EncryptionService.isFieldBoundToRecord(encrypted, dek, "pages", "R8", "title"))
    }

    @Test
    fun `same fields across different records produce different ciphertext with distinct tags`() {
        val a = EncryptionService.encryptField("same value".toByteArray(Charsets.UTF_8), dek, "pages", "R_A", "title")
        val b = EncryptionService.encryptField("same value".toByteArray(Charsets.UTF_8), dek, "pages", "R_B", "title")
        assertThrows(AEADBadTagException::class.java) {
            EncryptionService.decryptField(a, dek, "pages", "R_B", "title")
        }
        assertThrows(AEADBadTagException::class.java) {
            EncryptionService.decryptField(b, dek, "pages", "R_A", "title")
        }
    }

    @Test
    fun `legacy fallback only fires on AEADBadTagException - malformed payloads fail closed`() {
        val malformed = b64(ByteArray(12) { 5 }) // < 13 bytes
        assertThrows(IllegalArgumentException::class.java) {
            EncryptionService.decryptField(malformed, dek, "pages", "R_A", "title")
        }
        val unversioned = b64(ByteArray(20) { 9 }) // first byte != 1
        assertThrows(IllegalArgumentException::class.java) {
            EncryptionService.decryptField(unversioned, dek, "pages", "R_A", "title")
        }
        assertNull(EncryptionService.decryptFieldOrNull(unversioned, dek, "pages", "R_A", "title"))
    }

    @Test
    fun `fieldAad is domain-separated and stable for a given context`() {
        assertArrayEquals(fieldAad("pages", "abc", "title"), EncryptionService.fieldAad("pages", "abc", "title"))
        assertFalse(EncryptionService.fieldAad("pages", "abc", "title").contentEquals(EncryptionService.fieldAad("pages", "abd", "title")))
        assertFalse(EncryptionService.fieldAad("pages", "abc", "title").contentEquals(EncryptionService.fieldAad("strokes", "abc", "title")))
    }
}