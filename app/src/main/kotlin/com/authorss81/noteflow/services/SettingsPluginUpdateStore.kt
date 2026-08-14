package com.authorss81.noteflow.services

import com.authorss81.noteflow.plugins.runtime.PluginEntry
import com.authorss81.noteflow.plugins.runtime.PluginEntryCodec
import com.authorss81.noteflow.plugins.runtime.PluginUpdateStore

/**
 * Production [PluginUpdateStore] (Phase 24) — the update flow's rollback root
 * persisted through [SettingsManager] under `plugin_update_previous_<id>` (one
 * encoded [PluginEntry] blob, same codec as the active-entry store).
 *
 * The record is written before any update bytes move and wiped on store Delete
 * (`SettingsManager.wipePluginState`), so a deleted plugin leaves no update
 * residue. Serialization lives in the pure-JVM [PluginEntryCodec]; this adapter
 * is a thin Android-only read/write shell.
 */
class SettingsPluginUpdateStore(
    private val settings: SettingsManager,
    private val codec: PluginEntryCodec = PluginEntryCodec()
) : PluginUpdateStore {

    override fun savePrevious(entry: PluginEntry) {
        settings.setPluginUpdatePreviousJson(entry.id, codec.encode(entry))
    }

    override fun previousFor(pluginId: String): PluginEntry? =
        settings.getPluginUpdatePreviousJson(pluginId)?.let { codec.decode(it) }

    override fun clearPrevious(pluginId: String) {
        settings.setPluginUpdatePreviousJson(pluginId, null)
    }
}