package com.authorss81.noteflow.plugins.websearch

import com.authorss81.noteflow.plugins.WebSearchResult
import com.authorss81.noteflow.services.DnsRebindingPolicy
import com.authorss81.noteflow.services.StrictRedirectPolicy
import com.authorss81.noteflow.utils.HttpUserAgent
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.URLEncoder

/**
 * A real, keyless web-search backend: the DuckDuckGo Instant Answer API
 * (`https://api.duckduckgo.com/?q=...&format=json&no_html=1`).
 *
 * Pure JVM (java.net — no okhttp added): the client runs on device on
 * `Dispatchers.IO`, and its URL builder + JSON parser are unit-tested with
 * sample payloads (`WebSearchPluginTest`) without any network.
 */

/** Thrown when the search service returns something unusable. Message is
 *  user-facing and deliberately free of any server content. */
class DuckDuckGoSearchException(message: String) : IOException(message)

/** Builds the DuckDuckGo Instant Answer query URL. Pure, unit-tested. */
object DuckDuckGoQueryUrl {

    const val DEFAULT_BASE = "https://api.duckduckgo.com/"

    fun build(query: String, base: String = DEFAULT_BASE): String {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return buildString {
            append(base.trimEnd('/'))
            append("?q=").append(encoded)
            append("&format=json")
            append("&no_html=1")
            append("&no_redirect=1")
            append("&d=1") // include deep ("Topics") results
            append("&t=inkflow")
        }
    }
}

/** Maps a DDG Instant Answer JSON payload to [WebSearchResult]s. Pure, tested. */
object DuckDuckGoResponseParser {

    fun parse(json: String): List<WebSearchResult> {
        if (json.isBlank()) return emptyList()
        val answer: RawAnswer = try {
            Gson().fromJson(json, RawAnswer::class.java)
        } catch (e: JsonSyntaxException) {
            throw DuckDuckGoSearchException("The search service returned an unreadable response.")
        }

        val dedupe = LinkedHashMap<String, WebSearchResult>()

        // FIRST occurrence of a URL wins: the richer instant answer (Heading +
        // AbstractText) is inserted before the related topics, so a URL shared by
        // the abstract and a related topic keeps the abstract's title/snippet.
        fun addOnce(url: String, result: WebSearchResult) {
            if (!dedupe.containsKey(url)) dedupe[url] = result
        }

        // The primary "instant answer" (Abstract/AbstractURL), when present.
        val abstractText = answer.AbstractText?.takeIf { it.isNotBlank() }
        val abstractUrl = answer.AbstractURL?.takeIf { it.startsWith("http", ignoreCase = true) }
        if (abstractText != null && abstractUrl != null) {
            addOnce(
                abstractUrl,
                WebSearchResult(
                    title = displayTitle(answer.Heading?.takeIf { it.isNotBlank() }, abstractUrl),
                    url = abstractUrl,
                    snippet = abstractText
                )
            )
        }

        // Related topics (and their nested Topics — the `d=1` deep results).
        fun collect(topics: List<RelatedTopic>?) {
            topics.orEmpty().forEach { topic ->
                val text = topic.Text?.takeIf { it.isNotBlank() }
                val url = topic.FirstURL?.takeIf { it.startsWith("http", ignoreCase = true) }
                if (text != null && url != null) {
                    addOnce(
                        url,
                        WebSearchResult(
                            title = displayTitle(titleFromText(text), url),
                            url = url,
                            snippet = text
                        )
                    )
                }
                if (!topic.Topics.isNullOrEmpty()) collect(topic.Topics)
            }
        }
        collect(answer.RelatedTopics)

        return dedupe.values.toList()
    }

    /** DDG "Text" is usually "Title - Description": label the link with the title. */
    private fun titleFromText(text: String): String =
        text.substringBefore(" - ").substringBefore(" \u2013 ")
            .trim().takeIf { it.isNotBlank() } ?: text.take(80).trim()

    private fun displayTitle(title: String?, url: String): String {
        title?.takeIf { it.isNotBlank() }?.let { return it.take(120).trim() }
        return url.substringAfter("://").substringBefore("/").ifBlank { url }
    }

    /** Minimal DDG JSON shape (unknown fields ignored by Gson). */
    data class RawAnswer(
        val AbstractText: String? = null,
        val AbstractURL: String? = null,
        val Heading: String? = null,
        val RelatedTopics: List<RelatedTopic>? = null
    )

    data class RelatedTopic(
        val Text: String? = null,
        val FirstURL: String? = null,
        val Topics: List<RelatedTopic>? = null
    )
}

/**
 * HTTP GET client for the DDG Instant Answer API. Runs the request on the
 * caller's thread (call it from `Dispatchers.IO`). Connectivity failures are
 * converted to a clear, user-facing [DuckDuckGoSearchException]; HTTP != 200 is
 * likewise a typed error — nothing is ever silently swallowed.
 *
 * R2-B1N-02 (phase-144): every hop is additionally RESOLVED and PINNED via
 * [DnsRebindingPolicy] before a connection is opened — a DNS-rebinding answer
 * (an internal A/AAAA, regardless of the textual host string) refuses the whole
 * hop, and the connect is pinned to the validated addresses
 * ([DnsRebindingPolicy.applyPinToConnection]).
 */
class DuckDuckGoClient(
    private val urlBuilder: (String) -> String = DuckDuckGoQueryUrl::build,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 10_000,
    private val maxResponseBytes: Int = 1_000_000,
    private val dnsResolver: (String) -> Array<InetAddress> = DnsRebindingPolicy.DEFAULT_RESOLVER,
    private val connectionFactory: (String) -> HttpURLConnection = { url ->
        URL(url).openConnection() as HttpURLConnection
    }
) {

    fun search(query: String): List<WebSearchResult> {
        var cur = try {
            URI(urlBuilder(query))
        } catch (e: Exception) {
            throw DuckDuckGoSearchException("That doesn't look like a valid search-service URL.")
        }
        try {
            repeat(StrictRedirectPolicy.MAX_REDIRECTS + 1) { _ ->
                // B1-NET-05: reject any hop whose scheme is not https (the entry
                // URL AND every 3xx target) and any hop on the B1-NET-04 SSRF
                // blocklist, BEFORE a connection is opened.
                StrictRedirectPolicy.checkTlsHop(cur)
                // R2-B1N-02: resolve + validate every answer, then pin the connect
                // to the checked addresses (or refuse the name entirely).
                val pin = when (val verdict = DnsRebindingPolicy.resolveAndPin(cur.host ?: "", dnsResolver)) {
                    is DnsRebindingPolicy.Verdict.Pinned -> verdict
                    is DnsRebindingPolicy.Verdict.Refused -> throw DuckDuckGoSearchException(verdict.reason)
                }
                val conn = connectionFactory(cur.toString())
                try {
                    conn.requestMethod = "GET"
                    conn.connectTimeout = connectTimeoutMs
                    conn.readTimeout = readTimeoutMs
                    // Never auto-follow redirects: a downgrading 307 is surfaced
                    // as its 3xx code and re-validated manually per hop below.
                    conn.instanceFollowRedirects = false
                    conn.setRequestProperty("Accept", "application/json")
                    conn.setRequestProperty("User-Agent", HttpUserAgent.GENERIC)
                    DnsRebindingPolicy.applyPinToConnection(conn, cur.host ?: "", pin.addresses, connectTimeoutMs)
                    val code = conn.responseCode
                    if (code in 300..399) {
                        val next = StrictRedirectPolicy.resolveNextTlsHop(
                            cur, conn.getHeaderField("Location")
                        ) ?: throw DuckDuckGoSearchException(
                            "The search service redirected without a redirect target."
                        )
                        cur = next
                        return@repeat
                    }
                    if (code != 200) {
                        // Drain a little of the error body for a useful-but-safe message.
                        val detail = runCatching {
                            (if (code in 400..599) conn.errorStream else conn.inputStream)
                                ?.bufferedReader()?.use { it.readText(limit = 160) }?.trim()
                        }.getOrNull()
                        val suffix = if (detail.isNullOrBlank()) "" else " — $detail"
                        throw DuckDuckGoSearchException(
                            "The search service returned HTTP $code. Try again later.$suffix"
                        )
                    }
                    val json = conn.inputStream.bufferedReader().use { it.readText(limit = maxResponseBytes) }
                    return DuckDuckGoResponseParser.parse(json)
                } finally {
                    conn.disconnect()
                }
            }
            throw DuckDuckGoSearchException("The search service redirected too many times.")
        } catch (e: StrictRedirectPolicy.RedirectRefusedException) {
            throw DuckDuckGoSearchException(
                e.message ?: "The search service attempted an insecure redirect."
            )
        } catch (e: ResponseTooLargeException) {
            throw DuckDuckGoSearchException("The search service returned an oversized response.")
        } catch (e: IOException) {
            if (e is DuckDuckGoSearchException) throw e
            throw DuckDuckGoSearchException("Unable to reach the search service — check your connection.")
        }
    }

    /** Thrown by [readText] when a source exceeds its read limit. */
    private class ResponseTooLargeException : IOException("Response too large")

    /** Reads at most [limit] characters, throwing if the source is larger.
     *  Guards against an unbounded (runaway) server response. */
    private fun java.io.Reader.readText(limit: Int): String {
        val out = StringBuilder(minOf(limit, 4096))
        val buffer = CharArray(2048)
        var total = 0
        while (true) {
            val read = read(buffer, 0, buffer.size)
            if (read == -1) break
            total += read
            if (total > limit) {
                throw ResponseTooLargeException()
            }
            out.append(buffer, 0, read)
        }
        return out.toString()
    }
}
