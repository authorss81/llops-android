package com.authorss81.noteflow.plugins.websearch

import com.authorss81.noteflow.plugins.WebSearchResult
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.IOException
import java.net.HttpURLConnection
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
 */
class DuckDuckGoClient(
    private val urlBuilder: (String) -> String = DuckDuckGoQueryUrl::build,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 10_000
) {

    fun search(query: String): List<WebSearchResult> {
        val conn = URL(urlBuilder(query)).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code != 200) {
                // Drain a little of the error body for a useful-but-safe message.
                val detail = runCatching {
                    (if (code in 400..599) conn.errorStream else conn.inputStream)
                        ?.bufferedReader()?.use { it.readText() }?.take(160)?.trim()
                }.getOrNull()
                val suffix = if (detail.isNullOrBlank()) "" else " — $detail"
                throw DuckDuckGoSearchException(
                    "The search service returned HTTP $code. Try again later.$suffix"
                )
            }
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            return DuckDuckGoResponseParser.parse(json)
        } catch (e: IOException) {
            if (e is DuckDuckGoSearchException) throw e
            throw DuckDuckGoSearchException("Unable to reach the search service — check your connection.")
        } finally {
            conn.disconnect()
        }
    }
}
