package com.authorss81.noteflow.plugins.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.PluginAvailability
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginManifest
import com.authorss81.noteflow.plugins.PluginPermission
import com.authorss81.noteflow.plugins.PluginSettings
import com.authorss81.noteflow.plugins.SemanticVersion
import com.authorss81.noteflow.plugins.WeatherOutcome
import com.authorss81.noteflow.plugins.WeatherPlugin
import com.authorss81.noteflow.plugins.WeatherSnapshot
import com.authorss81.noteflow.plugins.weather.OpenMeteoGeocoderParser.Place
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Weather plugin (Phase 26) — serves [PluginCapability.Weather].
 *
 * A DATED weather snapshot from the keyless [Open-Meteo](https://open-meteo.com/)
 * API (no API key, no GPS permission). Location-free by default: the fixed
 * default city is London; the user may configure a different city or coarse
 * lat/lon via the plugin's namespaced settings — the device's location is NEVER
 * read.
 *
 * - Serves the `Weather` capability via [WeatherPlugin].
 * - `availability(context)` honestly reflects the network: the derived state
 *   flips to UNAVAILABLE the moment the device goes offline.
 * - Opt-in off by default; toggle in Settings → Plugins / the Plugin Store.
 * - All network runs on `Dispatchers.IO`, strictly user-initiated.
 *
 * @param network the weather backend; production uses [OpenMeteoClient].
 */
class WeatherPluginImpl(
    private val network: WeatherBackend = WeatherBackend.OpenMeteo
) : NoteflowPlugin, WeatherPlugin {

    override val manifest = PluginManifest(
        id = ID,
        name = "Weather",
        version = SemanticVersion(1, 0, 0),
        minSupportedApi = MIN_API,
        description = "Inserts a dated weather snapshot (keyless Open-Meteo, no GPS) into the note.",
        capabilities = setOf(PluginCapability.Weather),
        permissions = setOf(PluginPermission.Internet)
    )

    @Volatile
    private var settings: PluginSettings? = null

    override fun availability(context: Context?): PluginAvailability {
        val ctx = context ?: return PluginAvailability.Ok
        return WeatherAvailability.evaluate(
            internetPermissionGranted = hasInternetPermission(ctx),
            networkAvailable = hasActiveNetwork(ctx)
        )
    }

    override fun onEnable(context: Context?, settings: PluginSettings) {
        this.settings = settings
    }

    override fun onDisable(context: Context?, settings: PluginSettings) {
        this.settings = null
    }

    override fun onConfigChanged(context: Context?, settings: PluginSettings) {
        this.settings = settings
    }

    override suspend fun currentWeather(): WeatherOutcome {
        val explicitLat = settings?.getString(SETTING_LATITUDE)?.toDoubleOrNull()
        val explicitLon = settings?.getString(SETTING_LONGITUDE)?.toDoubleOrNull()
        val customCity = settings?.getString(SETTING_CITY)?.takeIf { it.isNotBlank() }
        return try {
            val snapshot: WeatherSnapshot = withContext(Dispatchers.IO) {
                when {
                    // Explicit coarse lat/lon from settings — no geocoding at all.
                    explicitLat != null && explicitLon != null -> {
                        val label = settings?.getString(SETTING_LOCATION_NAME)
                            ?.takeIf { it.isNotBlank() }
                            ?: WeatherDefaults.DEFAULT_LOCATION_NAME
                        network.forecast(Place(label, explicitLat, explicitLon))
                    }
                    // User-configured city → geocode via keyless Open-Meteo search.
                    customCity != null -> {
                        val geocoded = network.geocode(customCity)
                            ?: throw WeatherServiceException(
                                "The city \"$customCity\" was not found. Configure the latitude/longitude " +
                                    "settings to use a custom location."
                            )
                        network.forecast(Place(geocoded.name, geocoded.latitude, geocoded.longitude))
                    }
                    // Default city: fixed coordinates, no geocoding needed.
                    else -> network.forecast(
                        Place(
                            WeatherDefaults.DEFAULT_CITY,
                            WeatherDefaults.DEFAULT_LATITUDE,
                            WeatherDefaults.DEFAULT_LONGITUDE
                        )
                    )
                }
            }
            WeatherOutcome.Success(snapshot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: WeatherServiceException) {
            WeatherOutcome.Error(e.message ?: "Unable to fetch the weather.")
        } catch (e: IOException) {
            WeatherOutcome.Error("Unable to fetch the weather — check your connection.")
        }
    }

    private fun hasInternetPermission(context: Context): Boolean =
        context.packageManager.checkPermission(
            Manifest.permission.INTERNET, context.packageName
        ) == PackageManager.PERMISSION_GRANTED

    private fun hasActiveNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        const val MIN_API = 26
        const val ID = "com.authorss81.noteflow.plugins.weather"

        /** Namespaced settings keys (all OPTIONAL — defaults are fixed). */
        const val SETTING_CITY = "city"
        const val SETTING_LATITUDE = "latitude"
        const val SETTING_LONGITUDE = "longitude"
        const val SETTING_LOCATION_NAME = "locationName"
    }
}

/** Injected weather backend (fake in unit tests, [OpenMeteoClient] in production). */
interface WeatherBackend {
    fun geocode(city: String): Place?
    fun forecast(place: Place): WeatherSnapshot

    object OpenMeteo : WeatherBackend {
        private val client = OpenMeteoClient()
        override fun geocode(city: String): Place? = client.geocode(city)
        override fun forecast(place: Place): WeatherSnapshot = client.forecast(place)
    }
}