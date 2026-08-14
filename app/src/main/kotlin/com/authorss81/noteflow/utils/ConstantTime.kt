package com.authorss81.noteflow.utils

import java.security.MessageDigest

/**
 * Constant-time comparison for security-critical ASCII checksums and pins
 * (HMAC-SHA256 tamper checksums, artifact SHA-256 digests, base64 certificate
 * fingerprints/pins).
 *
 * `String.equals` / `==` are banned for these comparisons (CWE-650): they exit
 * on the first mismatching character, so an attacker who flips one input and
 * observes caller latency can recover the secret one character at a time. The
 * compared values are fixed-length ASCII (64-char hex SHA-256 / HMAC values,
 * 44-char base64 pins), so [MessageDigest.isEqual] runs a full-length loop
 * over both byte arrays. This is the single point every digest/pin compare in
 * the app funnels through — including the downloadable-plugin runtime's
 * certificate pins (`plugins/runtime/PinnedCertHash.kt`).
 *
 * Pure JVM (`java.security.MessageDigest` is available on every Android API
 * level, including the app's floor of API 26), so no platform fallback is
 * needed and the helper is fully unit-testable without Android.
 */
object ConstantTime {

    /**
     * True when [a] and [b] are byte-for-byte equal, evaluated in constant
     * time relative to the position of the first difference. Inputs MUST be
     * ASCII — hex digests or base64 pins (callers normalize lowercasing /
     * trimming / separator stripping beforehand). Non-ASCII characters are
     * mapped to `?` by the US-ASCII encoder — callers must never pass
     * secret-bearing non-ASCII text.
     *
     * Constant-time equality is guaranteed for EQUAL-LENGTH inputs:
     * [MessageDigest.isEqual] returns false immediately when the lengths
     * differ, so the full-length loop only runs over same-size arrays. All
     * current call sites compare fixed-length values (64-char hex SHA-256 /
     * HMAC hashes, 44-char base64 pins); callers MUST keep the compared sides
     * of fixed equal length.
     */
    fun hexEqual(a: String, b: String): Boolean =
        MessageDigest.isEqual(
            a.toByteArray(Charsets.US_ASCII),
            b.toByteArray(Charsets.US_ASCII)
        )
}