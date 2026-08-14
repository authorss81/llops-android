package com.authorss81.noteflow.plugins.runtime

/**
 * Persistence seam for the Phase-24 update's rollback path.
 *
 * The cardinal update rule (`docs/plugin-architecture.md` § State machine): the
 * PREVIOUS verified version is never discarded until the new one is verified.
 * [PluginUpdateEngine] records the previously-active [PluginEntry] HERE before
 * it downloads the new artifact, and [PluginRuntime.rollback] restores it from
 * here — so a failed update (hash/signature/load) or even a mid-update process
 * death always leaves a recoverable root: the old entry + its on-disk artifact.
 *
 * Pure JVM (in-memory impl unit-tested); the production adapter
 * ([com.authorss81.noteflow.services.SettingsPluginUpdateStore]) persists the
 * encoded entry through [com.authorss81.noteflow.services.SettingsManager].
 * The store's Delete wipes it too, so a deleted plugin leaves no update residue.
 */
interface PluginUpdateStore {

    /** Remember [entry] as the plugin's previously-active version. */
    fun savePrevious(entry: PluginEntry)

    /** The recorded previously-active entry for [pluginId], or null. */
    fun previousFor(pluginId: String): PluginEntry?

    /** Forget the previous-version record (after a successful rollback). */
    fun clearPrevious(pluginId: String)
}

/** In-memory [PluginUpdateStore] for JVM tests. */
class InMemoryPluginUpdateStore : PluginUpdateStore {
    private val previous = mutableMapOf<String, PluginEntry>()

    override fun savePrevious(entry: PluginEntry) {
        previous[entry.id] = entry
    }

    override fun previousFor(pluginId: String): PluginEntry? = previous[pluginId]

    override fun clearPrevious(pluginId: String) {
        previous.remove(pluginId)
    }
}