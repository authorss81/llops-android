package com.authorss81.noteflow.plugins.runtime

import java.net.URL
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Shared pinned-TLS machinery for the downloadable-plugin runtime (Phase 23/24).
 *
 * Both the artifact transport ([HttpsPluginDownloadTransport]) and the update
 * manifest transport ([HttpsManifestTransport]) must authenticate the server
 * before trusting a single byte. [open] performs, in order:
 *
 * 1. **Chain validation** against [baseTrustManager] (production: the system
 *    trust store — the same `X509TrustManager` behaviour `HttpURLConnection`
 *    would apply).
 * 2. **Leaf pinning** — the server's leaf certificate must hash (SHA-256 of its
 *    DER encoding, constant-time comparison) to [expectedCertHash]. A mismatch
 *    throws [CertificateException] before any request/response bytes move.
 *
 * The returned connection **never auto-follows redirects**
 * (`instanceFollowRedirects = false`): a redirecting endpoint answers with its
 * 3xx code and the caller decides (never follow — this closes the HTTPS→HTTP
 * downgrade path in B1-CRYPTO-01 / B1-NET-05 for the plugin transports).
 */
object PinnedTlsConnector {

    /**
     * Open an HTTPS connection to [url] whose server certificate is
     * chain-validated against [baseTrustManager] and then pinned to
     * [expectedCertHash] (`sha256/<base64>` — the format validated by
     * [PinnedCertHash.parse]). A mismatching leaf throws [CertificateException]
     * during the handshake, before the request is sent.
     */
    fun open(
        url: URL,
        expectedCertHash: String,
        baseTrustManager: X509TrustManager = systemTrustManager()
    ): HttpsURLConnection {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(
            null,
            arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    // 1) Standard chain validation against the configured trust anchor.
                    baseTrustManager.checkServerTrusted(chain, authType)
                    // 2) Then pin the leaf to the expected compile-time hash.
                    val leaf = chain.firstOrNull()
                        ?: throw CertificateException("no server certificate presented")
                    if (!PinnedCertHash.matches(leaf, expectedCertHash)) {
                        throw CertificateException(
                            "server certificate does not match the pinned certificate hash"
                        )
                    }
                }
            }),
            null
        )
        return (url.openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = sslContext.socketFactory
            hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
            useCaches = false
            // NEVER auto-follow redirects: a 3xx is surfaced to the caller who
            // refuses it (a redirected connection could downgrade to plaintext).
            instanceFollowRedirects = false
        }
    }

    /** The platform default `X509TrustManager` (system trust store). */
    fun systemTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as java.security.KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}