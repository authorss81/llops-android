package com.authorss81.noteflow.plugins.citation

import com.authorss81.noteflow.services.SsrfHostPolicy
import com.authorss81.noteflow.utils.HttpUserAgent
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Real HTTPS title fetcher for the Citation Formatter plugin.
 *
 * Pure JVM (`java.net` — no new dependency). GETs the URL (HTTPS enforced —
 * plain HTTP pages get a typed refusal, but the plugin's own fallback still
 * produces a `[host](url)` citation so the user is never blocked) and extracts
 * the `<title>` via [CitationFormatterCore.extractHtmlTitle]. Run on the
 * caller's thread (call it from `Dispatchers.IO`).
 *
 * B1-NET-04 (phase-51): every hop in the chain — the URL the user pasted AND
 * every 3xx `Location` — is re-validated against the http(s) scheme policy
 * and [SsrfHostPolicy] before a connection is made. Redirects are followed
 * manually (up to [MAX_REDIRECTS]) with `instanceFollowRedirects=false`, so a
 * crafted `Location` can never reach `localhost`, a LAN/private IP or the
 * cloud-metadata link-local address — and an HTTPS→HTTP downgrade hop is
 * refused for the default [httpsOnly] configuration.
 */

/** Typed, user-facing fetch failure. */
class TitleFetchException(message: String) : IOException(message)

/**
 * @param httpsOnly when true, plain-http URLs (initial AND on every redirect
 *   hop) are refused (never downgraded).
 */
class HttpsTitleFetcher(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 10_000,
    private val maxResponseBytes: Int = 512_000,
    private val httpsOnly: Boolean = true
) {

    /** @return the page title, or null when the page has none. */
    @Throws(TitleFetchException::class)
    fun fetch(url: String): String? {
        var cur = try {
            URI(url)
        } catch (e: Exception) {
            throw TitleFetchException("That doesn't look like a valid URL.")
        }
        repeat(MAX_REDIRECTS + 1) { _ ->
            val scheme = cur.scheme?.lowercase()
            val schemeAllowed = if (httpsOnly) scheme == "https" else (scheme == "http" || scheme == "https")
            if (!schemeAllowed) {
                throw TitleFetchException(
                    if (httpsOnly) {
                        "The Citation plugin only fetches titles over HTTPS."
                    } else {
                        "Only HTTP(S) pages can be cited."
                    }
                )
            }
            val host = cur.host
            if (host.isNullOrBlank()) {
                throw TitleFetchException("That URL does not include a host.")
            }
            val blocked = SsrfHostPolicy.blockedReason(host)
            if (blocked != null) {
                throw TitleFetchException(blocked)
            }

            val conn = cur.toURL().openConnection() as? HttpURLConnection
                ?: throw TitleFetchException("Could not fetch the page title — unsupported protocol.")
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = connectTimeoutMs
                conn.readTimeout = readTimeoutMs
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("Accept", "text/html")
                conn.setRequestProperty("User-Agent", HttpUserAgent.GENERIC)
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                        ?: throw TitleFetchException("The page redirected without a Location header.")
                    val resolved = cur.resolve(loc)
                    if (resolved.toString() == cur.toString()) {
                        throw TitleFetchException("Redirect loop detected.")
                    }
                    // Re-validate the resolved target (scheme + SSRF blocklist)
                    // BEFORE the next connection — B1-NET-04.
                    val schemeOk =
                        if (httpsOnly) resolved.scheme?.lowercase() == "https"
                        else resolved.scheme?.lowercase() in setOf("http", "https")
                    if (!schemeOk) {
                        throw TitleFetchException(
                            if (httpsOnly) {
                                "The redirect target is not HTTPS — title not fetched."
                            } else {
                                "The redirect target is not HTTP(S) — title not fetched."
                            }
                        )
                    }
                    val targetHost = resolved.host
                    val blockedTarget = if (targetHost.isNullOrBlank()) null else SsrfHostPolicy.blockedReason(targetHost)
                    if (blockedTarget != null) {
                        throw TitleFetchException("Redirect blocked: $blockedTarget")
                    }
                    cur = resolved
                    return@repeat
                }
                if (code != 200) {
                    throw TitleFetchException("The page returned HTTP $code and no title is known.")
                }
                val html = conn.inputStream.bufferedReader().use { it.readText(limit = maxResponseBytes) }
                return CitationFormatterCore.extractHtmlTitle(html)
            } catch (e: ResponseTooLargeException) {
                throw TitleFetchException("The page's HTML is too large to read.")
            } catch (e: IOException) {
                if (e is TitleFetchException) throw e
                throw TitleFetchException("Could not fetch the page title — check your connection.")
            } finally {
                conn.disconnect()
            }
        }
        throw TitleFetchException("Too many redirects while fetching the title.")
    }

    private class ResponseTooLargeException : IOException("Response too large")

    private fun java.io.Reader.readText(limit: Int): String {
        val out = StringBuilder(minOf(limit, 4096))
        val buffer = CharArray(2048)
        var total = 0
        while (true) {
            val read = read(buffer, 0, buffer.size)
            if (read == -1) break
            total += read
            if (total > limit) throw ResponseTooLargeException()
            out.append(buffer, 0, read)
        }
        return out.toString()
    }

    private companion object {
        const val MAX_REDIRECTS = 5
    }
}
