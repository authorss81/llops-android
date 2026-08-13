package com.authorss81.noteflow.services

import com.authorss81.noteflow.plugins.PluginEnableStore

/**
 * Production [PluginEnableStore] implementation — plugin opt-in lives in the
 * same SharedPreferences as every other app setting, persisted via
 * [SettingsManager], so enabled plugins survive process restarts.
 */
class SettingsPluginEnableStore(
    private val settings: SettingsManager
) : PluginEnableStore {

    override fun isEnabled(pluginId: String): Boolean = settings.isPluginEnabled(pluginId)

    override fun setEnabled(pluginId: String, enabled: Boolean) {
        settings.setPluginEnabled(pluginId, enabled)
    }
}