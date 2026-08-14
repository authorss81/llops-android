package com.authorss81.noteflow

import com.authorss81.noteflow.services.EncryptionService
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Phase 105 (B2-CRYPTO-05): `EncryptionService.decrypt` format selection is
 * DETERMINISTIC — no version-byte guessing, no legacy fallback retry.
 *
 * Before this fix the decrypt path first tried the versioned layout and, on any
 * AEADBadTagException, re-ran a SECOND full GCM decrypt against a guessed
 * unversioned no-AAD layout. That made the number of GCM operations reveal the
 * payload class (versioned = 2, legacy = 1) and gave every bad tag one
 * malleable retry. The old fallback existed only to read "legacy" payloads that
 * this app has never written (every writer in this repo has always produced
 * `[PAYLOAD_VERSION][12-byte IV][ciphertext+tag]`; see git history d31c23d
 * onward), so legacy support is dropped and decrypt now fails closed.
 *
 * A tampered payload fails exactly once (the single versioned path), and any
 * payload that does not begin with the version marker is rejected without ever
 * attacking a second layout. `decryptAad` is out of scope here: its FIELD_AAD
 * retry re-uses the SAME layout and only rescues pre-B2-CRYPTO-03 backups.
 */
class EncryptionServiceDecryptFormatTest {

    private val dek = ByteArray(32) { (0x20 + it).toByte() }

    private fun b64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    private fun rawOf(encryptedBase64: String): ByteArray =
        java.util.Base64.getDecoder().decode(encryptedBase64)

    /** Builds the pre-versioning layout: `[12-byte IV][GCM ciphertext+tag]`, no AAD, no version byte. */
    private fun buildLegacyPayload(iv: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(128, iv))
        val cipherText = cipher.doFinal(plaintext)
        return ByteArray(iv.size + cipherText.size).also { out ->
            System.arraycopy(iv, 0, out, 0, iv.size)
            System.arraycopy(cipherText, 0, out, iv.size, cipherText.size)
        }
    }

    @Test
    fun `correct versioned payload round-trips through the single chosen path`() {
        val plain = "Confidential note body".toByteArray(Charsets.UTF_8)
        val encrypted = EncryptionService.encrypt(plain, dek)
        assertArrayEquals(plain, EncryptionService.decrypt(encrypted, dek))
    }

    @Test
    fun `tampered ciphertext fails exactly once with the tag exception - no fallback retry`() {
        val encrypted = EncryptionService.encrypt("sensitive".toByteArray(Charsets.UTF_8), dek)
        val raw = rawOf(encrypted)
        // Versioned layout is [1][iv(12)][ct+tag]; flip the first ciphertext byte.
        raw[13] = (raw[13].toInt() xor 0x55).toByte()
        val tampered = b64(raw)
        // One attempt: the AEADBadTagException* propagates directly — the OLD code
        // would have swallowed it here and run a second (legacy) decrypt.
        assertThrows(AEADBadTagException::class.java) {
            EncryptionService.decrypt(tampered, dek)
        }
        // Graceful public surface: decryptOrNull swallows the failure, no crash.
        assertNull(EncryptionService.decryptOrNull(tampered, dek))
    }

    @Test
    fun `tampered IV also fails with the tag exception - AAD and IV are authenticated`() {
        val encrypted = EncryptionService.encrypt("sensitive".toByteArray(Charsets.UTF_8), dek)
        val raw = rawOf(encrypted)
        raw[1] = (raw[1].toInt() xor 0x01).toByte() // first IV byte
        assertThrows(AEADBadTagException::class.java) {
            EncryptionService.decrypt(b64(raw), dek)
        }
    }

    @Test
    fun `flipping the version marker to a non-1 value is rejected - never re-guessed as legacy`() {
        val encrypted = EncryptionService.encrypt("sensitive".toByteArray(Charsets.UTF_8), dek)
        val raw = rawOf(encrypted)
        raw[0] = 0x02
        // The old code treated this as legacy and attempted a shifted no-AAD
        // decrypt. Now it fails closed with a format error before any decrypt.
        assertThrows(IllegalArgumentException::class.java) {
            EncryptionService.decrypt(b64(raw), dek)
        }
    }

    @Test
    fun `legacy unversioned payload is rejected - legacy support dropped, fail closed`() {
        val iv = ByteArray(12) { 7 } // first byte 0x07 != PAYLOAD_VERSION
        val legacy = buildLegacyPayload(iv, "legacy note".toByteArray(Charsets.UTF_8))
        assertThrows(IllegalArgumentException::class.java) {
            EncryptionService.decrypt(b64(legacy), dek)
        }
        assertNull(EncryptionService.decryptOrNull(b64(legacy), dek))
    }

    @Test
    fun `legacy payload whose IV happens to start with the version byte is deterministically rejected`() {
        // The exact collision the old fallback existed for (1-in-256). The
        // payload is NOT versioned, so it must fail closed -- whether the marker
        // check rejects it (IllegalArgumentException) or the versioned path runs
        // and the GCM tag fails (AEADBadTagException) -- and it must NEVER be
        // silently decrypted as legacy.
        val iv = ByteArray(12) { 9 }
        iv[0] = 1 // IV's first byte collides with PAYLOAD_VERSION
        val legacy = buildLegacyPayload(iv, "legacy note".toByteArray(Charsets.UTF_8))
        assertThrows(Exception::class.java) {
            EncryptionService.decrypt(b64(legacy), dek)
        }
        assertNull(EncryptionService.decryptOrNull(b64(legacy), dek))
    }

    @Test
    fun `overshort payload is rejected before any decrypt attempt`() {
        val tooShort = b64(ByteArray(12) { 1 }) // needs >= 13 bytes to hold marker + IV
        assertThrows(IllegalArgumentException::class.java) {
            EncryptionService.decrypt(tooShort, dek)
        }
        assertNull(EncryptionService.decryptOrNull(tooShort, dek))
    }
}