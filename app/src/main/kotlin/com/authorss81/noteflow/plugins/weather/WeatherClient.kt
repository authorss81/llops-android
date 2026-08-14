package com.authorss81.noteflow.plugins.weather

import com.authorss81.noteflow.plugins.WeatherSnapshot
import com.authorss81.noteflow.plugins.weather.OpenMeteoGeocoderParser.Place
import com.authorss81.noteflow.utils.HttpUserAgent
import java.io.IOException
import java.net.HttpURLConnection
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
 */
class OpenMeteoClient(
    private val geocodeUrlBuilder: (String) -> String = OpenMeteoGeocodeUrl::build,
    private val forecastUrlBuilder: (Double, Double) -> String = OpenMeteoForecastUrl::build,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 10_000,
    private val maxResponseBytes: Int = 1_000_000
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
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", HttpUserAgent.GENERIC)
            val code = conn.responseCode
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
        } catch (e: ResponseTooLargeException) {
            throw WeatherServiceException("The weather service returned an oversized response.")
        } catch (e: IOException) {
            if (e is WeatherServiceException) throw e
            throw WeatherServiceException("Unable to reach the weather service — check your connection.")
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