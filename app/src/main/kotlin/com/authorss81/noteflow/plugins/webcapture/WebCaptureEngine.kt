package com.authorss81.noteflow.plugins.webcapture

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.PluginAvailability
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginManifest
import com.authorss81.noteflow.plugins.PluginSettings
import com.authorss81.noteflow.plugins.SemanticVersion
import com.authorss81.noteflow.plugins.WebCaptureOutcome
import com.authorss81.noteflow.plugins.WebCapturePlugin
import com.authorss81.noteflow.plugins.WebCaptureResult
import com.authorss81.noteflow.plugins.websearch.WebSearchAvailability

/**
 * Web Page -> Markdown capture plugin (Phase 15, capability `WebCapture`).
 *
 * Fetches an http(s) page, extracts the readable content with
 * [WebToMarkdownExtractor], and returns the result as Markdown for a new-note
 * capture. Fetching only ever runs on a user-initiated action and hops off the
 * main thread via [WebPageFetcher].
 */
class WebCaptureEngine(
    private val fetcher: WebPageFetcher = WebPageFetcher()
) : NoteflowPlugin, WebCapturePlugin {

    override val manifest = PluginManifest(
        id = "com.authorss81.noteflow.plugins.webcapture",
        name = "Web Capture",
        version = SemanticVersion(1, 0, 0),
        minSupportedApi = 26,
        description = "Captures any web page as a clean Markdown note (offline extraction via jsoup).",
        capabilities = setOf(PluginCapability.WebCapture)
    )

    override fun availability(context: Context?): PluginAvailability =
        if (context == null) {
            WebSearchAvailability.evaluate(internetPermissionGranted = true, networkAvailable = false)
        } else {
            WebSearchAvailability.evaluate(
                internetPermissionGranted = internetGranted(context),
                networkAvailable = hasActiveNetwork(context)
            )
        }

    override fun onEnable(context: Context?, settings: PluginSettings) {
        // Default: show the captured preview before saving (user can always
        // save directly). Pure UX preference, no side effects elsewhere.
        if (!settings.containsKey("confirm_before_save")) {
            settings.setBoolean("confirm_before_save", true)
        }
    }

    override suspend fun captureWebPage(context: Context?, url: String): WebCaptureOutcome {
        when (val v = WebPageFetchPolicy.validateUrl(url)) {
            is WebPageFetchPolicy.Either.Error -> return WebCaptureOutcome.Error(v.message)
            is WebPageFetchPolicy.Either.Valid -> Unit
        }
        if (context != null && !hasActiveNetwork(context)) {
            return WebCaptureOutcome.Error("Offline — check your connection.")
        }
        return fetcher.fetch(url).fold(
            onSuccess = { html ->
                val extracted = WebToMarkdownExtractor.extract(html, url)
                if (extracted.markdown.isBlank()) {
                    WebCaptureOutcome.Error("The page has no readable content to capture.")
                } else {
                    WebCaptureOutcome.Success(
                        WebCaptureResult(
                            title = extracted.title.ifBlank { "Captured Web Page" },
                            markdown = extracted.markdown
                        )
                    )
                }
            },
            onFailure = { t ->
                WebCaptureOutcome.Error(t.message ?: "Could not capture that page.")
            }
        )
    }

    private fun internetGranted(context: Context): Boolean =
        runCatching {
            context.checkSelfPermission(android.Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    private fun hasActiveNetwork(context: Context): Boolean =
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }.getOrDefault(false)
}