package com.authorss81.noteflow.plugins.runtime

import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import java.util.Date

/**
 * B1-CRYPTO-08 (phase-66): the single pure-JVM decision table for whether an
 * artifact-signing certificate is usable as the pinned signer of a
 * downloadable-plugin artifact.
 *
 * The finding: the old `ArtifactSignatureVerifier` compared the pinned cert
 * hash against ONE cert taken from the LAST signed entry seen, and never asked
 * whether that cert is currently usable — an expired or revoked pinned cert
 * was silently accepted, and the pin only proved "at least one entry was
 * signed by this cert".
 *
 * This policy closes the certificate-ITSELF half of the finding (the full
 * signer-set binding lives in [ArtifactSignatureVerifier.collectSignerSet]):
 *
 *  - **Validity period** — `checkValidity(now)` is mandatory. An expired or
 *    not-yet-valid cert cannot be the archive signer, even when its hash still
 *    matches the pin.
 *  - **Key usage** — the RFC-5280 `KeyUsage` extension must permit
 *    digital signatures (bit 0). An absent extension is unrestricted (per RFC
 *    5280) and accepted; an explicit extension whose `digitalSignature` bit is
 *    not set rejects the cert. This also hardens against a platform JAR
 *    verifier that happens to surface a key-usage-invalid cert with `null`
 *    entry certificates (the signer-set gate then rejects it as unsigned).
 *
 * Pure JVM (no Android imports) so the table is unit-testable in
 * `app/src/test` and runs unchanged on the API 26+ floor.
 */
internal object SignerCertificatePolicy {

    /** RFC-5280 KeyUsage bit index for digitalSignature. */
    const val DIGITAL_SIGNATURE_BIT = 0

    sealed class Validation {
        object Accept : Validation()
        data class Reject(val reason: String) : Validation()
    }

    /**
     * [now] is injectable so the decision table is deterministic under test
     * (an "expired" verdict does not depend on the wall clock).
     */
    fun validate(cert: X509Certificate, now: Date = Date()): Validation {
        val period = validity(cert, now)
        if (period != null) return period
        return keyUsage(cert)
    }

    private fun validity(cert: X509Certificate, now: Date): Validation.Reject? = try {
        cert.checkValidity(now)
        null
    } catch (_: CertificateExpiredException) {
        Validation.Reject("its validity period has expired (notAfter ${cert.notAfter})")
    } catch (_: CertificateNotYetValidException) {
        Validation.Reject("its validity period has not started yet (notBefore ${cert.notBefore})")
    } catch (_: Throwable) {
        Validation.Reject("its validity period could not be verified")
    }

    private fun keyUsage(cert: X509Certificate): Validation {
        val usage = cert.keyUsage
        if (usage == null) {
            // RFC 5280: an absent KeyUsage extension leaves the key unrestricted.
            return Validation.Accept
        }
        if (usage.isEmpty() || usage[DIGITAL_SIGNATURE_BIT].not()) {
            return Validation.Reject(
                "its KeyUsage extension does not allow digital signatures"
            )
        }
        return Validation.Accept
    }
}
