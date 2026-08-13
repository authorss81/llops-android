package com.authorss81.noteflow.plugins

import android.content.Context

/**
 * Compile-time registry of installed plugins.
 *
 * Plugins are discovered by simple list registration — the registry never loads
 * code at runtime, so the set of plugins is fixed at build time (honest, safe,
 * and trivially unit-testable). A new plugin is added by implementing
 * [NoteflowPlugin], adding it to [defaultPlugins], and (for a brand-new
 * capability) defining its serving interface. Nothing else in the app needs to
 * change.
 *
 * @param enableStore persistence for per-plugin opt-in.
 * @param plugins the installed plugins (defaults to the built-in set).
 */
class PluginRegistry(
    private val enableStore: PluginEnableStore,
    private val plugins: List<NoteflowPlugin> = defaultPlugins()
) {

    /**
     * Plugin ids whose [NoteflowPlugin.onEnable] hook has already run in this
     * process. Tracks the hook independently of the persisted enable state so it
     * fires at most ONCE per process — including across disable/re-enable cycles
     * and across process restarts (where the store may already say "enabled").
     */
    private val enabledNotified = mutableSetOf<String>()

    /**
     * Run at process start (from the ViewModel) so plugins that are already
     * enabled in the persisted store still get their [NoteflowPlugin.onEnable]
     * hook — otherwise a plugin enabled in a previous session would never be
     * initialized in this one. Idempotent per process.
     */
    fun onProcessStart(context: Context?) {
        plugins.forEach { plugin ->
            if (enableStore.isEnabled(plugin.id) && enabledNotified.add(plugin.id)) {
                plugin.onEnable(context)
            }
        }
    }

    /** Every installed plugin, in registration order. */
    val allPlugins: List<NoteflowPlugin> get() = plugins

    /** Whether [pluginId] is currently opted-in by the user. */
    fun isEnabled(pluginId: String): Boolean = enableStore.isEnabled(pluginId)

    /** Opted-in plugins that can run on this device/context. */
    fun enabledPlugins(context: Context? = null): List<NoteflowPlugin> =
        plugins.filter { enableStore.isEnabled(it.id) && it.isAvailable(context) }

    /** Opted-in, device-available plugins that serve [capability]. */
    fun availablePlugins(capability: PluginCapability, context: Context? = null): List<NoteflowPlugin> =
        enabledPlugins(context).filter { capability in it.capabilities }

    /** All plugins that declare [capability] (regardless of enabled/available). */
    fun pluginsForCapability(capability: PluginCapability): List<NoteflowPlugin> =
        plugins.filter { capability in it.capabilities }

    /**
     * Set a plugin's opt-in state. When a plugin transitions from off to on, its
     * [NoteflowPlugin.onEnable] hook is invoked — at most once per process.
     */
    fun setEnabled(pluginId: String, enabled: Boolean, context: Context? = null) {
        val wasEnabled = enableStore.isEnabled(pluginId)
        enableStore.setEnabled(pluginId, enabled)
        if (enabled && !wasEnabled) {
            plugins.firstOrNull { it.id == pluginId }?.let { plugin ->
                if (enabledNotified.add(plugin.id)) {
                    plugin.onEnable(context)
                }
            }
        }
    }

    /**
     * Display status for the plugin settings screen.
     *
     * - [PluginStatus.UNAVAILABLE] — cannot run on this device (isAvailable false).
     * - [PluginStatus.ENABLED] — opted-in and usable.
     * - [PluginStatus.DISABLED] — usable but not yet opted-in.
     */
    fun statusOf(plugin: NoteflowPlugin, context: Context?): PluginStatus =
        if (!plugin.isAvailable(context)) PluginStatus.UNAVAILABLE
        else if (enableStore.isEnabled(plugin.id)) PluginStatus.ENABLED
        else PluginStatus.DISABLED

    companion object {
        /**
         * The built-in plugin set. Extend this list to install a new plugin;
         * keep it as the single registration point (compile-time discovery).
         */
        fun defaultPlugins(): List<NoteflowPlugin> = listOf(
            Rot13TransformPlugin()
        )
    }
}

/** Display status of an installed plugin (see [PluginRegistry.statusOf]). */
enum class PluginStatus {
    /** Opted-in and usable on this device. */
    ENABLED,

    /** Usable on this device but not yet opted-in. */
    DISABLED,

    /** Cannot run on this device (fails [NoteflowPlugin.isAvailable]). */
    UNAVAILABLE
}
