package com.authorss81.noteflow.plugins.weather

import com.authorss81.noteflow.plugins.WeatherSnapshot
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * Pure-JVM weather core: parses the keyless [Open-Meteo](https://open-meteo.com/)
 * forecast + geocoding JSON payloads and formats a dated snapshot. Nothing here
 * touches Android or the network — the parsing/formatting is unit-tested with
 * sample payloads.
 */

/** Thrown when the weather service returns an unusable payload. */
class WeatherServiceException(message: String) : java.io.IOException(message)

/** WMO weather-interpretation codes → short, user-facing descriptions. */
object WmoWeatherCode {

    /** @return a human description for a WMO code, or "Unknown" for unused ones. */
    fun description(code: Int): String = when (code) {
        0 -> "Clear sky"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45 -> "Fog"
        48 -> "Depositing rime fog"
        51 -> "Light drizzle"
        53 -> "Moderate drizzle"
        55 -> "Dense drizzle"
        56 -> "Light freezing drizzle"
        57 -> "Dense freezing drizzle"
        61 -> "Slight rain"
        63 -> "Moderate rain"
        65 -> "Heavy rain"
        66 -> "Light freezing rain"
        67 -> "Heavy freezing rain"
        71 -> "Slight snow"
        73 -> "Moderate snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80 -> "Slight rain showers"
        81 -> "Moderate rain showers"
        82 -> "Violent rain showers"
        85 -> "Slight snow showers"
        86 -> "Heavy snow showers"
        95 -> "Thunderstorm"
        96 -> "Thunderstorm with slight hail"
        99 -> "Thunderstorm with heavy hail"
        else -> "Unknown"
    }
}

/** Parses an Open-Meteo FORECAST payload (the `/v1/forecast` endpoint). */
object OpenMeteoForecastParser {

    private val gson = Gson()

    /**
     * @param json the forecast JSON (daily time/weather_code/temperature/wind arrays).
     * @param city display name for the snapshot.
     * @param locationProvenance provenance of the location ("Default city" vs
     *   "Configured location") — supplied by the caller, which knows the config
     *   path actually used. NEVER derived from the city name.
     * @return the FIRST forecast day, or null for a blank/empty payload.
     * @throws WeatherServiceException on malformed JSON.
     */
    fun parse(json: String, city: String, locationProvenance: String = "Configured location"): WeatherSnapshot? {
        if (json.isBlank()) return null
        val forecast: RawForecast = try {
            gson.fromJson(json, RawForecast::class.java)
        } catch (e: JsonSyntaxException) {
            throw WeatherServiceException("The weather service returned an unreadable response.")
        }
        val daily = forecast.daily ?: return null
        val time = daily.time.orEmpty()
        val codes = daily.weather_code.orEmpty()
        val max = daily.temperature_2m_max.orEmpty()
        val min = daily.temperature_2m_min.orEmpty()
        val wind = daily.wind_speed_10m_max.orEmpty()
        if (time.isEmpty() || max.isEmpty() || min.isEmpty()) return null

        val date = time.first()
        val code = codes.firstOrNull() ?: 0
        return WeatherSnapshot(
            date = date,
            city = city,
            tempMinC = min.first(),
            tempMaxC = max.first(),
            weatherCode = code,
            weatherDescription = WmoWeatherCode.description(code),
windSpeedKmh = wind.firstOrNull() ?: 0.0,
            sourceNote = locationProvenance
        )
    }

    /** Minimal Open-Meteo forecast shape (unknown fields ignored). */
    data class RawForecast(
        val latitude: Double? = null,
        val longitude: Double? = null,
        val daily: Daily? = null
    )

    data class Daily(
        val time: List<String>? = null,
        val weather_code: List<Int>? = null,
        val temperature_2m_max: List<Double>? = null,
        val temperature_2m_min: List<Double>? = null,
        val wind_speed_10m_max: List<Double>? = null
    )
}

/** Parses an Open-Meteo GEOCODING payload (the `/v1/search` endpoint). */
object OpenMeteoGeocoderParser {

    private val gson = Gson()

    /** A geocoded place: display name + coarse coordinates. */
    data class Place(val name: String, val latitude: Double, val longitude: Double)

    /** @return the FIRST matching place, or null when there are no results. */
    fun parse(json: String): Place? {
        if (json.isBlank()) return null
        val response: RawResponse = try {
            gson.fromJson(json, RawResponse::class.java)
        } catch (e: JsonSyntaxException) {
            throw WeatherServiceException("The weather service returned an unreadable response.")
        }
        val result = response.results.orEmpty().firstOrNull() ?: return null
        val lat = result.latitude ?: return null
        val lon = result.longitude ?: return null
        val name = result.name?.takeIf { it.isNotBlank() } ?: "Unknown location"
        return Place(name, lat, lon)
    }

    /** Minimal Open-Meteo geocoding shape. */
    data class RawResponse(val results: List<RawResult>? = null)

    data class RawResult(
        val name: String? = null,
        val latitude: Double? = null,
        val longitude: Double? = null
    )
}

/** Fixed defaults for the location-free weather plugin (NO GPS — see the doc). */
object WeatherDefaults {
    const val DEFAULT_CITY = "London"
    const val DEFAULT_LATITUDE = 51.5072
    const val DEFAULT_LONGITUDE = -0.1276
    const val DEFAULT_LOCATION_NAME = "London, United Kingdom"
}

/** Formats a [WeatherSnapshot] into the dated text inserted into a note. Pure. */
object WeatherSnapshotFormatter {
    fun toNoteText(snapshot: WeatherSnapshot): String = buildString {
        append("Weather · ").append(snapshot.city).append(" — ").append(snapshot.date)
        appendLine()
        append(
            "Max %.1f°C / Min %.1f°C · %s · Wind %.0f km/h"
                .format(snapshot.tempMaxC, snapshot.tempMinC, snapshot.weatherDescription, snapshot.windSpeedKmh)
        )
    }
}