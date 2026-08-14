package com.authorss81.noteflow.plugins.citation

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Real HTTPS title fetcher for the Citation Formatter plugin.
 *
 * Pure JVM (`java.net` — no new dependency). GETs the URL (HTTPS enforced —
 * plain HTTP pages get a typed refusal, but the plugin's own fallback still
 * produces a `[host](url)` citation so the user is never blocked) and extracts
 * the `<title>` via [CitationFormatterCore.extractHtmlTitle]. Runs on the
 * caller's thread (call it from `Dispatchers.IO`).
 */

/** Typed, user-facing fetch failure. */
class TitleFetchException(message: String) : IOException(message)

/**
 * @param httpsOnly when true, plain-http URLs are refused (never downgraded).
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
        val parsed = URL(url)
        if (httpsOnly && parsed.protocol != "https") {
            throw TitleFetchException("The Citation plugin only fetches titles over HTTPS.")
        }
        val conn = parsed.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Accept", "text/html")
            val code = conn.responseCode
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
}