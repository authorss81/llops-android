package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.PluginAvailability
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginPermission
import com.authorss81.noteflow.plugins.PluginManager
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.PluginResult
import com.authorss81.noteflow.plugins.WeatherOutcome
import com.authorss81.noteflow.plugins.WeatherPlugin
import com.authorss81.noteflow.plugins.WeatherSnapshot
import com.authorss81.noteflow.plugins.weather.OpenMeteoForecastParser
import com.authorss81.noteflow.plugins.weather.OpenMeteoForecastUrl
import com.authorss81.noteflow.plugins.weather.OpenMeteoGeocodeUrl
import com.authorss81.noteflow.plugins.weather.OpenMeteoGeocoderParser
import com.authorss81.noteflow.plugins.weather.OpenMeteoGeocoderParser.Place
import com.authorss81.noteflow.plugins.weather.WeatherAvailability
import com.authorss81.noteflow.plugins.weather.WeatherBackend
import com.authorss81.noteflow.plugins.weather.WeatherDefaults
import com.authorss81.noteflow.plugins.weather.WeatherPluginImpl
import com.authorss81.noteflow.plugins.weather.WeatherSnapshotFormatter
import com.authorss81.noteflow.plugins.weather.WmoWeatherCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 26 Weather plugin tests (pure JVM, no network).
 *
 * Covers the real Open-Meteo forecast + geocoding JSON parsing, WMO code
 * mapping, dated-snapshot formatting, URL building, the offline availability
 * gate, and plugin behaviour with an injected weather backend (success,
 * offline error, unknown city, exact defaults).
 */
class WeatherPluginTest {

    private val forecastJson = """
        {
          "latitude": 51.5,
          "longitude": -0.12,
          "daily": {
            "time": ["2026-08-14", "2026-08-15"],
            "weather_code": [2, 3],
            "temperature_2m_max": [22.3, 21.1],
            "temperature_2m_min": [13.1, 12.4],
            "wind_speed_10m_max": [17.2, 9.4]
          }
        }
    """.trimIndent()

    private val geocodeJson = """
        {
          "results": [
            {"id": 2643743, "name": "London", "latitude": 51.50853, "longitude": -0.12574,
             "country": "United Kingdom", "admin1": "England"},
            {"id": 3936456, "name": "London", "latitude": 42.98, "longitude": -81.24,
             "country": "Canada"}
          ]
        }
    """.trimIndent()

    // ---- forecast JSON parse ----------------------------------------------

    @Test
    fun `parser extracts the first forecast day from a sample payload`() {
        val snapshot = OpenMeteoForecastParser.parse(forecastJson, WeatherDefaults.DEFAULT_CITY)
        assertNotNull(snapshot)
        snapshot!!
        assertEquals("2026-08-14", snapshot.date)
        assertEquals(WeatherDefaults.DEFAULT_CITY, snapshot.city)
        assertEquals(22.3, snapshot.tempMaxC, 0.0001)
        assertEquals(13.1, snapshot.tempMinC, 0.0001)
        assertEquals(2, snapshot.weatherCode)
        assertEquals("Partly cloudy", snapshot.weatherDescription)
        assertEquals(17.2, snapshot.windSpeedKmh, 0.0001)
    }

    @Test
    fun `parser handles blank or empty payloads as no result`() {
        assertNull(OpenMeteoForecastParser.parse("", "London"))
        assertNull(OpenMeteoForecastParser.parse("{}", "London"))
        assertNull(OpenMeteoForecastParser.parse("""{"daily":{"time":[]}}""", "London"))
    }

    @Test
    fun `parser converts malformed json into a typed user-facing error`() {
        try {
            OpenMeteoForecastParser.parse("{ not json", "London")
            fail("malformed JSON must fail loudly")
        } catch (e: com.authorss81.noteflow.plugins.weather.WeatherServiceException) {
            assertTrue(e.message.orEmpty().isNotBlank())
        }
    }

    // ---- WMO codes & snapshot formatting -----------------------------------

    @Test
    fun `wmo code maps to a user-facing description`() {
        assertEquals("Clear sky", WmoWeatherCode.description(0))
        assertEquals("Overcast", WmoWeatherCode.description(3))
        assertEquals("Slight rain", WmoWeatherCode.description(61))
        assertEquals("Thunderstorm", WmoWeatherCode.description(95))
        assertEquals("Unknown", WmoWeatherCode.description(9_999))
    }

    @Test
    fun `snapshot formatter produces a dated, insertable text`() {
        val snapshot = OpenMeteoForecastParser.parse(forecastJson, "Berlin")!!
        val text = WeatherSnapshotFormatter.toNoteText(snapshot)
        assertTrue(text.contains("Weather · Berlin — 2026-08-14"))
        assertTrue(text.contains("Max 22.3°C / Min 13.1°C"))
        assertTrue(text.contains("Partly cloudy"))
    }

    // ---- geocoding parse & URL building ------------------------------------

    @Test
    fun `geocoder returns the first matching place`() {
        val place = OpenMeteoGeocoderParser.parse(geocodeJson)
        assertNotNull(place)
        assertEquals("London", place!!.name)
        assertEquals(51.50853, place.latitude, 0.0001)
        assertEquals(-0.12574, place.longitude, 0.0001)
    }

    @Test
    fun `geocoder returns null when no result exists`() {
        assertNull(OpenMeteoGeocoderParser.parse("""{"results":[]}"""))
        assertNull(OpenMeteoGeocoderParser.parse(""))
    }

    @Test
    fun `geocoder converts malformed json into a typed error`() {
        try {
            OpenMeteoGeocoderParser.parse("nope")
            fail("malformed JSON must fail loudly")
        } catch (e: com.authorss81.noteflow.plugins.weather.WeatherServiceException) {
            assertTrue(e.message.orEmpty().isNotBlank())
        }
    }

    @Test
    fun `forecast url includes coords and the daily variables`() {
        val url = OpenMeteoForecastUrl.build(51.5, -0.12)
        assertTrue(url.startsWith(OpenMeteoForecastUrl.DEFAULT_BASE.trimEnd('/')))
        assertTrue(url.contains("latitude=51.5"))
        assertTrue(url.contains("longitude=-0.12"))
        assertTrue(url.contains("daily="))
        assertTrue(url.contains("forecast_days=1"))
    }

    @Test
    fun `geocode url encodes the city`() {
        val url = OpenMeteoGeocodeUrl.build("New York")
        assertTrue(url.startsWith(OpenMeteoGeocodeUrl.DEFAULT_BASE.trimEnd('/')))
        assertTrue(url.contains("name=New+York"))
    }

    // ---- availability gate ---------------------------------------------------

    @Test
    fun `availability ok when permission granted and network present`() {
        assertEquals(PluginAvailability.Ok, WeatherAvailability.evaluate(true, true))
    }

    @Test
    fun `availability offline when no network`() {
        val offline = WeatherAvailability.evaluate(true, false)
        assertTrue(offline is PluginAvailability.Unavailable)
        assertTrue((offline as PluginAvailability.Unavailable).reason.contains("Offline"))
    }

    // ---- plugin behaviour (injected backend, no network) -------------------

    private val fakeBackend = object : WeatherBackend {
        override fun geocode(city: String): Place? =
            if (city.contains("london", ignoreCase = true)) Place("London", 51.50853, -0.12574) else null

        override fun forecast(place: Place): WeatherSnapshot =
            OpenMeteoForecastParser.parse(forecastJson, place.name)!!
    }

    @Test
    fun `plugin returns a dated snapshot for the default city`() = runBlocking {
        val plugin = WeatherPluginImpl(network = fakeBackend)
        val outcome = plugin.currentWeather()
        assertTrue(outcome is WeatherOutcome.Success)
        val snapshot = (outcome as WeatherOutcome.Success).snapshot
        assertEquals("2026-08-14", snapshot.date)
        assertEquals(WeatherDefaults.DEFAULT_CITY, snapshot.city)
    }

    @Test
    fun `plugin maps a weather service failure to a clear error`() = runBlocking {
        val backend = object : WeatherBackend {
            override fun geocode(city: String): Place? = null
            override fun forecast(place: Place): WeatherSnapshot =
                throw com.authorss81.noteflow.plugins.weather.WeatherServiceException("the network is down")
        }
        val plugin = WeatherPluginImpl(network = backend)
        val outcome = plugin.currentWeather()
        assertTrue(outcome is WeatherOutcome.Error)
        assertTrue((outcome as WeatherOutcome.Error).message.isNotBlank())
    }

    @Test
    fun `plugin maps an io failure to a clear error`() = runBlocking {
        val backend = object : WeatherBackend {
            override fun geocode(city: String): Place? = Place("London", 0.0, 0.0)
            override fun forecast(place: Place): WeatherSnapshot =
                throw java.io.IOException("connection reset")
        }
        val plugin = WeatherPluginImpl(network = backend)
        val outcome = plugin.currentWeather()
        assertTrue(outcome is WeatherOutcome.Error)
        assertTrue((outcome as WeatherOutcome.Error).message.contains("connection"))
    }

    @Test
    fun `plugin routes through the manager with the weather capability`() = runBlocking {
        val plugin = WeatherPluginImpl(network = fakeBackend)
        val registry = PluginRegistry(
            InMemoryEnableStore(),
            plugins = listOf(plugin),
            currentApiLevel = 26
        )
        registry.setEnabled(plugin.id, enabled = true)
        val manager = PluginManager(registry)
        val result = manager.withPluginAsync(PluginCapability.Weather, null) { p ->
            (p as WeatherPlugin).currentWeather()
        }
        assertTrue(result is PluginResult.Success)
    }

    // ---- manifest -----------------------------------------------------------

    @Test
    fun `manifest declares weather capability and internet permission`() {
        val plugin = WeatherPluginImpl()
        assertTrue(PluginCapability.Weather in plugin.capabilities)
        assertTrue(PluginPermission.Internet in plugin.manifest.permissions)
        assertTrue(plugin.id.startsWith("com.authorss81.noteflow.plugins.weather"))
    }
}