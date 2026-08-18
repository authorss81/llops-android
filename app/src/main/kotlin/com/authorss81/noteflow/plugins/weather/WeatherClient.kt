package com.authorss81.noteflow.plugins.weather

import com.authorss81.noteflow.plugins.WeatherSnapshot
import com.authorss81.noteflow.plugins.weather.OpenMeteoGeocoderParser.Place
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
 * Real, keyless HTTP backend for the Weather plugin: the free [Open-Meteo] API
 * (no API key, no GPS). Pure JVM (`java.net` — no new dependency). Runs on
 * `Dispatchers.IO` on device; the URL builders + JSON parsing are unit-tested
 * with sample payloads, never over a real network in unit tests.
 */

/** Builds the Open-Meteo geocoding URL (`/v1/search`). Pure, unit-tested. */
object OpenMeteoGeocodeUrl {
    const val DEFAULT_BASE = "https://geocoding-api.open-meteo.com/v1/search"

    fun build(city: String, count: Int = 1, base: String = DEFAULT_BASE): String {
        val encoded = URLEncoder.encode(city.trim(), "UTF-8")
        return buildString {
            append(base.trimEnd('/'))
            append("?name=").append(encoded)
            append("&count=").append(count)
            append("&language=en")
            append("&format=json")
        }
    }
}

/** Builds the Open-Meteo forecast URL (`/v1/forecast`). Pure, unit-tested. */
object OpenMeteoForecastUrl {
    const val DEFAULT_BASE = "https://api.open-meteo.com/v1/forecast"

    fun build(
        latitude: Double,
        longitude: Double,
        base: String = DEFAULT_BASE
    ): String = buildString {
        append(base.trimEnd('/'))
        append("?latitude=").append(latitude)
        append("&longitude=").append(longitude)
        append("&daily=weather_code,temperature_2m_max,temperature_2m_min,wind_speed_10m_max")
        append("&forecast_days=1")
        append("&timezone=auto")
    }
}

/**
 * HTTP GET client for Open-Meteo (geocoding + forecast). Runs on the caller's
 * thread (call it from `Dispatchers.IO`). Connectivity failures and non-200
 * responses are converted to a clear, user-facing [WeatherServiceException].
 *
 * R2-B1N-02 (phase-144): every hop is additionally RESOLVED and PINNED via
 * [DnsRebindingPolicy] before a connection is opened — a DNS-rebinding answer
 * (an internal A/AAAA, regardless of the textual host string) refuses the whole
 * hop, and the connect is pinned to the validated addresses
 * ([DnsRebindingPolicy.applyPinToConnection]).
 */
class OpenMeteoClient(
    private val geocodeUrlBuilder: (String) -> String = OpenMeteoGeocodeUrl::build,
    private val forecastUrlBuilder: (Double, Double) -> String = OpenMeteoForecastUrl::build,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 10_000,
    private val maxResponseBytes: Int = 1_000_000,
    private val dnsResolver: (String) -> Array<InetAddress> = DnsRebindingPolicy.DEFAULT_RESOLVER,
    private val connectionFactory: (String) -> HttpURLConnection = { url ->
        URL(url).openConnection() as HttpURLConnection
    }
) {

    /** Geocode [city] into coarse coordinates. Null when the city is unknown. */
    fun geocode(city: String): Place? {
        val json = get(geocodeUrlBuilder(city), notFoundMeansNull = true)
            ?: return null
        return OpenMeteoGeocoderParser.parse(json)
    }

    /** Fetch today's forecast for [place]. */
    fun forecast(place: Place): WeatherSnapshot {
        val json = get(forecastUrlBuilder(place.latitude, place.longitude))
            ?: throw WeatherServiceException("The weather service returned an empty response.")
        return OpenMeteoForecastParser.parse(json, place.name)
            ?: throw WeatherServiceException("The weather service returned no forecast for today.")
    }

    private fun get(url: String, notFoundMeansNull: Boolean = false): String? {
        var cur = try {
            URI(url)
        } catch (e: Exception) {
            throw WeatherServiceException("That doesn't look like a valid weather-service URL.")
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
                    is DnsRebindingPolicy.Verdict.Refused -> throw WeatherServiceException(verdict.reason)
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
                        ) ?: throw WeatherServiceException(
                            "The weather service redirected without a redirect target."
                        )
                        cur = next
                        return@repeat
                    }
                    if (code == 404 && notFoundMeansNull) return null
                    if (code != 200) {
                        val detail = runCatching {
                            (if (code in 400..599) conn.errorStream else conn.inputStream)
                                ?.bufferedReader()?.use { it.readText(limit = 160) }?.trim()
                        }.getOrNull()
                        val suffix = if (detail.isNullOrBlank()) "" else " — $detail"
                        throw WeatherServiceException(
                            "The weather service returned HTTP $code. Try again later.$suffix"
                        )
                    }
                    return conn.inputStream.bufferedReader().use { it.readText(limit = maxResponseBytes) }
                } finally {
                    conn.disconnect()
                }
            }
            throw WeatherServiceException("The weather service redirected too many times.")
        } catch (e: StrictRedirectPolicy.RedirectRefusedException) {
            throw WeatherServiceException(
                e.message ?: "The weather service attempted an insecure redirect."
            )
        } catch (e: ResponseTooLargeException) {
            throw WeatherServiceException("The weather service returned an oversized response.")
        } catch (e: IOException) {
            if (e is WeatherServiceException) throw e
            throw WeatherServiceException("Unable to reach the weather service — check your connection.")
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