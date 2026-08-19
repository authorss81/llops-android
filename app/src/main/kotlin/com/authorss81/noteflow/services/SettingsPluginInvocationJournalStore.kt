package com.authorss81.noteflow.services

/**
 * Phase 173 (feature 2): the production [PluginInvocationJournal.Store] bound
 * to [SettingsManager].
 *
 * Persists each plugin's bounded journal wire under its own top-level key
 * (`plugin_invocation_journal_<id>` — NOT under `plugins.<id>.*`) so a plugin
 * can never read or forge its own journal through the plugin settings API, and
 * the store's Delete wipes it via [SettingsManager.wipePluginState].
 */
class SettingsPluginInvocationJournalStore(
    private val settings: SettingsManager
) : PluginInvocationJournal.Store {

    override fun read(pluginId: String): String? = settings.getPluginInvocationJournal(pluginId)

    override fun write(pluginId: String, wire: String?) {
        settings.setPluginInvocationJournal(pluginId, wire)
    }
}