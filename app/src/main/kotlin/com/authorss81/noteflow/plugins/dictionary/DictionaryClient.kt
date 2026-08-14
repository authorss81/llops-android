package com.authorss81.noteflow.plugins.dictionary

import com.authorss81.noteflow.plugins.DictionaryLookup
import com.authorss81.noteflow.utils.HttpUserAgent
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Real, keyless HTTP backend for the Dictionary plugin: the free
 * [dictionaryapi.dev] REST API (no API key). Pure JVM (`java.net` — no new
 * dependency); the client runs on device on `Dispatchers.IO`, and its URL
 * builder + JSON parsing are unit-tested with sample payloads, never over a
 * real network in unit tests.
 */

/** Builds the `dictionaryapi.dev` lookup URL. Pure, unit-tested. */
object DictionaryQueryUrl {
    const val DEFAULT_BASE = "https://api.dictionaryapi.dev/api/v2/entries/en/"

    fun build(word: String, base: String = DEFAULT_BASE): String {
        val encoded = URLEncoder.encode(word.trim().lowercase(), "UTF-8")
        return buildString {
            append(base.trimEnd('/'))
            append('/').append(encoded)
        }
    }
}

/**
 * HTTP GET client for the dictionary service. Runs the request on the caller's
 * thread (call it from `Dispatchers.IO`). Connectivity failures and non-200
 * responses are converted to a clear, user-facing [DictionaryServiceException] —
 * never silently swallowed. A 404 (unknown word) returns null.
 */
class DictionaryClient(
    private val urlBuilder: (String) -> String = DictionaryQueryUrl::build,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 10_000,
    private val maxResponseBytes: Int = 1_000_000
) {

    /** @return the parsed lookup, null when the service says "word not found". */
    fun lookup(word: String): DictionaryLookup? {
        val conn = URL(urlBuilder(word)).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", HttpUserAgent.GENERIC)
            val code = conn.responseCode
            when {
                code == 404 -> return null
                code != 200 -> {
                    val detail = runCatching {
                        (if (code in 400..599) conn.errorStream else conn.inputStream)
                            ?.bufferedReader()?.use { it.readText(limit = 160) }?.trim()
                    }.getOrNull()
                    val suffix = if (detail.isNullOrBlank()) "" else " — $detail"
                    throw DictionaryServiceException(
                        "The dictionary service returned HTTP $code. Try again later.$suffix"
                    )
                }
            }
            val json = conn.inputStream.bufferedReader().use { it.readText(limit = maxResponseBytes) }
            return DictionaryResponseParser.parse(json, word)
        } catch (e: ResponseTooLargeException) {
            throw DictionaryServiceException("The dictionary service returned an oversized response.")
        } catch (e: IOException) {
            if (e is DictionaryServiceException) throw e
            throw DictionaryServiceException("Unable to reach the dictionary service — check your connection.")
        } finally {
            conn.disconnect()
        }
    }

    /** Thrown by [readText] when a source exceeds its read limit. */
    private class ResponseTooLargeException : IOException("Response too large")

    /** Reads at most [limit] characters, throwing if the source is larger. */
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