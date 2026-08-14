package com.authorss81.noteflow.plugins.webcapture

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.authorss81.noteflow.utils.HttpUserAgent

/**
 * Platform (network) slice of Web Capture. Fetches an already-validated URL on
 * [Dispatchers.IO], with redirects followed and a hard response-size cap
 * ([WebPageFetchPolicy.MAX_RESPONSE_BYTES]) applied while streaming.
 */
class WebPageFetcher {

    suspend fun fetch(url: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching { doFetch(url) }
    }

    private fun doFetch(url: String): String {
        var cur = URI(url)
        repeat(MAX_REDIRECTS + 1) { hop ->
            if (hop > 0) {
                val scheme = cur.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") {
                    throw IOException("Redirected to a non-http(s) address — blocked.")
                }
            }
            val conn = (cur.toURL().openConnection() as? HttpURLConnection)
                ?: throw IOException("Unsupported protocol.")
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.instanceFollowRedirects = false
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", HttpUserAgent.GENERIC)
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                val status = conn.responseCode
                if (status in 300..399) {
                    val loc = conn.getHeaderField("Location")
                        ?: throw IOException("Redirect without a Location.")
                    if (cur.resolve(loc).toString() == cur.toString()) {
                        throw IOException("Redirect loop detected.")
                    }
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
    }
}
