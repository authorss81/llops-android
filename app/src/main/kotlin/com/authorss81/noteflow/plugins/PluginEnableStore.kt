package com.authorss81.noteflow.plugins

/**
 * Where plugin enable/disable state lives.
 *
 * The framework is deliberately decoupled from the concrete persistence so the
 * registry and manager are pure JVM code and can be unit-tested without Android
 * (see `PluginFrameworkTest`'s in-memory store). The production implementation
 * delegates to [com.authorss81.noteflow.services.SettingsManager] so plugin
 * opt-in survives process restarts alongside every other app setting.
 */
interface PluginEnableStore {
    fun isEnabled(pluginId: String): Boolean
    fun setEnabled(pluginId: String, enabled: Boolean)
}
