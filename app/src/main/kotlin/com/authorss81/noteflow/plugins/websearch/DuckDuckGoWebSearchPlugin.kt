package com.authorss81.noteflow.plugins.websearch

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
import com.authorss81.noteflow.plugins.WebSearchOutcome
import com.authorss81.noteflow.plugins.WebSearchPlugin
import com.authorss81.noteflow.plugins.WebSearchResult
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The real web-search plugin (Phase 12).
 *
 * Serves the [PluginCapability.WebSearch] capability via the framework
 * [WebSearchPlugin] serving interface, backed by the keyless DuckDuckGo Instant
 * Answer API. Results are real; a connectivity failure returns
 * [WebSearchOutcome.Error] with a clear "offline — check connection" message —
 * it never silently degrades.
 *
 * Availability is live and honest: `availability(context)` reflects INTERNET
 * permission presence AND an active network with INTERNET capability, so the
 * derived lifecycle state flips to UNAVAILABLE the moment the device goes
 * offline and recovers automatically when connectivity returns.
 *
 * All network work runs on `Dispatchers.IO`. [searchImpl] is injectable so JVM
 * tests can assert routing + error handling with no network.
 *
 * @param searchImpl the search backend; production uses the DDG client.
 */
class DuckDuckGoWebSearchPlugin(
    private val searchImpl: (String) -> List<WebSearchResult> = { query ->
        DuckDuckGoClient().search(query)
    }
) : NoteflowPlugin, WebSearchPlugin {

    override val manifest = PluginManifest(
        id = "com.authorss81.noteflow.plugins.websearch",
        name = "DuckDuckGo Web Search",
        version = SemanticVersion(1, 0, 0),
        minSupportedApi = 26,
        description = "Searches the web via the keyless DuckDuckGo Instant Answer API and inserts [title](url) links.",
        capabilities = setOf(PluginCapability.WebSearch),
        permissions = setOf(PluginPermission.Internet)
    )

    override fun availability(context: Context?): PluginAvailability {
        val ctx = context ?: return PluginAvailability.Unknown
        return WebSearchAvailability.evaluate(
            internetPermissionGranted = hasInternetPermission(ctx),
            networkAvailable = hasActiveNetwork(ctx)
        )
    }

    override fun onEnable(context: Context?, settings: PluginSettings) {
        // Documented settings-schema migration (see docs/PLUGIN_SDK.md § 4).
        val schema = settings.getInt("settings_schema", default = 0)
        if (schema < 1) {
            settings.setInt("result_limit", 8)
            settings.setInt("settings_schema", 1)
        }
    }

    override suspend fun searchWeb(query: String): WebSearchOutcome {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return WebSearchOutcome.Error("Enter a search query first.")
        }
        return try {
            val results = withContext(Dispatchers.IO) { searchImpl(trimmed) }
            WebSearchOutcome.Success(results)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            WebSearchOutcome.Error(
                (e as? DuckDuckGoSearchException)?.message
                    ?: "Unable to reach the search service — check your connection."
            )
        }
    }

    // ---- internals ---------------------------------------------------------

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
}
