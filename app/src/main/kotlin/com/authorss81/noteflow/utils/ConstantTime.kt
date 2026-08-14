package com.authorss81.noteflow.utils

import java.security.MessageDigest

/**
 * Constant-time comparison for security-critical ASCII-hex values (HMAC-SHA256
 * tamper checksums, artifact SHA-256 digests, certificate fingerprints/pins).
 *
 * `String.equals` / `==` are banned for these comparisons (CWE-650): they exit
 * on the first mismatching character, so an attacker who flips one input and
 * observes caller latency can recover the secret one nibble at a time. Both
 * sides here are fixed-length hex (64 chars for HMAC-SHA256 / SHA-256), so
 * [MessageDigest.isEqual] runs a full-length loop over both byte arrays — the
 * same primitive the downloadable-plugin runtime already uses in
 * `PinnedCertHash` (`plugins/runtime/PinnedCertHash.kt`).
 *
 * Pure JVM (`java.security.MessageDigest` is available on every Android API
 * level, including the app's floor of API 26), so no platform fallback is
 * needed and the helper is fully unit-testable without Android.
 */
object ConstantTime {

    /**
     * True when [a] and [b] are byte-for-byte equal, evaluated in constant
     * time relative to the position of the first difference. Inputs MUST be
     * ASCII hex (callers normalize lowercasing / trimming / separator
     * stripping beforehand). Non-ASCII characters are mapped to `?` by the
     * US-ASCII encoder — callers must never pass secret-bearing non-ASCII
     * text; for the checksums/pins/digests this helper serves the inputs are
     * always hex.
     */
    fun hexEqual(a: String, b: String): Boolean =
        MessageDigest.isEqual(
            a.toByteArray(Charsets.US_ASCII),
            b.toByteArray(Charsets.US_ASCII)
        )
}