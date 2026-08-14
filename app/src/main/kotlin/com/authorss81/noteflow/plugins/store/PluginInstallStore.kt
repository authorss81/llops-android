package com.authorss81.noteflow.plugins.store

/**
 * Where the plugin store's install state lives.
 *
 * Phase 21: the plugin store adds an install/uninstall lifecycle on TOP of the
 * compile-time registry. A plugin that is installed is part of the active
 * registry (it can be enabled, routed, resolved); a plugin that is NOT installed
 * is "not downloaded" — it exists only as a bundled [PluginStoreCatalog] entry
 * and is excluded from the registry until the user downloads (installs) it.
 *
 * The framework stays decoupled from the concrete persistence so the store
 * controller is pure JVM and unit-testable with [InMemoryPluginInstallStore];
 * the production implementation delegates to
 * [com.authorss81.noteflow.services.SettingsManager].
 */
interface PluginInstallStore {
    /** Whether [pluginId] is currently installed ("downloaded") and active. */
    fun isInstalled(pluginId: String): Boolean

    /** Mark [pluginId] installed (true) or uninstalled (false). */
    fun setInstalled(pluginId: String, installed: Boolean)
}

/**
 * In-memory [PluginInstallStore] for JVM tests and for the registry's
 * backward-compatible default (see [PluginRegistry]).
 */
class InMemoryPluginInstallStore(
    installedIds: Collection<String> = emptyList()
) : PluginInstallStore {
    private val installed = installedIds.toMutableSet()

    override fun isInstalled(pluginId: String): Boolean = pluginId in installed

    override fun setInstalled(pluginId: String, installed: Boolean) {
        if (installed) this.installed.add(pluginId) else this.installed.remove(pluginId)
    }

    /** Test helper: force a set of ids installed (simulating a prior session). */
    fun installAll(ids: Collection<String>) {
        installed.addAll(ids)
    }
}
