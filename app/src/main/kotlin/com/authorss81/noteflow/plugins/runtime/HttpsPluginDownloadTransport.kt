package com.authorss81.noteflow.plugins.runtime

import java.io.FileOutputStream
import java.io.InputStream
import java.net.URL
import java.security.cert.CertificateException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.authorss81.noteflow.utils.HttpUserAgent

/**
 * Production [DownloadTransport] (Phase 23): a pinned, TLS-only HTTPS fetch of
 * a plugin artifact into app-private storage.
 *
 * Security properties (all mandatory, enforced here regardless of caller):
 *
 * - **HTTPS only.** A non-`https` URL is refused before a connection opens —
 *   never a cleartext downgrade.
 * - **Pinned certificate.** The TLS session's leaf certificate must hash to the
 *   compile-time [PluginEntry.pinnedCertHash] carried in [DownloadRequest].
 *   The chain is first validated against the system trust store (standard
 *   [X509TrustManager] behaviour) and THEN pinned to the expected hash — an
 *   unpinned host is refused before any artifact byte is trusted.
 * - **No redirects.** `instanceFollowRedirects` is off (via
 *   [PinnedTlsConnector]); a 3xx (including an HTTPS→HTTP downgrade) answers
 *   with its redirect code and is refused — never followed.
 * - **Resume.** When [DownloadRequest.resumeFromBytes] > 0 a `Range: bytes=<n>-`
 *   header is sent and the response is appended to the partial file.
 * - **Cancel.** [DownloadRequest.isActive] is polled while streaming; when it
 *   turns false the download aborts (the caller deletes the partial file).
 * - **Size cap.** A `Content-Length` above [MAX_BYTES] aborts immediately;
 *   the streamed byte count is also capped, so a lying or chunked server
 *   cannot write past the cap.
 * - **Progress** is reported as `0f..1f` from the `Content-Length`.
 *
 * Never logs downloaded bytes. Pure JVM (only does network I/O at runtime), so
 * it compiles and is auditable next to the rest of the runtime; tests use a
 * fake transport instead of the network.
 */
class HttpsPluginDownloadTransport : DownloadTransport {

    override suspend fun download(request: DownloadRequest): DownloadTransportResult =
        withContext(Dispatchers.IO) {
            var connection: HttpsURLConnection? = null
            var stream: InputStream? = null
            try {
                val url = URL(request.url)
                if (url.protocol != "https") {
                    return@withContext DownloadTransportResult.Failed(
                        "Refusing a non-TLS plugin download (got '${url.protocol}://'). TLS only."
                    )
                }
                val connection = PinnedTlsConnector.open(url, request.pinnedCertHash)
                connection.setRequestProperty("Accept", "application/octet-stream")
                connection.setRequestProperty("User-Agent", HttpUserAgent.GENERIC)
                if (request.resumeFromBytes > 0) {
                    connection.setRequestProperty("Range", "bytes=${request.resumeFromBytes}-")
                }
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS

                val responseCode = connection.responseCode
                if (responseCode in 300..399) {
                    return@withContext DownloadTransportResult.Failed(
                        "Plugin download refused: the server answered with an HTTP redirect ($responseCode), which is never followed."
                    )
                }
                if (responseCode !in 200..299) {
                    return@withContext DownloadTransportResult.Failed(
                        "Plugin download failed (HTTP $responseCode). The artifact may not be available yet."
                    )
                }
                val contentLength = connection.contentLengthLong
                if (contentLength > MAX_BYTES) {
                    return@withContext DownloadTransportResult.Failed(
                        "Plugin download refused: artifact too large (~${contentLength / (1024 * 1024)} MB)."
                    )
                }
                stream = connection.inputStream
                // Append mode: `Range` resumes an interrupted download.
                val out = FileOutputStream(request.target, request.resumeFromBytes > 0)
                var total = request.resumeFromBytes
                try {
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (!request.isActive()) {
                            return@withContext DownloadTransportResult.Failed("Download cancelled.")
                        }
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_BYTES) {
                            return@withContext DownloadTransportResult.Failed(
                                "Plugin download refused: artifact exceeds the ${MAX_BYTES / (1024 * 1024)} MB cap."
                            )
                        }
                        out.write(buffer, 0, read)
                        if (contentLength > 0) {
                            val expected = request.resumeFromBytes + contentLength
                            if (expected > 0) {
                                request.onProgress((total.toDouble() / expected).toFloat().coerceIn(0f, 1f))
                            }
                        }
                    }
                } finally {
                    out.flush()
                    out.close()
                }
                if (total == request.resumeFromBytes) {
                    return@withContext DownloadTransportResult.Failed("Plugin download returned no bytes.")
                }
                DownloadTransportResult.Completed(total)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SSLHandshakeException) {
                // The pin gate throws CertificateException inside the handshake,
                // which surfaces wrapped as SSLHandshakeException by the JRE.
                if (isPinnedCertFailure(e)) {
                    DownloadTransportResult.Failed(
                        "Plugin download refused: the download host's certificate does not match the pinned hash."
                    )
                } else {
                    DownloadTransportResult.Failed(
                        "Plugin download failed (${e::class.java.simpleName}). Check your connection and try again."
                    )
                }
            } catch (e: CertificateException) {
                DownloadTransportResult.Failed(
                    "Plugin download refused: the download host's certificate does not match the pinned hash."
                )
            } catch (e: Throwable) {
                DownloadTransportResult.Failed(
                    "Plugin download failed (${e::class.java.simpleName}). Check your connection and try again."
                )
            } finally {
                stream?.close()
                connection?.disconnect()
            }
        }

    /** True when [throwable]'s cause chain contains a [CertificateException] —
     *  i.e. the TLS handshake was refused by the pinned-certificate gate. */
    private fun isPinnedCertFailure(throwable: Throwable): Boolean {
        var cause: Throwable? = throwable
        while (cause != null) {
            if (cause is CertificateException) return true
            cause = cause.cause
        }
        return false
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 40_000
        const val MAX_BYTES: Long = PluginDownloader.MAX_ARTIFACT_BYTES
    }
}
