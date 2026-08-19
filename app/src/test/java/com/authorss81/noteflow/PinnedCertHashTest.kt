package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.runtime.PinnedCertHash
import com.authorss81.noteflow.plugins.runtime.PLUGIN_MANIFEST_CERT_PIN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.cert.X509Certificate
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
 *
 * Phase 171 (Phase-32-NEW-04 INFO): added the cert-level `matches` fail-closed
 * contract — a KNOWN-GOOD pin matches its real certificate, while the
 * compiled-in placeholder manifest pin, any well-formed wrong pin, near-miss
 * (1-char shorter/longer), wrong-prefix and blank pins NEVER match. This pins
 * the exact placeholder value so a future edit can neither silently accept a
 * bad pin nor swap in a changed-but-still-wrong constant, and keeps the hosted
 * plugin-update channel fail-closed until the operator substitutes the real
 * `plugin-updates.inkflow.app` leaf hash (docs/PLUGIN_CHANNEL.md).
 */
class PinnedCertHashTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A fresh real leaf certificate, generated like the transport tests do. */
    private lateinit var cert: X509Certificate

    /** The correct `sha256/<base64>` pin of [cert] (the "known-good" pin). */
    private lateinit var goodPin: String

    @Before
    fun generateCert() {
        cert = newSelfSignedCert()
        goodPin = "sha256/" + PinnedCertHash.base64Sha256(cert)
    }

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

    @Test
    fun `known-good pin matches its real certificate`() {
        assertTrue(PinnedCertHash.matches(cert, goodPin))
    }

    @Test
    fun `the placeholder manifest pin never matches any real certificate`() {
        val actualBase64 = PinnedCertHash.base64Sha256(cert)
        assertFalse("cert-matches path", PinnedCertHash.matches(cert, PLUGIN_MANIFEST_CERT_PIN))
        assertFalse("base64-matches path", PinnedCertHash.matchesBase64(actualBase64, PLUGIN_MANIFEST_CERT_PIN))
        assertFalse("known-good must differ from the placeholder", actualBase64 == PLUGIN_MANIFEST_CERT_PIN.removePrefix("sha256/"))
    }

    @Test
    fun `a well-formed wrong pin and near-miss pins never match a certificate`() {
        val otherPin = "sha256/${digest(5)}" // 32-byte well-formed, wrong digest
        assertFalse(PinnedCertHash.matches(cert, otherPin))
        assertFalse(PinnedCertHash.matches(cert, goodPin.dropLast(1))) // one char short
        assertFalse(PinnedCertHash.matches(cert, "$goodPin="))         // one char too long
    }

    @Test
    fun `wrong prefix malformed and blank pins never match a certificate`() {
        assertFalse(PinnedCertHash.matches(cert, "SHA256/${goodPin.removePrefix("sha256/")}"))
        assertFalse(PinnedCertHash.matches(cert, "sha256/\$\$\$notbase64\$\$\$"))
        assertFalse(PinnedCertHash.matches(cert, ""))
        assertFalse(PinnedCertHash.matches(cert, "sha256/"))
        assertFalse(PinnedCertHash.matches(cert, "sha256/${digest(0)}")) // valid-form, wrong digest
    }

    @Test
    fun `placeholder manifest pin source contract is pinned so the channel stays fail-closed`() {
        // Phase 171 (Phase-32-NEW-04 INFO): the compiled-in manifest cert pin must
        // remain the documented, well-formed REPLACEMENT placeholder until the
        // operator substitutes the real plugin-updates.inkflow.app leaf hash (see
        // docs/PLUGIN_CHANNEL.md). A changed-but-still-wrong constant is worse than
        // the documented placeholder, and a silently-accepted bad pin is a trust
        // regression — this test pins the exact value AND the fail-closed contract:
        // it must parse as a valid 32-byte pin (so the transport CONFIGURES the pin
        // and enforces the pin gate — never silently degrading to unpinned HTTPS)
        // and must never hash-match any real certificate.
        assertEquals(
            "sha256/AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
            PLUGIN_MANIFEST_CERT_PIN
        )
        val parsed = PinnedCertHash.parse(PLUGIN_MANIFEST_CERT_PIN)
        assertTrue("the placeholder must be a well-formed 32-byte pin", parsed != null && parsed.size == 32)
        assertFalse("a wrong pin must never be accepted", PinnedCertHash.matches(cert, PLUGIN_MANIFEST_CERT_PIN))
    }

    private fun newSelfSignedCert(): X509Certificate {
        val ksFile = File(tmp.root, "pinned-cert-hash-test.p12")
        val password = "noteflow-test-pass"
        val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
        val cmd = listOf(
            keytool,
            "-genkeypair", "-alias", "server",
            "-keyalg", "RSA", "-keysize", "2048",
            "-sigalg", "SHA256withRSA",
            "-validity", "3650",
            "-dname", "CN=plugin-updates-test.local",
            "-ext", "san=dns:plugin-updates-test.local",
            "-keystore", ksFile.absolutePath,
            "-storetype", "PKCS12",
            "-storepass", password,
            "-keypass", password,
            "-noprompt"
        )
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "keytool failed: $output" }

        val ks = KeyStore.getInstance("PKCS12")
        FileInputStream(ksFile).use { ks.load(it, password.toCharArray()) }
        val entry = ks.getEntry("server", KeyStore.PasswordProtection(password.toCharArray()))
            as KeyStore.PrivateKeyEntry
        return entry.certificateChain.first() as X509Certificate
    }
}
