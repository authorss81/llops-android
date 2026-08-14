package com.authorss81.noteflow.plugins

/**
 * Where plugin enable/disable state lives.
 *
 * The framework is deliberately decoupled from the concrete persistence so the
 * registry and manager are pure JVM code and can be unit-tested without Android
 * (see the plugin test classes' in-memory store). The production implementation
 * delegates to [com.authorss81.noteflow.services.SettingsManager] so plugin
 * opt-in survives process restarts alongside every other app setting.
 */
interface PluginEnableStore {
    fun isEnabled(pluginId: String): Boolean
    fun setEnabled(pluginId: String, enabled: Boolean)

    /**
     * Whether the user has EVER enabled this plugin. Distinguishes the derived
     * states REGISTERED (never touched, off) from DISABLED (turned off). Must
     * become true the first time [setEnabled] is called with `enabled = true`.
     */
    fun hasEverBeenEnabled(pluginId: String): Boolean

    /**
     * Completely remove this plugin's opt-in history (used by the store's
     * Delete action — delete is "gone + settings wiped", unlike disable which
     * keeps everything re-enableable). The plugin is left disabled AND its
     * ever-enabled flag is reset, so a later re-install starts from REGISTERED.
     *
     * Implementations MUST reset both the enabled flag AND the ever-enabled
     * history (a default that only disables would make a re-install derive as
     * DISABLED instead of REGISTERED).
     */
    fun wipe(pluginId: String)
}