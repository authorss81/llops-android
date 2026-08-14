package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.runtime.PinnedCertHash
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Phase 103 (B2-CRYPTO-02): regression guard for the single constant-time
 * comparison helper policy. Certificate pins MUST compare through the app-wide
 * [com.authorss81.noteflow.utils.ConstantTime] helper (delegating to
 * `MessageDigest.isEqual`), never `String.equals` — and pin parsing must keep
 * rejecting digests that are not exactly 32 bytes.
 *
 * The base64 pin alphabet is plain ASCII, so the helper's US-ASCII byte
 * encoding of the compared values is byte-identical to the (previously
 * local) UTF-8 path — behavior is preserved exactly.
 */
class PinnedCertHashTest {

    private fun digest(firstByte: Int): String =
        Base64.getEncoder().encodeToString(ByteArray(32) { (it + firstByte).toByte() })

    @Test
    fun `equal 32-byte base64 hash matches with or without the sha256 prefix`() {
        val hash = digest(0)
        assertTrue(PinnedCertHash.matchesBase64(hash, hash))
        assertTrue(PinnedCertHash.matchesBase64(hash, "sha256/$hash"))
    }

    @Test
    fun `a differing base64 hash never matches`() {
        val a = digest(0)
        val b = digest(1) // differs in every byte
        assertFalse(PinnedCertHash.matchesBase64(a, b))
        assertFalse(PinnedCertHash.matchesBase64(a, "sha256/$b"))
    }

    @Test
    fun `parse rejects digests that are not exactly 32 bytes`() {
        assertNull(PinnedCertHash.parse("sha256/AAAA")) // 3 bytes
        assertNull(
            PinnedCertHash.parse("sha256/" + Base64.getEncoder().encodeToString(ByteArray(16) { 0x00 }))
        )
        assertNull(
            PinnedCertHash.parse("sha256/" + Base64.getEncoder().encodeToString(ByteArray(33) { 0x00 }))
        )
    }

    @Test
    fun `parse accepts a pinned 32-byte digest`() {
        val parsed = PinnedCertHash.parse("sha256/${digest(0)}")
        assertTrue(parsed != null && parsed.size == 32)
    }
}
