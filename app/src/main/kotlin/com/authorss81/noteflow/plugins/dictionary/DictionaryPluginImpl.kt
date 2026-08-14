package com.authorss81.noteflow.plugins.dictionary

import android.content.Context
import com.authorss81.noteflow.plugins.DictionaryLookup
import com.authorss81.noteflow.plugins.DictionaryOutcome
import com.authorss81.noteflow.plugins.DictionaryPlugin
import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.PluginAvailability
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginManifest
import com.authorss81.noteflow.plugins.PluginPermission
import com.authorss81.noteflow.plugins.PluginSettings
import com.authorss81.noteflow.plugins.SemanticVersion
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Dictionary plugin (Phase 26) — serves [PluginCapability.Dictionary].
 *
 * Keyless [dictionaryapi.dev] lookup with an honest OFFLINE fallback to the
 * bundled [OfflineWordList], so a lookup genuinely works with no network (the
 * result is labelled `source = offline`). Lookups are strictly user-initiated;
 * all network work runs on `Dispatchers.IO`.
 *
 * - Serves the `Dictionary` capability via [DictionaryPlugin].
 * - `availability()` is always `Ok` — the offline fallback means the plugin
 *   never requires a network to serve *something* (online simply enriches).
 * - Opt-in off by default; toggle in Settings → Plugins / the Plugin Store.
 * - No new permissions beyond INTERNET (already granted for the app's existing
 *   keyless HTTP plugins). No GPS, no device location.
 */
class DictionaryPluginImpl(
    private val client: (String) -> DictionaryLookup? = { word ->
        DictionaryClient().lookup(word)
    }
) : NoteflowPlugin, DictionaryPlugin {

    override val manifest = PluginManifest(
        id = ID,
        name = "Dictionary",
        version = SemanticVersion(1, 0, 0),
        minSupportedApi = MIN_API,
        description = "Looks up a word's definition via the keyless dictionaryapi.dev API, with a bundled offline fallback.",
        capabilities = setOf(PluginCapability.Dictionary),
        permissions = setOf(PluginPermission.Internet)
    )

    override fun availability(context: Context?): PluginAvailability = PluginAvailability.Ok

    override fun onEnable(context: Context?, settings: PluginSettings) {}

    override suspend fun lookupWord(word: String): DictionaryOutcome {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) {
            return DictionaryOutcome.Error("Enter a word to look up first.")
        }
        // 1. Online, keyless dictionaryapi.dev (on Dispatchers.IO).
        val online = try {
            withContext(Dispatchers.IO) { client(trimmed) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DictionaryServiceException) {
            null // network/service failure → fall back offline below
        } catch (e: IOException) {
            null
        }
        if (online != null) return DictionaryOutcome.Success(online)

        // 2. Offline fallback: bundled word list. Always works, never fake.
        val offline = OfflineWordList.lookup(trimmed)
        if (offline != null) return DictionaryOutcome.Success(offline)

        // 3. Honest miss — never pretend to know a word we don't.
        return DictionaryOutcome.NotFound(
            "No definition found for \"$trimmed\" — online and in the bundled offline word list."
        )
    }

    companion object {
        const val MIN_API = 26
        const val ID = "com.authorss81.noteflow.plugins.dictionary"
    }
}