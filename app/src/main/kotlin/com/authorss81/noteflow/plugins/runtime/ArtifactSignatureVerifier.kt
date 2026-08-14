package com.authorss81.noteflow.plugins.runtime

import com.authorss81.noteflow.utils.ConstantTime
import java.io.File
import java.io.OutputStream
import java.security.cert.X509Certificate
import java.util.jar.JarFile

/**
 * THE security-critical signature-verification gate of the downloadable-plugin
 * runtime (Phase 23, see `docs/plugin-architecture.md` § Security model).
 *
 * A downloaded plugin artifact is verified BEFORE any code is loaded, and
 * RE-verified on EVERY load. Verification is two independent, both-mandatory
 * checks:
 *
 * 1. **SHA-256 of the artifact bytes** must equal the compile-time
 *    [PluginEntry.sha256] — a single flipped byte (corruption or tampering)
 *    fails this check.
 * 2. **The artifact's signing certificate** (extracted from its JAR signature
 *    block) must hash to the compile-time [PluginEntry.pinnedCertHash]. A
 *    genuine artifact signed by a different key is rejected here even when its
 *    bytes were untouched.
 *
 * Any failure is a hard [Result.Invalid] with a user-facing reason — a
 * tampered artifact is NEVER loaded, never partially executed. Signature
 * verification uses the JDK's own `java.util.jar.JarFile` verifier (the same
 * machinery `jarsigner` produces and verifies), so no bespoke crypto is
 * involved. Pure JVM — fully unit-testable.
 */
class ArtifactSignatureVerifier {

    /** Outcome of verifying an artifact. */
    sealed class Result {
        /** Integrity proven: [sha256Hex] matches and the signer cert hashes to the pin. */
        data class Verified(
            val sha256Hex: String,
            val signingCertHash: String
        ) : Result()

        /** Verification failed; [reason] is user-facing. Never loads the artifact. */
        data class Invalid(val reason: String) : Result()
    }

    /**
     * Verify [file] against [expectedSha256] and [expectedPinnedCertHash].
     *
     * Order matters: the cheap byte hash runs first; only if it matches do we
     * open the JAR and force full signature verification (which involves
     * parsing the signing block). Never throws.
     */
    fun verify(file: File, expectedSha256: String, expectedPinnedCertHash: String): Result {
        if (!file.isFile) {
            return Result.Invalid("the downloaded artifact file was not found.")
        }
        val sha256 = PluginDigest.sha256Hex(file)
            ?: return Result.Invalid("the downloaded artifact could not be read.")
        // B2-CRYPTO-02 (CWE-650): digests MUST be compared via the shared
        // constant-time helper (ConstantTime.hexEqual → MessageDigest.isEqual),
        // never String.equals, which exits on the first mismatching nibble.
        // Case is normalized once here at the expected-digest parse boundary —
        // PluginDigest.sha256Hex always emits lowercase — so ignoreCase is
        // neither needed nor allowed at compare time.
        val expected = expectedSha256.trim().lowercase()
        if (!ConstantTime.hexEqual(sha256, expected)) {
            return Result.Invalid(
                "SHA-256 mismatch — the artifact is corrupted or has been tampered with " +
                    "(expected $expected, got $sha256). It will not be loaded."
            )
        }
        val signerCert = findSignerCertificate(file)
            ?: return Result.Invalid(
                "the artifact is not signed (no verifiable signer certificate was found). " +
                    "It will not be loaded."
            )
        val actualPin = PinnedCertHash.base64Sha256(signerCert)
        if (!PinnedCertHash.matches(signerCert, expectedPinnedCertHash)) {
            return Result.Invalid(
                "the artifact's signing certificate does not match the pinned certificate hash " +
                    "(pinned $expectedPinnedCertHash, actual $actualPin). " +
                    "It will not be loaded."
            )
        }
        return Result.Verified(sha256, actualPin)
    }

    /**
     * Force full JAR signature verification and return the signer certificate.
     *
     * Opening with `verify = true` makes [JarFile] validate the manifest and
     * the `META-INF` `.SF`/`.RSA` signature block; to trigger that validation
     * every non-`META-INF` entry must be fully read (a tampered signature
     * throws [SecurityException] mid-read). Returns the certificate that
     * signed the entries, or null when unsigned / unverifiable / not an
     * archive. Never throws.
     */
    private fun findSignerCertificate(file: File): X509Certificate? = try {
        JarFile(file, true).use { jar ->
            var signer: X509Certificate? = null
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || entry.name.startsWith("META-INF/")) continue
                jar.getInputStream(entry).use { stream ->
                    stream.copyTo(OutputStream.nullOutputStream())
                }
                val certs = entry.certificates
                if (certs != null && certs.isNotEmpty()) {
                    signer = certs.firstOrNull() as? X509Certificate ?: continue
                }
            }
            signer
        }
    } catch (_: SecurityException) {
        // The JAR verifier rejected the signature / entry digests (tampered).
        null
    } catch (_: Throwable) {
        null
    }
}
