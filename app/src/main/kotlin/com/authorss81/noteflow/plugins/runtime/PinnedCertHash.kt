package com.authorss81.noteflow.plugins.runtime

import com.authorss81.noteflow.utils.ConstantTime
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * The pinned certificate hash used by the downloadable-plugin runtime (Phase 23).
 *
 * The pin format is Android's network-security pin: `sha256/<base64-of-SHA256>`
 * (the base64 is the SHA-256 of the certificate's DER encoding). One pin is the
 * trust anchor for TWO checks, both mandatory before any plugin code runs:
 *
 * 1. **TLS session** — the download host's leaf certificate must hash to the
 *    pin before a single artifact byte is trusted
 *    (`services/HttpsPluginDownloadTransport`).
 * 2. **Artifact signature** — the plugin APK's signing certificate must hash to
 *    the SAME pin ([ArtifactSignatureVerifier]).
 *
 * The pin comes from the compile-time [PluginEntry.pinnedCertHash] — never from
 * the network and never user-editable. All comparisons are constant-time via the
 * app-wide [`com.authorss81.noteflow.utils.ConstantTime`] helper (the base64
 * alphabet is plain ASCII, so the US-ASCII byte encoding is byte-identical).
 */
object PinnedCertHash {

    const val PREFIX = "sha256/"

    /** Base64 of the raw SHA-256 of a certificate's DER encoding. */
    fun base64Sha256(cert: X509Certificate): String =
        PluginDigest.sha256Base64(cert.encoded)

    /** True when [cert]'s hash matches [pin] (accepts `sha256/<b64>` or bare b64). */
    fun matches(cert: X509Certificate, pin: String): Boolean =
        ConstantTime.hexEqual(base64Sha256(cert), stripPrefix(pin))

    /** True when an already-computed base64 hash matches [pin]. */
    fun matchesBase64(actualBase64: String, pin: String): Boolean =
        ConstantTime.hexEqual(actualBase64, stripPrefix(pin))

    /**
     * Parse a `sha256/<base64>` pin into its 32 raw bytes, or null when
     * malformed or the digest is not 32 bytes (defensive — pins are pinned
     * compile-time constants, but a wrong-format pin must fail loudly).
     */
    fun parse(pin: String): ByteArray? = try {
        val decoded = Base64.getDecoder().decode(stripPrefix(pin))
        decoded.takeIf { it.size == 32 }
    } catch (_: Throwable) {
        null
    }

    private fun stripPrefix(pin: String): String =
        if (pin.startsWith(PREFIX)) pin.removePrefix(PREFIX) else pin
}
