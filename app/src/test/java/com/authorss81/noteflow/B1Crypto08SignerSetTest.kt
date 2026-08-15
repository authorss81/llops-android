package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.runtime.ArtifactSignatureVerifier
import com.authorss81.noteflow.plugins.runtime.SignerCertificatePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.cert.Certificate
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
 *  - a CA-chain-signed jar (a single signer whose leaf carries issuers) STILL
 *    verifies — "one signer" means one signer chain, not one certificate;
 *  - a jar with TWO signers over every entry is rejected;
 *  - a jar with a signed entry PLUS an appended unsigned entry is rejected;
 *  - an expired signing cert is rejected even though its hash matches the pin;
 *  - the pure-JVM [SignerCertificatePolicy] decision table rejects
 *    expired / not-yet-valid / non-digitalSignature KeyUsage certs.
 *
 * A "mixing different signers across entries, each a single chain" jar is NOT
 * covered with a real fixture because standard jarsigner output cannot produce
 * one: any second signature block must cover every entry (a hand-merged
 * manifest breaks the `.SF` "Manifest main attributes" digest, which the JAR
 * verifier enforces — verified on JDK 21). That branch of [singleSignerChain]
 * is pinned by the synthetic chain-decomposition tests below and the source
 * pin, which is the honest level of coverage for a shape standard tooling
 * cannot emit.
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
        assertTrue("reason=$reason", reason.contains("multiple signers"))
    }

    @Test
    fun `a jar signed by a single signer with a CA-issued certificate chain verifies`() {
        // B1-CRYPTO-08 regression guard (review finding 1): the JAR verifier
        // reports a signer's WHOLE chain in JarEntry.getCertificates()
        // (leaf-first). A single CA-issued signer therefore yields MORE than one
        // certificate per entry — the old `certs.size != 1` check wrongly
        // rejected it as "multi-signer". The pin binds the LEAF; the chain is a
        // property of that one signer and must be accepted.
        val chainKs = TestArtifactBuilder.newChainKeystore(tmp.root, "chain-signer")
        val artifact = TestArtifactBuilder.build(tmp.root, chainKs)

        val result = verifier.verify(artifact.file, artifact.sha256Hex, artifact.pinnedCertHash)

        assertTrue(
            "verify -> ${(result as? ArtifactSignatureVerifier.Result.Invalid)?.reason}",
            result is ArtifactSignatureVerifier.Result.Verified
        )
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
        // certificates null (signer-set "not signed" gate — observed on JDK 21)
        // or attribute the cert (SignerCertificatePolicy KeyUsage gate). Either
        // way the artifact is hard-refused and the REASON must be pinned to one
        // of the two enforcement layers, so a refactor cannot silently drop one.
        val reason = invalidReason(result)
        assertTrue(
            "reason=$reason (must cite the signer-set 'not signed' gate or the KeyUsage gate)",
            reason.contains("not signed") || reason.contains("KeyUsage")
        )
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
            "the signer set must be judged per signer chain (leaf + issuers), not per certificate",
            source.contains("singleSignerChain(certs)")
        )
        assertTrue(
            "a multi-signer entry must be refused (singleSignerChain returning null)",
            source.contains("signed by multiple signers")
        )
        assertTrue(
            "entries mixing different signer chains must be refused",
            source.contains("mixes different signing certificates across its entries")
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

    // ------------------------------------------------------------------ //
    // 6. singleSignerChain / sameChain decision table (synthetic cert sets)  //
    //    Pins the chain decomposition: "one signer" == one chain (leaf +      //
    //    issuers), not one certificate. A disjoint-signers-across-entries jar  //
    //    cannot be built with standard jarsigner output (the JAR verifier     //
    //    enforces each .SF's "Manifest main attributes" digest against the     //
    //    SHARED manifest, so a second signer necessarily covers every entry —  //
    //    verified on JDK 21), so the multi-chain boundary and the cross-entry  //
    //    equality rules are pinned here over synthetic certificate sets.       //
    // ------------------------------------------------------------------ //

    private fun certOfAlias(name: String, alias: String = "plugin"): X509Certificate {
        val ks = TestArtifactBuilder.newKeystore(tmp.root, name, alias = alias)
        return ks.privateKeyEntry().certificateChain.first() as X509Certificate
    }

    @Test
    fun `singleSignerChain treats a lone self-signed signer as one chain`() {
        val a = certOfAlias("chain-a")
        val chain = verifier.singleSignerChain(arrayOf<Certificate>(a))
        assertEquals(listOf(a), chain)
    }

    @Test
    fun `singleSignerChain treats a CA-issued chain as one chain`() {
        val ks = TestArtifactBuilder.newChainKeystore(tmp.root, "chain-split")
        val entry = ks.privateKeyEntry().certificateChain
        val leaf = entry[0] as X509Certificate
        val ca = entry[1] as X509Certificate
        assertEquals(listOf(leaf, ca), verifier.singleSignerChain(arrayOf<Certificate>(leaf, ca)))
    }

    @Test
    fun `singleSignerChain rejects two unrelated self-signed signers`() {
        // Both fixtures use the SAME subject DN, so the boundary cannot be found
        // by DN comparison alone — only the signature check (leaf is not signed
        // by the other key) separates them. This is the multi-signer shape.
        val a = certOfAlias("two-chains-a", alias = "plugin-a")
        val b = certOfAlias("two-chains-b", alias = "plugin-b")
        assertEquals(null, verifier.singleSignerChain(arrayOf<Certificate>(a, b)))
    }

    @Test
    fun `singleSignerChain rejects a genuine chain with an extra signer appended`() {
        val ks = TestArtifactBuilder.newChainKeystore(tmp.root, "chain-plus")
        val entry = ks.privateKeyEntry().certificateChain
        val leaf = entry[0] as X509Certificate
        val ca = entry[1] as X509Certificate
        val extra = certOfAlias("chain-extra")
        assertEquals(null, verifier.singleSignerChain(arrayOf<Certificate>(leaf, ca, extra)))
    }

    @Test
    fun `singleSignerChain rejects an empty certificate set`() {
        assertEquals(null, verifier.singleSignerChain(emptyArray<Certificate>()))
    }

    @Test
    fun `sameChain compares whole signer chains element-wise`() {
        val ks = TestArtifactBuilder.newChainKeystore(tmp.root, "same-chain")
        val entry = ks.privateKeyEntry().certificateChain
        val leaf = entry[0] as X509Certificate
        val ca = entry[1] as X509Certificate
        assertTrue(verifier.sameChain(listOf(leaf, ca), listOf(leaf, ca)))
        assertTrue(!verifier.sameChain(listOf(leaf), listOf(leaf, ca)))
        val other = certOfAlias("same-chain-other")
        assertTrue(!verifier.sameChain(listOf(leaf), listOf(other)))
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
