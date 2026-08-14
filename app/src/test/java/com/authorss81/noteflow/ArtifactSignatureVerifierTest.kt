package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.runtime.ArtifactSignatureVerifier
import com.authorss81.noteflow.plugins.runtime.PluginDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 23: the security-critical [ArtifactSignatureVerifier] gate. A downloaded
 * plugin artifact is accepted ONLY when its bytes hash to the pinned sha256 AND
 * its signing certificate hashes to the pinned certificate. Any mismatch —
 * tampered bytes, a missing signature, a different signing key, a wrong file —
 * is a hard [ArtifactSignatureVerifier.Result.Invalid] that must precede any
 * load. Pure JVM over real keytool-signed JARs.
 */
class ArtifactSignatureVerifierTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val verifier = ArtifactSignatureVerifier()

    @Test
    fun `a genuinely signed artifact with matching digests verifies`() {
        val ks = TestArtifactBuilder.newKeystore(tmp.root, "valid-signer")
        val artifact = TestArtifactBuilder.build(tmp.root, ks)

        val result = verifier.verify(artifact.file, artifact.sha256Hex, artifact.pinnedCertHash)

        assertTrue("verify -> ${(result as? ArtifactSignatureVerifier.Result.Invalid)?.reason}", result is ArtifactSignatureVerifier.Result.Verified)
        result as ArtifactSignatureVerifier.Result.Verified
        assertEquals(artifact.sha256Hex, result.sha256Hex)
        assertEquals(artifact.pinnedCertHash.removePrefix("sha256/"), result.signingCertHash)
    }

    @Test
    fun `a tampered artifact is rejected before any load`() {
        val ks = TestArtifactBuilder.newKeystore(tmp.root, "tamper-signer")
        val artifact = TestArtifactBuilder.build(tmp.root, ks)

        // Flip a single byte in the middle of the artifact's data region. The
        // sha256 gate runs BEFORE the JAR is opened, so any flip is a hard
        // "SHA-256 mismatch" — the artifact is never loaded.
        val tampered = java.io.File(tmp.root, "tampered.jar")
        artifact.file.copyTo(tampered)
        tampered.inputStream().use { input ->
            val bytes = input.readBytes()
            val payload = bytes.size / 2
            bytes[payload] = (bytes[payload].toInt() xor 0x01).toByte()
            tampered.outputStream().use { output -> output.write(bytes) }
        }

        val result = verifier.verify(tampered, artifact.sha256Hex, artifact.pinnedCertHash)

        assertTrue(result is ArtifactSignatureVerifier.Result.Invalid)
        assertTrue((result as ArtifactSignatureVerifier.Result.Invalid).reason.contains("SHA-256"))
    }

    @Test
    fun `a sha256 mismatch is rejected even with the correct signing cert`() {
        val ks = TestArtifactBuilder.newKeystore(tmp.root, "sha-signer")
        val artifact = TestArtifactBuilder.build(tmp.root, ks)

        val result = verifier.verify(artifact.file, "0".repeat(64), artifact.pinnedCertHash)

        assertTrue(result is ArtifactSignatureVerifier.Result.Invalid)
        assertTrue((result as ArtifactSignatureVerifier.Result.Invalid).reason.contains("SHA-256"))
    }

    @Test
    fun `an artifact signed by a different key is rejected even with matching sha256`() {
        val signerA = TestArtifactBuilder.newKeystore(tmp.root, "signer-a")
        val signerB = TestArtifactBuilder.newKeystore(tmp.root, "signer-b")
        val artA = TestArtifactBuilder.build(tmp.root, signerA)
        val artB = TestArtifactBuilder.build(tmp.root, signerB)

        // Use A's bytes/sha against B's pin → cert mismatch (sha is valid).
        val result = verifier.verify(artA.file, artA.sha256Hex, artB.pinnedCertHash)

        assertTrue(result is ArtifactSignatureVerifier.Result.Invalid)
        val reason = (result as ArtifactSignatureVerifier.Result.Invalid).reason
        assertTrue("reason=$reason", reason.contains("certificate") && reason.contains("pinned"))
    }

    @Test
    fun `an unsigned artifact is rejected even when its sha256 matches`() {
        val ks = TestArtifactBuilder.newKeystore(tmp.root, "unsigned-signer")
        // Unsigned copy of the same bytes → sha matches against itself, but the
        // verifier must refuse because there is no signer certificate at all.
        val unsigned = TestArtifactBuilder.build(tmp.root, ks, sign = false)

        val result = verifier.verify(unsigned.file, unsigned.sha256Hex, unsigned.pinnedCertHash)

        assertTrue(result is ArtifactSignatureVerifier.Result.Invalid)
        assertTrue((result as ArtifactSignatureVerifier.Result.Invalid).reason.contains("not signed"))
    }

    @Test
    fun `a missing or unreadable file is rejected`() {
        val missing = java.io.File(tmp.root, "does-not-exist.jar")

        val result = verifier.verify(missing, "ab12", "sha256/AAAA")

        assertTrue(result is ArtifactSignatureVerifier.Result.Invalid)
        assertTrue((result as ArtifactSignatureVerifier.Result.Invalid).reason.contains("not found"))
    }

    @Test
    fun `an empty or corrupt file is rejected`() {
        val corrupt = java.io.File(tmp.root, "corrupt.jar")
        corrupt.writeBytes(ByteArray(16) { 0x00 })

        val result = verifier.verify(corrupt, "00".repeat(32), "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")

        assertTrue(result is ArtifactSignatureVerifier.Result.Invalid)
    }

    @Test
    fun `verification is content-derived, never a stale cached value`() {
        val ks = TestArtifactBuilder.newKeystore(tmp.root, "determinism")
        val a = TestArtifactBuilder.build(tmp.root, ks)
        val b = TestArtifactBuilder.build(tmp.root, ks)

        // Each artifact's sha256 gate is its OWN current content hash — a build
        // (no matter how close in time) verifies independently against its own
        // digests, proving the gate is not a stale cache from a previous verify.
        assertTrue(
            verifier.verify(a.file, a.sha256Hex, a.pinnedCertHash) is ArtifactSignatureVerifier.Result.Verified
        )
        assertTrue(
            verifier.verify(b.file, b.sha256Hex, b.pinnedCertHash) is ArtifactSignatureVerifier.Result.Verified
        )
        // And tampering one artifact does not poison the other's result.
        val tampered = java.io.File(tmp.root, "determinism-tampered.jar")
        b.file.copyTo(tampered)
        tampered.inputStream().use { input ->
            val bytes = input.readBytes()
            bytes[bytes.size - 2] = (bytes[bytes.size - 2].toInt() xor 0x01).toByte()
            tampered.outputStream().use { output -> output.write(bytes) }
        }
        val corrupted = verifier.verify(tampered, b.sha256Hex, b.pinnedCertHash)
        assertTrue(corrupted is ArtifactSignatureVerifier.Result.Invalid)
        assertTrue((corrupted as ArtifactSignatureVerifier.Result.Invalid).reason.contains("SHA-256"))
    }
}