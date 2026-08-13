package com.authorss81.noteflow.services

import com.authorss81.noteflow.plugins.PluginSettingsStore

/**
 * Production [PluginSettingsStore] implementation. Plugin settings live in the
 * app SharedPreferences under the canonical `plugins.<id>.<key>` namespace (see
 * [com.authorss81.noteflow.plugins.PluginSettingKey]), so two plugins can never
 * collide and every plugin's settings survive process restarts.
 */
class SettingsPluginSettingsStore(
    private val settings: SettingsManager
) : PluginSettingsStore {

    override fun getString(pluginId: String, key: String): String? =
        settings.getPluginSetting(pluginId, key)

    override fun setString(pluginId: String, key: String, value: String?) {
        settings.setPluginSetting(pluginId, key, value)
    }

    override fun getInt(pluginId: String, key: String, default: Int): Int =
        settings.getPluginIntSetting(pluginId, key, default)

    override fun setInt(pluginId: String, key: String, value: Int) {
        settings.setPluginIntSetting(pluginId, key, value)
    }

    override fun getBoolean(pluginId: String, key: String, default: Boolean): Boolean =
        settings.getPluginBooleanSetting(pluginId, key, default)

    override fun setBoolean(pluginId: String, key: String, value: Boolean) {
        settings.setPluginBooleanSetting(pluginId, key, value)
    }

    override fun containsKey(pluginId: String, key: String): Boolean =
        settings.hasPluginSetting(pluginId, key)
}