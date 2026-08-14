package com.authorss81.noteflow

import com.authorss81.noteflow.services.EncryptionService
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 108 (B2-CRYPTO-10): blank/empty plaintext fields must be stored as REAL
 * AEAD payloads, never raw and untagged, and the "is this field encrypted?"
 * classifier must be decided by payload structure — never by content blank-ness.
 *
 * Before this phase:
 *  - `createPage`/`saveStrokesForPage`/`saveMediaEmbedsForPage`/`createNoteVersion`
 *    wrote a blank value as the literal `""` (no GCM tag), so an attacker who
 *    zeroed a ciphertext column produced a value indistinguishable from a
 *    legitimately-blank record;
 *  - `isFieldEncrypted("")` returned true ("nothing to encrypt"), so
 *    `reencryptPlaintextFields` never stamped blank columns and any integrity
 *    logic consulting it asserted an emptied field was "encrypted and fine".
 *
 * AES-GCM of the empty plaintext is a valid 29-byte payload
 * (`[version 1][12-byte IV][16-byte tag]`), so blanks now round-trip as
 * encrypted blobs that fail authentically when the column is zeroed. The
 * re-encryption sweep's gate is `EncryptionService.isFieldEncrypted` — a raw
 * blank now returns false, so the sweep re-stamps it.
 */
class BlankFieldEncryptionTest {

    private val dek = ByteArray(32) { (0x40 + it).toByte() }

    private fun b64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    private fun rawOf(encryptedBase64: String): ByteArray =
        java.util.Base64.getDecoder().decode(encryptedBase64)

    @Test
    fun `empty plaintext round-trips as a real AEAD payload and decrypts back to empty`() {
        val encrypted = EncryptionService.encryptField(ByteArray(0), dek, "pages", "P1", "extractedText")
        assertTrue(EncryptionService.isEncryptedPayload(encrypted))
        // Version byte (1) + 12-byte IV + GCM tag (16) = 29 bytes minimum.
        assertTrue(rawOf(encrypted).size >= 29)
        assertArrayEquals(ByteArray(0), EncryptionService.decryptField(encrypted, dek, "pages", "P1", "extractedText"))
        assertEquals("", EncryptionService.decryptFieldOrNull(encrypted, dek, "pages", "P1", "extractedText"))
    }

    @Test
    fun `zeroing the ciphertext column fails decryption and is not classified as an encrypted payload`() {
        val encrypted = EncryptionService.encryptField(ByteArray(0), dek, "pages", "P1", "extractedText")
        // An attacker zeroing the stored column leaves "".
        assertFalse(EncryptionService.isEncryptedPayload(""))
        assertFalse(EncryptionService.isFieldEncrypted("", dek, "pages", "P1", "extractedText"))
        assertNull(EncryptionService.decryptFieldOrNull("", dek, "pages", "P1", "extractedText"))
        assertThrows(IllegalArgumentException::class.java) {
            EncryptionService.decryptField("", dek, "pages", "P1", "extractedText")
        }
        // While the legitimately-stored blank still decrypts — the two are no
        // longer indistinguishable at the field layer.
        assertEquals("", EncryptionService.decryptFieldOrNull(encrypted, dek, "pages", "P1", "extractedText"))
    }

    @Test
    fun `classifier never uses content blank-ness`() {
        val encryptedBlank = EncryptionService.encryptField(ByteArray(0), dek, "strokes", "S1", "textContent")
        // A raw blank is NOT an encrypted payload -> the re-encryption sweep
        // must re-stamp it (old behavior: returns true and never touches it).
        assertFalse(EncryptionService.isFieldEncrypted("", dek, "strokes", "S1", "textContent"))
        // A correctly-stored blank IS one.
        assertTrue(EncryptionService.isFieldEncrypted(encryptedBlank, dek, "strokes", "S1", "textContent"))
        // A long non-ciphertext value is NOT one either (never blank-ness driven).
        assertFalse(
            EncryptionService.isFieldEncrypted(
                "This is a long legacy plaintext value that is not any kind of ciphertext payload whatsoever",
                dek, "pages", "P2", "extractedText"
            )
        )
    }

    @Test
    fun `isEncryptedPayload is a purely structural check`() {
        // Blank -> not a payload.
        assertFalse(EncryptionService.isEncryptedPayload(""))
        assertFalse(EncryptionService.isEncryptedPayload("   "))
        // Too short to hold marker + IV (< 13 decoded bytes).
        assertFalse(EncryptionService.isEncryptedPayload(b64(ByteArray(12) { 1 })))
        // Versioned shapes with a wrong version marker -> not a payload.
        assertFalse(EncryptionService.isEncryptedPayload(b64(ByteArray(20) { 0x02 })))
        assertFalse(EncryptionService.isEncryptedPayload(b64(ByteArray(20) { 0x00 })))
        // Not base64 at all -> not a payload (and must not throw).
        assertFalse(EncryptionService.isEncryptedPayload("!!!not base64!!!"))
        // A genuine versioned payload -> payload.
        val genuine = EncryptionService.encrypt("x".toByteArray(Charsets.UTF_8), dek)
        assertTrue(EncryptionService.isEncryptedPayload(genuine))
        // The empty-plaintext payload also carries the version marker.
        val blank = EncryptionService.encryptField(ByteArray(0), dek, "media_embeds", "M1", "textContent")
        assertTrue(EncryptionService.isEncryptedPayload(blank))
        assertEquals(1, rawOf(blank)[0].toInt())
    }

    @Test
    fun `blank fields round-trip and stay tagged across every field table`() {
        val contexts = listOf(
            Triple("pages", "P", "title"),
            Triple("pages", "P", "extractedText"),
            Triple("strokes", "S", "textContent"),
            Triple("strokes", "S", "pointsJson"),
            Triple("media_embeds", "M", "textContent"),
            Triple("note_versions", "V", "title"),
            Triple("note_versions", "V", "extractedText")
        )
        for ((table, id, field) in contexts) {
            val encrypted = EncryptionService.encryptField(ByteArray(0), dek, table, id, field)
            assertTrue("$table.$field blank must classify as encrypted", EncryptionService.isEncryptedPayload(encrypted))
            assertArrayEquals(
                "$table.$field blank must decrypt to empty",
                ByteArray(0),
                EncryptionService.decryptField(encrypted, dek, table, id, field)
            )
            // A zeroed column of this field is never "encrypted and fine".
            assertFalse(
                "$table.$field raw blank must classify as not encrypted",
                EncryptionService.isFieldEncrypted("", dek, table, id, field)
            )
        }
    }

    @Test
    fun `a stored blank is recognised as already-encrypted so the sweep does not double-encrypt`() {
        val encrypted = EncryptionService.encryptField(ByteArray(0), dek, "note_versions", "V9", "extractedText")
        // Once stamped, re-running the sweep's gate skips the row (true), so the
        // blank is not rewritten on every unlock.
        assertTrue(EncryptionService.isFieldEncrypted(encrypted, dek, "note_versions", "V9", "extractedText"))
        // It stays decryptable across re-keys (cross-device restore path calls
        // decryptField with the backup DEK, then re-encryptField under the new one).
        val reKeyed = EncryptionService.encryptField(
            EncryptionService.decryptField(encrypted, dek, "note_versions", "V9", "extractedText"),
            ByteArray(32) { (it + 1).toByte() },
            "note_versions", "V9", "extractedText"
        )
        assertArrayEquals(ByteArray(0), EncryptionService.decryptField(reKeyed, ByteArray(32) { (it + 1).toByte() }, "note_versions", "V9", "extractedText"))
    }

    @Test
    fun `blank field is bound to its record context like any other payload`() {
        val encrypted = EncryptionService.encryptField(ByteArray(0), dek, "pages", "REC_A", "extractedText")
        assertTrue(EncryptionService.isFieldBoundToRecord(encrypted, dek, "pages", "REC_A", "extractedText"))
        assertFalse(EncryptionService.isFieldBoundToRecord(encrypted, dek, "pages", "REC_B", "extractedText"))
        // A legacy raw blank carries no AAD binding and no tag — not a payload.
        assertFalse(EncryptionService.isFieldBoundToRecord("", dek, "pages", "REC_A", "extractedText"))
    }
}