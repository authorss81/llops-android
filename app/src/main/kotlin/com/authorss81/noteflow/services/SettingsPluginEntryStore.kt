package com.authorss81.noteflow.services

import com.authorss81.noteflow.plugins.runtime.PluginEntry
import com.authorss81.noteflow.plugins.runtime.PluginEntryCodec
import com.authorss81.noteflow.plugins.runtime.PluginEntryStore

/**
 * Production [PluginEntryStore] (Phase 22) — persisted unified catalog entries
 * live in the same SharedPreferences as every other app setting (via
 * [SettingsManager]), as one JSON blob per plugin under `plugin_entry_<id>`.
 *
 * Only REMOTE (downloadable) entries are persisted — bundled entries are
 * derived from the compile-time registry by the store catalog and never stored.
 * The store's Delete action wipes the blob through [SettingsManager.wipePluginState],
 * so a deleted plugin leaves no catalog residue.
 *
 * The serialization logic lives in the pure-JVM [PluginEntryCodec] (unit-tested);
 * this adapter is a thin, Android-only read/write shell.
 */
class SettingsPluginEntryStore(
    private val settings: SettingsManager,
    private val codec: PluginEntryCodec = PluginEntryCodec()
) : PluginEntryStore {

    override fun save(entry: PluginEntry) {
        if (!entry.isDownloadable) {
            // Bundled entries are derivable facts of the APK — never persist them.
            remove(entry.id)
            return
        }
        settings.setPluginEntryJson(entry.id, codec.encode(entry))
    }

    override fun find(pluginId: String): PluginEntry? =
        settings.getPluginEntryJson(pluginId)?.let { codec.decode(it) }

    override fun all(): List<PluginEntry> =
        settings.allPluginEntryIds().mapNotNull { find(it) }

    override fun remove(pluginId: String) {
        settings.setPluginEntryJson(pluginId, null)
    }
}
