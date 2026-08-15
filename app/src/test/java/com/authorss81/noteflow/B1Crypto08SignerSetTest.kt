package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.runtime.ArtifactSignatureVerifier
import com.authorss81.noteflow.plugins.runtime.SignerCertificatePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.cert.X509Certificate
import java.util.Date

/**
 * B1-CRYPTO-08 (phase-66): the artifact-signer pin must bind the FULL signer
 * certificate set, not a "last signed entry seen" cert, and the pinned cert
 * must be currently valid with digital-signature key usage.
 *
 * Finding: `ArtifactSignatureVerifier.findSignerCertificate` iterated entries
 * in JarFile order, skipped unsigned entries, and took `certs.firstOrNull()`
 * of the LAST entry that carried certificates — so an artifact signed by the
 * genuine cert on one benign entry and an attacker key on `classes.dex`
 * passed whenever iteration ended on the genuine entry, and an
 * expired/revoked pinned cert was silently accepted (no `checkValidity`, no
 * key-usage check).
 *
 * These tests prove the fixed behavior over REAL keytool-signed jars:
 *  - a single-pinned-signer jar still verifies;
 *  - a jar with TWO signers over every entry is rejected;
 *  - a jar with a signed entry PLUS an appended unsigned entry is rejected;
 *  - an expired signing cert is rejected even though its hash matches the pin;
 *  - the pure-JVM [SignerCertificatePolicy] decision table rejects
 *    expired / not-yet-valid / non-digitalSignature KeyUsage certs.
 */
class B1Crypto08SignerSetTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val verifier = ArtifactSignatureVerifier()

    private val invalidReason: (ArtifactSignatureVerifier.Result) -> String = { result ->
        assertTrue("expected Invalid, got $result", result is ArtifactSignatureVerifier.Result.Invalid)
        (result as ArtifactSignatureVerifier.Result.Invalid).reason
    }

    // ------------------------------------------------------------------ //
    // 1. The positive control: a single-pinned-signer jar still verifies    //
    // ------------------------------------------------------------------ //

    @Test
    fun `a single-pinned-signer jar verifies`() {
        val ks = TestArtifactBuilder.newKeystore(tmp.root, "single-signer")
        val artifact = TestArtifactBuilder.build(tmp.root, ks)

        val result = verifier.verify(artifact.file, artifact.sha256Hex, artifact.pinnedCertHash)

        assertTrue(
            "verify -> ${(result as? ArtifactSignatureVerifier.Result.Invalid)?.reason}",
            result is ArtifactSignatureVerifier.Result.Verified
        )
    }

    // ------------------------------------------------------------------ //
    // 2. Full signer-set binding                                          //
    // ------------------------------------------------------------------ //

    @Test
    fun `a jar with two signers over every entry fails verification`() {
        // Distinct aliases are REQUIRED: jarsigner replaces an existing
        // signature made under the SAME alias, so two coexisting signature
        // blocks need two differently-named keys.
        val ksA = TestArtifactBuilder.newKeystore(tmp.root, "two-signer-a", alias = "signer-a")
        val ksB = TestArtifactBuilder.newKeystore(tmp.root, "two-signer-b", alias = "signer-b")
        // jarsigner preserves an existing signature block when re-signing with a
        // different key, so every entry ends up with certs [B, A] — the
        // multi-signer shape the pre-fix verifier's `certs.firstOrNull()` ignored.
        val artifact = TestArtifactBuilder.build(tmp.root, ksA, additionalSigners = listOf(ksB))

        val result = verifier.verify(artifact.file, artifact.sha256Hex, artifact.pinnedCertHash)

        val reason = invalidReason(result)
        assertTrue("reason=$reason", reason.contains("signed by 2 certificates") || reason.contains("multiple"))
    }

    @Test
    fun `a jar mixing a signed entry and an unsigned entry fails verification`() {
        val ks = TestArtifactBuilder.newKeystore(tmp.root, "mixed-signer")
        // The appended entry is NOT covered by the signature (no manifest Name:
        // digest section) — the jar verifies, but the unsigned entry must make
        // the whole artifact fail (the pre-fix code skipped it and accepted the
        // signed sibling's cert).
        val artifact = TestArtifactBuilder.buildWithUnsignedEntry(
            tmp.root, ks,
            unsignedEntryName = "payload/smuggled.txt",
            unsignedEntryContent = "attacker bytes".toByteArray()
        )

        val result = verifier.verify(artifact.file, artifact.sha256Hex, artifact.pinnedCertHash)

        val reason = invalidReason(result)
        assertTrue("reason=$reason", reason.contains("not signed"))
    }

    @Test
    fun `an unsigned artifact is rejected even when its sha256 matches`() {
        val ks = TestArtifactBuilder.newKeystore(tmp.root, "unsigned-set-signer")
        val artifact = TestArtifactBuilder.build(tmp.root, ks, sign = false)

        val result = verifier.verify(artifact.file, artifact.sha256Hex, artifact.pinnedCertHash)

        val reason = invalidReason(result)
        assertTrue("reason=$reason", reason.contains("not signed"))
    }

    // ------------------------------------------------------------------ //
    // 3. Certificate validity period + key usage at the verify() gate        //
    // ------------------------------------------------------------------ //

    @Test
    fun `an expired signing certificate fails verification even when its hash matches the pin`() {
        // keytool -startdate 2000 + 3650 days validity => notAfter in 2009,
        // long past today. The artifact bytes + sha256 + pin are all genuine —
        // only the certificate's validity period is gone, and that MUST fail.
        val expiredKs = TestArtifactBuilder.newKeystore(tmp.root, "expired-signer", startDate = "2000/01/01 00:00:00")
        val artifact = TestArtifactBuilder.build(tmp.root, expiredKs)

        val result = verifier.verify(artifact.file, artifact.sha256Hex, artifact.pinnedCertHash)

        val reason = invalidReason(result)
        assertTrue("reason=$reason", reason.contains("expired"))
    }

    @Test
    fun `a signing certificate whose KeyUsage excludes digitalSignature is refused`() {
        val ks = TestArtifactBuilder.newKeystore(tmp.root, "no-digisig", keyUsage = "keyUsage=keyCertSign")
        val artifact = TestArtifactBuilder.build(tmp.root, ks)

        val result = verifier.verify(artifact.file, artifact.sha256Hex, artifact.pinnedCertHash)

        // Two independent gates may fire depending on how the platform JAR
        // verifier surfaces a non-signing key: it may leave the entry's
        // certificates null (signer-set "not signed" gate) or attribute the cert
        // (SignerCertificatePolicy KeyUsage gate). Either way the artifact is
        // hard-refused — never silently accepted.
        invalidReason(result)
    }

    // ------------------------------------------------------------------ //
    // 4. SignerCertificatePolicy decision table (pure JVM)                 //
    // ------------------------------------------------------------------ //

    private fun certOf(name: String, startDate: String? = null, keyUsage: String? = null): X509Certificate {
        val ks = TestArtifactBuilder.newKeystore(tmp.root, name, startDate = startDate, keyUsage = keyUsage)
        return ks.privateKeyEntry().certificateChain.first() as X509Certificate
    }

    @Test
    fun `the policy accepts a valid cert with no KeyUsage extension`() {
        // keytool's default certs carry NO KeyUsage extension — RFC 5280 treats
        // that as unrestricted, so the decision table must accept.
        val cert = certOf("policy-valid")
        assertEquals(SignerCertificatePolicy.Validation.Accept, SignerCertificatePolicy.validate(cert, Date()))
    }

    @Test
    fun `the policy accepts a cert whose KeyUsage allows digitalSignature`() {
        val cert = certOf("policy-digisig", keyUsage = "keyUsage=digitalSignature")
        assertEquals(SignerCertificatePolicy.Validation.Accept, SignerCertificatePolicy.validate(cert, Date()))
    }

    @Test
    fun `the policy rejects an expired cert`() {
        val cert = certOf("policy-expired", startDate = "2000/01/01 00:00:00")
        val verdict = SignerCertificatePolicy.validate(cert, Date())
        assertTrue(verdict is SignerCertificatePolicy.Validation.Reject)
        assertTrue((verdict as SignerCertificatePolicy.Validation.Reject).reason.contains("expired"))
    }

    @Test
    fun `the policy rejects a not-yet-valid cert`() {
        val cert = certOf("policy-not-yet", startDate = "2030/01/01 00:00:00")
        val verdict = SignerCertificatePolicy.validate(cert, Date())
        assertTrue(verdict is SignerCertificatePolicy.Validation.Reject)
        assertTrue((verdict as SignerCertificatePolicy.Validation.Reject).reason.contains("not started"))
    }

    @Test
    fun `the policy rejects a cert whose KeyUsage excludes digitalSignature`() {
        val cert = certOf("policy-ku", keyUsage = "keyUsage=keyCertSign")
        val verdict = SignerCertificatePolicy.validate(cert, Date())
        assertTrue(verdict is SignerCertificatePolicy.Validation.Reject)
        assertTrue((verdict as SignerCertificatePolicy.Validation.Reject).reason.contains("KeyUsage"))
    }

    // ------------------------------------------------------------------ //
    // 5. Wiring pins (source-level) — a refactor cannot drop the gate       //
    // ------------------------------------------------------------------ //

    @Test
    fun `verify binds the full signer set and cert policy - never a last-entry cert`() {
        val source = File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/plugins/runtime/ArtifactSignatureVerifier.kt"
        ).readText()
        assertTrue("verify() must collect the full signer set", source.contains("collectSignerSet(file)"))
        assertTrue("verify() must run the cert policy", source.contains("SignerCertificatePolicy.validate"))
        assertTrue(
            "the multi-signer entry must be refused",
            source.contains("certs.size != 1")
        )
        assertTrue(
            "an unsigned entry inside a verified jar must be refused",
            source.contains("every non-META-INF entry must be signed")
        )
        assertTrue(
            "the old last-signed-entry-wins fallback must be gone",
            !source.contains("signer = certs.firstOrNull()")
        )
    }

    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (File(dir, "gradle/libs.versions.toml").isFile && File(dir, "app").isDirectory) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}
