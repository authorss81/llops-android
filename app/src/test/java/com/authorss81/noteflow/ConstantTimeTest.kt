package com.authorss81.noteflow

import com.authorss81.noteflow.utils.ConstantTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 102 (B2-CRYPTO-01): the constant-time comparison helper used for every
 * HMAC / SHA-256 / certificate-pin comparison in the app.
 *
 * `==` / `String.equals` are banned for these values (CWE-650) because the
 * early exit on the first mismatching nibble lets a timing observer recover
 * the stored secret. [ConstantTime.hexEqual] delegates to
 * `MessageDigest.isEqual` (the same primitive `PinnedCertHash` already used for
 * plugin pins), which runs a fixed full-length loop over both byte arrays.
 *
 * The constant-time property itself cannot be asserted reliably in a unit test
 * (any nanoTime measurement is flaky on shared CI runners), so the review-level
 * check here pins the OBSERVABLE contract: equality is decided only by the full
 * pair of byte arrays, never short-circuited on a prefix — a difference in the
 * FIRST byte, the MIDDLE byte or the LAST byte must all be detected, and a
 * length change must not be a shortcut for value equality.
 */
class ConstantTimeTest {

    private val hmac = "a1b2c3d4e5f60718293a4b5c6d7e8f901a2b3c4d5e6f708192a3b4c5d6e7f8090"
    private val other64 = "f1b2c3d4e5f60718293a4b5c6d7e8f901a2b3c4d5e6f708192a3b4c5d6e7f8090"

    @Test
    fun `identical fixed length hex compares equal`() {
        assertTrue(ConstantTime.hexEqual(hmac, hmac))
    }

    @Test
    fun `a difference in the very first byte is a mismatch`() {
        assertFalse(ConstantTime.hexEqual(hmac, other64))
    }

    @Test
    fun `a difference in the middle is a mismatch`() {
        val middle = hmac.substring(0, 30) + '7' + hmac.substring(31)
        assertFalse(ConstantTime.hexEqual(hmac, middle))
    }

    @Test
    fun `a difference in the very last byte is a mismatch`() {
        val last = hmac.substring(0, 63) + '1'
        assertFalse(ConstantTime.hexEqual(hmac, last))
    }

    @Test
    fun `different lengths never compare equal`() {
        assertFalse(ConstantTime.hexEqual(hmac, hmac.dropLast(1)))
        assertFalse(ConstantTime.hexEqual("", hmac))
        assertFalse(ConstantTime.hexEqual(hmac, ""))
    }

    @Test
    fun `both empty strings compare equal`() {
        assertTrue(ConstantTime.hexEqual("", ""))
    }

    @Test
    fun `comparison is byte-wise and case sensitive by design`() {
        // Callers normalize lowercasing BEFORE invoking hexEqual; the helper
        // itself must not case-fold (case is part of the compared digest bytes).
        assertFalse(ConstantTime.hexEqual(hmac, hmac.uppercase()))
    }

    @Test
    fun `callers may normalize whitespace and separators before comparing`() {
        // LocalSend/HMAC call sites trim/lowercase/strip separators up front;
        // the helper compares whatever normalized hex it receives byte-for-byte.
        assertTrue(ConstantTime.hexEqual("ab cd".replace(" ", ""), "abcd"))
    }
}