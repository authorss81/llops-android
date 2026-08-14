package com.authorss81.noteflow.services

import com.authorss81.noteflow.plugins.store.PluginInstallStore

/**
 * Production [PluginInstallStore] implementation — plugin install state lives in
 * the same SharedPreferences as every other app setting (via [SettingsManager]),
 * so a plugin the user deletes stays deleted across restarts, and a re-download
 * is remembered.
 */
class SettingsPluginInstallStore(
    private val settings: SettingsManager
) : PluginInstallStore {

    override fun isInstalled(pluginId: String): Boolean =
        !settings.isPluginUninstalled(pluginId)

    override fun setInstalled(pluginId: String, installed: Boolean) {
        settings.setPluginUninstalled(pluginId, !installed)
    }
}
