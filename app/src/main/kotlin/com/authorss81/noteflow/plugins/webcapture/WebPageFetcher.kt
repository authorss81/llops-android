package com.authorss81.noteflow.plugins.webcapture

import com.authorss81.noteflow.services.DnsRebindingPolicy
import com.authorss81.noteflow.utils.HttpUserAgent
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Platform (network) slice of Web Capture. Fetches an already-validated URL on
 * [Dispatchers.IO], with redirects followed and a hard response-size cap
 * ([WebPageFetchPolicy.MAX_RESPONSE_BYTES]) applied while streaming.
 *
 * R2-B1N-02 (phase-144): every hop is additionally RESOLVED and PINNED via
 * [DnsRebindingPolicy] before a connection is made — the textual
 * [WebPageFetchPolicy.rejectHop] gate sees only the host string, so a
 * DNS-rebinding domain is refused when its resolution contains an internal
 * address, and the connect itself is pinned to the validated addresses
 * ([DnsRebindingPolicy.applyPinToConnection]).
 */
class WebPageFetcher(
    private val dnsResolver: (String) -> Array<InetAddress> = DnsRebindingPolicy.DEFAULT_RESOLVER
) {

    /**
     * @param allowInsecureHttp per-fetch cleartext opt-in (R2-B1N-04). The
     *   caller (WebCaptureEngine) derives it from its own opt-in and passes it
     *   to EVERY hop so the redirect policy matches the entry policy.
     */
    suspend fun fetch(url: String, allowInsecureHttp: Boolean = false): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching { doFetch(url, allowInsecureHttp) }
        }

    private fun doFetch(url: String, allowInsecureHttp: Boolean): String {
        var cur = URI(url)
        repeat(MAX_REDIRECTS + 1) { _ ->
            // Re-apply the entry policy to EVERY hop (B1-NET-04): a redirect
            // target is parsed and validated against the same scheme
            // allow-list + SSRF host blocklist before any connection is made.
            // R2-B1N-04: http stays refused unless the fetch was opted in.
            val hopError = WebPageFetchPolicy.rejectHop(cur.toString(), allowInsecureHttp)
            if (hopError != null) {
                throw IOException(hopError)
            }
            val host = cur.host
                ?: throw IOException("That address does not include a host.")
            val pin = when (val verdict = DnsRebindingPolicy.resolveAndPin(host, dnsResolver)) {
                is DnsRebindingPolicy.Verdict.Pinned -> verdict
                is DnsRebindingPolicy.Verdict.Refused -> throw IOException(verdict.reason)
            }
            val conn = (cur.toURL().openConnection() as? HttpURLConnection)
                ?: throw IOException("Unsupported protocol.")
            DnsRebindingPolicy.applyPinToConnection(conn, host, pin.addresses, CONNECT_TIMEOUT_MS)
            try {
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = 15_000
                conn.instanceFollowRedirects = false
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", HttpUserAgent.GENERIC)
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                val status = conn.responseCode
                if (status in 300..399) {
                    val loc = conn.getHeaderField("Location")
                        ?: throw IOException("Redirect without a Location.")
                    val resolved = cur.resolve(loc)
                    if (resolved.toString() == cur.toString()) {
                        throw IOException("Redirect loop detected.")
                    }
                    val nextError = WebPageFetchPolicy.rejectHop(resolved.toString(), allowInsecureHttp)
                    if (nextError != null) {
                        throw IOException(nextError)
                    }
                    cur = resolved
                    return@repeat
                }
                if (status !in 200..299) {
                    throw IOException("The server responded with HTTP $status.")
                }
                val contentType = conn.getHeaderField("Content-Type")?.lowercase() ?: ""
                if (contentType.isNotEmpty() && "html" !in contentType && "text" !in contentType) {
                    throw IOException("That address does not serve a web page.")
                }
                return readCapped(conn)
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Too many redirects.")
    }

    private fun readCapped(conn: HttpURLConnection): String {
        conn.inputStream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > WebPageFetchPolicy.MAX_RESPONSE_BYTES) {
                    throw IOException(
                        "That page is too large to capture (over ${WebPageFetchPolicy.MAX_RESPONSE_BYTES / (1024 * 1024)} MB)."
                    )
                }
                out.write(buf, 0, n)
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        const val CONNECT_TIMEOUT_MS = 10_000
    }
}
