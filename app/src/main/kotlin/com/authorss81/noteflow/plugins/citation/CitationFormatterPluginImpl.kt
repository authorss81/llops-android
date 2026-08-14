package com.authorss81.noteflow.plugins.citation

import android.content.Context
import com.authorss81.noteflow.plugins.CitationOutcome
import com.authorss81.noteflow.plugins.CitationPlugin
import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.PluginAvailability
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginManifest
import com.authorss81.noteflow.plugins.PluginPermission
import com.authorss81.noteflow.plugins.PluginSettings
import com.authorss81.noteflow.plugins.SemanticVersion
import com.authorss81.noteflow.plugins.citation.CitationFormatterCore.UrlCheck
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Citation Formatter plugin (Phase 26) — serves
 * [PluginCapability.CitationFormatter].
 *
 * Formats a pasted URL (and optional title) into a clean Markdown `[title](url)`
 * link. When no title is given the plugin fetches the page's `<title>` over
 * HTTPS and — on ANY network failure or missing title — honestly falls back to
 * a host-derived label (`[example.com](https://example.com/...)`), never a
 * fabricated title.
 *
 * - Serves the `CitationFormatter` capability via [CitationPlugin].
 * - `availability()` is always `Ok` — the fallback means it can always produce
 *   a valid citation offline (title fetching simply enriches).
 * - Opt-in off by default; toggle in Settings → Plugins / the Plugin Store.
 * - All network runs on `Dispatchers.IO`, strictly user-initiated.
 *
 * @param titleFetcher the title backend; production uses [HttpsTitleFetcher].
 */
class CitationFormatterPluginImpl(
    private val titleFetcher: (String) -> String? = { url -> HttpsTitleFetcher().fetch(url) }
) : NoteflowPlugin, CitationPlugin {

    override val manifest = PluginManifest(
        id = ID,
        name = "Citation Formatter",
        version = SemanticVersion(1, 0, 0),
        minSupportedApi = MIN_API,
        description = "Formats a pasted URL into a clean Markdown [title](url) link, fetching the page title over HTTPS.",
        capabilities = setOf(PluginCapability.CitationFormatter),
        permissions = setOf(PluginPermission.Internet)
    )

    override fun availability(context: Context?): PluginAvailability = PluginAvailability.Ok

    override fun onEnable(context: Context?, settings: PluginSettings) {}

    override suspend fun formatCitation(url: String, title: String?): CitationOutcome {
        val check = CitationFormatterCore.validateUrl(url)
        if (check is UrlCheck.Invalid) {
            return CitationOutcome.Error(check.reason)
        }
        val validatedUrl = (check as UrlCheck.Valid).url

        val supplied = title?.trim().orEmpty()
        if (supplied.isNotEmpty()) {
            // User-supplied title: no network needed at all.
            return CitationOutcome.Success(
                markdown = CitationFormatterCore.buildCitation(validatedUrl, supplied),
                titleFetched = false
            )
        }
        // No title given — try HTTPS fetch, then an honest host fallback.
        val fetched = try {
            withContext(Dispatchers.IO) { titleFetcher(validatedUrl) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        }
        return CitationOutcome.Success(
            markdown = CitationFormatterCore.buildCitation(validatedUrl, fetched),
            titleFetched = fetched != null
        )
    }

    companion object {
        const val MIN_API = 26
        const val ID = "com.authorss81.noteflow.plugins.citation"
    }
}