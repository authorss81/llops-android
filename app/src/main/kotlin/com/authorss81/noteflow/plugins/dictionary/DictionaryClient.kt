package com.authorss81.noteflow.plugins.dictionary

import com.authorss81.noteflow.plugins.DictionaryLookup
import com.authorss81.noteflow.services.DnsRebindingPolicy
import com.authorss81.noteflow.services.StrictRedirectPolicy
import com.authorss81.noteflow.utils.HttpUserAgent
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
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
 *
 * R2-B1N-02 (phase-144): every hop is additionally RESOLVED and PINNED via
 * [DnsRebindingPolicy] before a connection is opened — a DNS-rebinding answer
 * (an internal A/AAAA, regardless of the textual host string) refuses the whole
 * hop, and the connect is pinned to the validated addresses
 * ([DnsRebindingPolicy.applyPinToConnection]).
 */
class DictionaryClient(
    private val urlBuilder: (String) -> String = DictionaryQueryUrl::build,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 10_000,
    private val maxResponseBytes: Int = 1_000_000,
    private val dnsResolver: (String) -> Array<InetAddress> = DnsRebindingPolicy.DEFAULT_RESOLVER,
    private val connectionFactory: (String) -> HttpURLConnection = { url ->
        URL(url).openConnection() as HttpURLConnection
    }
) {

    /** @return the parsed lookup, null when the service says "word not found". */
    fun lookup(word: String): DictionaryLookup? {
        var cur = try {
            URI(urlBuilder(word))
        } catch (e: Exception) {
            throw DictionaryServiceException("That doesn't look like a valid dictionary URL.")
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
                    is DnsRebindingPolicy.Verdict.Refused -> throw DictionaryServiceException(verdict.reason)
                }
                val conn = connectionFactory(cur.toString())
                try {
                    conn.requestMethod = "GET"
                    conn.connectTimeout = connectTimeoutMs
                    conn.readTimeout = readTimeoutMs
                    // Never auto-follow redirects: a downgrading 3xx is surfaced
                    // as its code and re-validated manually per hop below.
                    conn.instanceFollowRedirects = false
                    conn.setRequestProperty("Accept", "application/json")
                    conn.setRequestProperty("User-Agent", HttpUserAgent.GENERIC)
                    DnsRebindingPolicy.applyPinToConnection(conn, cur.host ?: "", pin.addresses, connectTimeoutMs)
                    val code = conn.responseCode
                    if (code in 300..399) {
                        val next = StrictRedirectPolicy.resolveNextTlsHop(
                            cur, conn.getHeaderField("Location")
                        ) ?: throw DictionaryServiceException(
                            "The dictionary service redirected without a redirect target."
                        )
                        cur = next
                        return@repeat
                    }
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
                } finally {
                    conn.disconnect()
                }
            }
            throw DictionaryServiceException("The dictionary service redirected too many times.")
        } catch (e: StrictRedirectPolicy.RedirectRefusedException) {
            throw DictionaryServiceException(
                e.message ?: "The dictionary service attempted an insecure redirect."
            )
        } catch (e: ResponseTooLargeException) {
            throw DictionaryServiceException("The dictionary service returned an oversized response.")
        } catch (e: IOException) {
            if (e is DictionaryServiceException) throw e
            throw DictionaryServiceException("Unable to reach the dictionary service — check your connection.")
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