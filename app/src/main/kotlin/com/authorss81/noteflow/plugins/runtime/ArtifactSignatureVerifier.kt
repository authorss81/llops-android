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
 * The signer check binds the FULL archive signer set (B1-CRYPTO-08): every
 * non-`META-INF` entry must carry exactly the one pinned cert — an unsigned
 * entry, a multi-signer entry, or an archive mixing different certs is
 * rejected, never "passes if iteration happens to end on the genuine entry" —
 * and the pinned cert must be currently valid with digital-signature key
 * usage ([SignerCertificatePolicy]), so an expired/revoked pinned cert is
 * never silently accepted.
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
        // B1-AUTH-01 (phase-46): static security scan before the signer is even
        // parsed. An artifact whose bytecode MENTIONS app-private packages or
        // raw network primitives is refused here — before ANY class is created
        // or resolved. Every plugin-bytecode path funnels through verify():
        // install, every load re-verify, update and rollback.
        when (val scan = ArtifactStaticScan().scan(file)) {
            is ArtifactStaticScan.Result.Pass -> Unit
            is ArtifactStaticScan.Result.Rejected -> return Result.Invalid(
                "the artifact failed the plugin static security scan and will not be loaded: ${scan.reason}"
            )
        }
        when (val signer = collectSignerSet(file)) {
            is SignerSetResult.Rejected -> return Result.Invalid(signer.reason)
            is SignerSetResult.Unified -> {
                // B1-CRYPTO-08: a cert whose hash matches the pin is STILL not
                // usable as the archive signer unless it is currently valid and
                // permitted to sign. An expired / not-yet-valid / non-signing
                // cert must fail here, never be silently accepted.
                when (val certCheck = SignerCertificatePolicy.validate(signer.cert)) {
                    is SignerCertificatePolicy.Validation.Reject -> return Result.Invalid(
                        "the artifact's signing certificate is not valid: ${certCheck.reason}. " +
                            "It will not be loaded."
                    )
                    is SignerCertificatePolicy.Validation.Accept -> Unit
                }
                val actualPin = PinnedCertHash.base64Sha256(signer.cert)
                if (!PinnedCertHash.matches(signer.cert, expectedPinnedCertHash)) {
                    return Result.Invalid(
                        "the artifact's signing certificate does not match the pinned certificate hash " +
                            "(pinned $expectedPinnedCertHash, actual $actualPin). " +
                            "It will not be loaded."
                    )
                }
                return Result.Verified(sha256, actualPin)
            }
        }
    }

    /**
     * Outcome of forcing full JAR signature verification over every entry.
     *
     * Unlike the pre-fix `findSignerCertificate` (which took the cert of the
     * LAST signed entry and skipped everything else), this binds the WHOLE
     * archive's signer set:
     *
     * - every non-`META-INF` entry MUST carry certificates — an unsigned entry
     *   inside an otherwise-verified jar is a hard rejection, so no entry can
     *   hide behind a signed sibling (the "attacker key on `classes.dex`" case);
     * - every entry's FULL certificate set must be a single-element set — a
     *   multi-signer entry (two signature blocks covering the same entry) is a
     *   hard rejection;
     * - the distinct signer across all entries must be exactly ONE — an
     *   archive mixing different signing certs anywhere fails, never "passes if
     *   iteration happens to end on the genuine entry";
     * - an EMPTY verified signer set is a hard rejection, never a silent
     *   fallback to a last-seen value.
     */
    private sealed class SignerSetResult {
        /** Every non-`META-INF` entry is signed by exactly this one cert. */
        data class Unified(val cert: X509Certificate) : SignerSetResult()

        /** A multi-signer / unsigned-entry / mixed-signer / empty-set archive. */
        data class Rejected(val reason: String) : SignerSetResult()
    }

    /**
     * Force full JAR signature verification and bind the archive's signer set.
     *
     * Opening with `verify = true` makes [JarFile] validate the manifest and
     * the `META-INF` `.SF`/`.RSA` signature blocks; to trigger that validation
     * every non-`META-INF` entry must be fully read (a tampered signature
     * throws [SecurityException] mid-read). Never throws.
     */
    private fun collectSignerSet(file: File): SignerSetResult = try {
        JarFile(file, true).use { jar ->
            var unified: X509Certificate? = null
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || entry.name.startsWith("META-INF/")) continue
                jar.getInputStream(entry).use { stream ->
                    stream.copyTo(OutputStream.nullOutputStream())
                }
                val certs = entry.certificates
                if (certs == null || certs.isEmpty()) {
                    return@use SignerSetResult.Rejected(
                        "the artifact entry '$entry' is not signed — every non-META-INF entry must be signed by the pinned certificate. It will not be loaded."
                    )
                }
                if (certs.size != 1) {
                    return@use SignerSetResult.Rejected(
                        "the artifact entry '$entry' is signed by ${certs.size} certificates — " +
                            "only a single pinned signer is accepted. It will not be loaded."
                    )
                }
                val cert = certs[0] as? X509Certificate
                    ?: return@use SignerSetResult.Rejected(
                        "the artifact entry '$entry' carries a non-X.509 signer certificate. " +
                            "It will not be loaded."
                    )
                val existing = unified
                if (existing != null && !sameCert(existing, cert)) {
                    return@use SignerSetResult.Rejected(
                        "the artifact mixes different signing certificates across its entries — " +
                            "every entry must be signed by the single pinned certificate. " +
                            "It will not be loaded."
                    )
                }
                unified = cert
            }
            val signer = unified
            if (signer == null) {
                SignerSetResult.Rejected(
                    "the artifact is not signed (no signed non-META-INF entry was found). " +
                        "It will not be loaded."
                )
            } else {
                SignerSetResult.Unified(signer)
            }
        }
    } catch (_: SecurityException) {
        // The JAR verifier rejected the signature / entry digests (tampered).
        SignerSetResult.Rejected(
            "the artifact's signature could not be verified (tampered or malformed " +
                "signature block). It will not be loaded."
        )
    } catch (_: Throwable) {
        SignerSetResult.Rejected(
            "the artifact could not be verified as a signed archive. It will not be loaded."
        )
    }

    /** Byte-for-byte equality of two certificates' DER encodings. */
    private fun sameCert(a: X509Certificate, b: X509Certificate): Boolean =
        a.encoded.contentEquals(b.encoded)
}
