package com.authorss81.noteflow.plugins.store

import android.content.Context
import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.PluginInstallResult
import com.authorss81.noteflow.plugins.PluginLogger
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.PluginStateInfo
import com.authorss81.noteflow.plugins.PluginUninstallResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The plugin store's pure-JVM lifecycle logic (Phase 21).
 *
 * Implements the store's install/uninstall state machine over the bundled
 * catalog + compile-time registry. HONEST semantics under the compile-time
 * rule:
 *
 * - **Download** installs a plugin DEFINITION that is already bundled in the
 *   APK — never a network fetch, never an executable APK. It activates the
 *   definition (for an optional plugin it becomes part of the registry; for a
 *   previously-deleted built-in it flips install state back on). Progress is
 *   reported so the UI has real progress/error states, and the operation
 *   runs off the main thread.
 * - **Delete** is "gone + settings wiped": it tears down the plugin, deletes
 *   its downloaded model/assets, wipes its namespaced settings and opt-in
 *   history, and removes it from the registry.
 * - **Disable / Enable** are delegated to [PluginRegistry.setEnabled] and keep
 *   all data (re-enableable).
 *
 * Pure JVM (registry + catalog + stores are all JVM-testable); only the
 * off-main-thread hop and progress delay touch coroutines.
 */
class PluginStoreController(
    private val registry: PluginRegistry,
    private val catalog: PluginStoreCatalog,
    private val logger: PluginLogger = PluginLogger.NoOp
) {

    /** Outcome of a store "Download" (bundled-definition install). */
    sealed class DownloadOutcome {
        data class Installed(val pluginId: String) : DownloadOutcome()
        data class Failed(val pluginId: String, val message: String) : DownloadOutcome()
    }

    /** Outcome of a store "Delete". */
    sealed class DeleteOutcome {
        data class Deleted(val pluginId: String) : DeleteOutcome()
        data class Failed(val pluginId: String, val message: String) : DeleteOutcome()
    }

    /** One row the store screen renders: catalog definition + install/lifecycle state. */
    data class StoreRow(
        val entry: PluginStoreEntry,
        val installed: Boolean,
        val state: PluginStateInfo?,
        val plugin: NoteflowPlugin?
    )

    /** The full store listing (catalog order): installed + not-downloaded entries. */
    fun rows(context: Context?): List<StoreRow> {
        val states = registry.resolve(context)
        val installedPlugins = registry.allPlugins.associateBy { it.id }
        return catalog.entries().map { entry ->
            val installed = registry.isInstalled(entry.pluginId)
            StoreRow(
                entry = entry,
                installed = installed,
                state = if (installed) states[entry.pluginId] else null,
                plugin = if (installed) installedPlugins[entry.pluginId] else null
            )
        }
    }

    /**
     * Download (install) a bundled plugin definition. Reports progress 0f→1f;
     * runs the install (including the registry's availability re-check) off the
     * main thread. Never throws.
     */
    suspend fun download(
        pluginId: String,
        context: Context?,
        onProgress: (Float) -> Unit
    ): DownloadOutcome {
        val entry = catalog.entryFor(pluginId)
            ?: return DownloadOutcome.Failed(pluginId, "This plugin is not in the catalog.")
        if (registry.isInstalled(pluginId)) {
            return DownloadOutcome.Failed(pluginId, "This plugin is already downloaded.")
        }
        // The definition is always one of the registry's compiled set — built-in
        // or optional bundled (optional definitions are RE-materialized from
        // their factory on process restart, so an installed optional plugin is
        // found here even after the app was killed).
        val plugin = registry.compiledPlugins.firstOrNull { it.id == pluginId }
            ?: return DownloadOutcome.Failed(pluginId, "This plugin's definition is missing.")
        onProgress(0f)
        // Brief, real install window + the actual registry install (which
        // re-evaluates every plugin's availability gate) run off the main
        // thread so the store never blocks the UI.
        val result = withContext(Dispatchers.Default) {
            delay(INSTALL_DELAY_MS)
            onProgress(0.6f)
            registry.installPlugin(plugin, context)
        }
        return when (result) {
            is PluginInstallResult.Installed -> {
                onProgress(1f)
                logger.lifecycle("store-download", pluginId, plugin.name)
                DownloadOutcome.Installed(pluginId)
            }
            is PluginInstallResult.Refused -> {
                logger.error(pluginId, plugin.name, "store download refused: ${result.reason}")
                DownloadOutcome.Failed(pluginId, result.reason)
            }
        }
    }

    /**
     * Delete a plugin COMPLETELY: downloaded model/assets removed, opt-in +
     * settings wiped, and the plugin absent from the registry until re-download.
     * Never throws.
     */
    fun delete(pluginId: String, context: Context?): DeleteOutcome {
        val plugin = registry.allPlugins.firstOrNull { it.id == pluginId }
            ?: return DeleteOutcome.Failed(pluginId, "This plugin is not installed.")
        // Remove downloaded assets BEFORE the registry forgets the plugin.
        plugin.deleteDownloadedAssets(context)
        return when (val result = registry.uninstallPlugin(pluginId, context)) {
            is PluginUninstallResult.Uninstalled -> {
                logger.lifecycle("store-delete", pluginId, plugin.name)
                DeleteOutcome.Deleted(pluginId)
            }
            is PluginUninstallResult.Refused -> {
                logger.error(pluginId, plugin.name, "store delete refused: ${result.reason}")
                DeleteOutcome.Failed(pluginId, result.reason)
            }
        }
    }

    private companion object {
        /** Simulated bundled-install window (ms) so progress states are real. */
        const val INSTALL_DELAY_MS = 350L
    }
}
