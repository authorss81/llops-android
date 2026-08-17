package com.authorss81.noteflow.plugins.store

import android.content.Context
import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.PluginInstallResult
import com.authorss81.noteflow.plugins.PluginLogger
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.PluginStateInfo
import com.authorss81.noteflow.plugins.PluginUninstallResult
import com.authorss81.noteflow.plugins.runtime.CompileTimePluginPinStore
import com.authorss81.noteflow.plugins.runtime.CompileTimePluginPins
import com.authorss81.noteflow.plugins.runtime.ManifestFetchResult
import com.authorss81.noteflow.plugins.runtime.PluginUpdateChecker
import com.authorss81.noteflow.plugins.runtime.PluginUpdateInfo
import com.authorss81.noteflow.plugins.runtime.PluginVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The plugin store's pure-JVM lifecycle logic (Phase 21, extended Phase 23).
 *
 * Implements the store's install/uninstall state machine over the unified
 * catalog (bundled definitions + remote entries) + the compile-time registry.
 * HONEST semantics under the hybrid rule:
 *
 * - **Bundled Download** installs a plugin DEFINITION that is already compiled
 *   in the APK — never a network fetch, never an executable APK. It activates
 *   the definition (for an optional plugin it becomes part of the registry; for
 *   a previously-deleted built-in it flips install state back on).
 * - **Remote Download** (Phase 23) goes through [RemotePluginInstaller] — the
 *   FIRST download needs explicit user consent ([DownloadOutcome.NeedsConsent]),
 *   after which the installer performs HTTPS download → pinned-cert + sha256
 *   verification → load → registry install. Downloaded plugins are OFF by
 *   default (registry install starts REGISTERED) and toggleable from the store.
 *   Progress is reported so the UI has real progress/error states.
 * - **Delete** is "gone + settings wiped": it tears down the plugin, deletes
 *   its downloaded model/assets AND (for remote plugins) the downloaded
 *   artifact + persisted catalog entry, wipes its namespaced settings and
 *   opt-in history, and removes it from the registry.
 * - **Disable / Enable** are delegated to [PluginRegistry.setEnabled] and keep
 *   all data (re-enableable).
 *
 * Pure JVM (registry + catalog + stores are all JVM-testable); only the
 * off-main-thread hop and progress delay touch coroutines.
 */
class PluginStoreController(
    private val registry: PluginRegistry,
    private val catalog: PluginStoreCatalog,
    private val logger: PluginLogger = PluginLogger.NoOp,
    private val remoteInstaller: RemotePluginInstaller? = null,
    private val updateCoordinator: PluginUpdateCoordinator? = null,
    private val pins: CompileTimePluginPinStore = CompileTimePluginPins.defaultStore
) {

    /** Outcome of a store "Download". */
    sealed class DownloadOutcome {
        data class Installed(val pluginId: String) : DownloadOutcome()

        /** Remote plugin, first download — explicit user consent is required
         *  before any bytes move. Call [grantRemoteConsent] then re-download. */
        data class NeedsConsent(val pluginId: String, val message: String) : DownloadOutcome()

        data class Failed(val pluginId: String, val message: String) : DownloadOutcome()
    }

    /** Outcome of a store "Delete". */
    sealed class DeleteOutcome {
        data class Deleted(val pluginId: String) : DeleteOutcome()
        data class Failed(val pluginId: String, val message: String) : DeleteOutcome()
    }

    /** Outcome of a store "Check for updates". */
    sealed class UpdateCheckOutcome {
        /** One or more newer versions are offered by the hosted manifest. */
        data class UpdatesAvailable(val updates: List<PluginUpdateInfo>) : UpdateCheckOutcome()

        /** Everything installed is current (or the manifest offers nothing newer). */
        data object UpToDate : UpdateCheckOutcome()

        /** The check could not complete (offline, fetch failure, bad manifest). */
        data class Failed(val message: String) : UpdateCheckOutcome()
    }

    /** Outcome of a store "Update" (approved, verified, reversible). */
    sealed class UpdateOutcome {
        /** The new version was verified + swapped and is now the active version. */
        data class Updated(
            val pluginId: String,
            val fromVersion: PluginVersion,
            val toVersion: PluginVersion
        ) : UpdateOutcome()

        /** The update failed and the previous version is active again. */
        data class RolledBack(val pluginId: String, val message: String) : UpdateOutcome()

        /** The update was requested without explicit approval — it never ran. */
        data class NeedsApproval(val pluginId: String) : UpdateOutcome()

        data class Failed(val pluginId: String, val message: String) : UpdateOutcome()
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
     * Download a plugin. Two honest paths:
     * - **Bundled** — install the compiled definition (offline, no APK loading;
     *   progress reported).
     * - **Remote** — first download needs explicit consent
     *   ([DownloadOutcome.NeedsConsent]); after consent the installer downloads
     *   the signed artifact over HTTPS, verifies pinned-cert + sha256, loads and
     *   installs it (REGISTERED — off by default). Runs off the main thread.
     * Never throws.
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
        if (entry.bundled) {
            return downloadBundled(entry, context, onProgress)
        }
        // REMOTE (downloadable) plugin — the verified-download runtime path.
        val installer = remoteInstaller
            ?: return DownloadOutcome.Failed(
                pluginId,
                "This is a remote (downloadable) plugin, but remote downloads are not wired in this build."
            )
        if (!installer.isConsented(pluginId)) {
            return DownloadOutcome.NeedsConsent(
                pluginId,
                "This plugin adds features from a third party. " +
                    "It is signature-verified (pinned certificate + SHA-256) before any code runs, " +
                    "and it stays OFF until you enable it. Download and install?"
            )
        }
        onProgress(0f)
        return withContext(Dispatchers.Default) {
            installer.install(entry.entry, onProgress)
        }
    }

    /** Record explicit consent for [pluginId]'s remote download. Returns false
     *  when no remote installer is wired (the consent cannot be granted). */
    fun grantRemoteConsent(pluginId: String): Boolean {
        val installer = remoteInstaller ?: return false
        installer.grantConsent(pluginId)
        return true
    }

    /** Bundled-definition install (offline). */
    private suspend fun downloadBundled(
        entry: PluginStoreEntry,
        context: Context?,
        onProgress: (Float) -> Unit
    ): DownloadOutcome {
        val pluginId = entry.pluginId
        // The definition is always one of the registry's compiled set — built-in
        // or optional bundled (optional definitions are RE-materialized from
        // their factory on process restart, so an installed optional plugin is
        // found here even after the app was killed).
        val plugin = registry.compiledPlugins.firstOrNull { it.id == pluginId }
            ?: return DownloadOutcome.Failed(
                pluginId,
                "This plugin definition is not available in this build."
            )
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
                // B2-LOG-04 (phase-93): reason could echo hostile plugin data —
                // log a FIXED code, keep the reason user-facing only.
                logger.error(pluginId, plugin.name, "store download refused; code=REGISTRY_REFUSED")
                DownloadOutcome.Failed(pluginId, result.reason)
            }
        }
    }

    /**
     * Delete a plugin COMPLETELY: downloaded model/assets removed, opt-in +
     * settings wiped, and the plugin absent from the registry until re-download.
     * For a REMOTE (downloadable) plugin the downloaded artifact + persisted
     * catalog entry are also removed. Never throws.
     */
    fun delete(pluginId: String, context: Context?): DeleteOutcome {
        val plugin = registry.allPlugins.firstOrNull { it.id == pluginId }
            ?: return DeleteOutcome.Failed(pluginId, "This plugin is not installed.")
        // Remove downloaded assets BEFORE the registry forgets the plugin.
        plugin.deleteDownloadedAssets(context)
        val entry = catalog.entryFor(pluginId)
        return when (val result = registry.uninstallPlugin(pluginId, context)) {
            is PluginUninstallResult.Uninstalled -> {
                // A downloadable plugin's artifact + entry blob are owned by the
                // runtime/store, not the plugin itself — remove them too.
                if (entry != null && !entry.bundled) {
                    remoteInstaller?.deleteArtifact(entry.entry)
                }
                logger.lifecycle("store-delete", pluginId, plugin.name)
                DeleteOutcome.Deleted(pluginId)
            }
            is PluginUninstallResult.Refused -> {
                // B2-LOG-04 (phase-93): fixed code only, never result.reason.
                logger.error(pluginId, plugin.name, "store delete refused; code=REGISTRY_REFUSED")
                DeleteOutcome.Failed(pluginId, result.reason)
            }
        }
    }

    /**
     * Store "Check for updates" (Phase 24). Fetches the hosted version manifest
     * (keyless, HTTPS-only, user-initiated) and compares it against the
     * INSTALLED downloadable plugins. Reports [UpdateCheckOutcome.UpdatesAvailable]
     * only when the manifest offers a version strictly newer than what is
     * installed — never a downgrade, never an equal no-op. Bundled
     * (compile-time) plugins are excluded ("managed by app update"). Never throws.
     */
    suspend fun checkForUpdates(context: Context?): UpdateCheckOutcome {
        val coordinator = updateCoordinator
            ?: return UpdateCheckOutcome.Failed("Checking for plugin updates is not wired in this build.")
        return when (val fetched = coordinator.fetchManifest()) {
            is ManifestFetchResult.Failed -> UpdateCheckOutcome.Failed(fetched.message)
            is ManifestFetchResult.Loaded -> {
                val installedRemote = rows(context)
                    .filter { !it.entry.bundled && it.installed }
                    .map { it.entry.entry }
                val updates = PluginUpdateChecker.check(installedRemote, fetched.manifest, pins)
                if (updates.isEmpty()) UpdateCheckOutcome.UpToDate
                else UpdateCheckOutcome.UpdatesAvailable(updates)
            }
        }
    }

    /**
     * Store "Update" (Phase 24). Applies a user-approved, verified update.
     *
     * Consent contract (MANDATORY): an update NEVER runs unless [userApproved]
     * is true — the approval dialog is the only path to a true value, and a
     * non-approved request answers [UpdateOutcome.NeedsApproval] without
     * touching the coordinator. After approval a FRESH manifest is fetched and
     * the target entry is built from it (an offer is always current at install
     * time); the coordinator then downloads, re-verifies (pinned cert + SHA-256),
     * smoke-tests and swaps — any failure leaves the previous version active
     * ([UpdateOutcome.RolledBack]). Never throws.
     */
    suspend fun update(
        pluginId: String,
        userApproved: Boolean,
        onProgress: (Float) -> Unit
    ): UpdateOutcome {
        val coordinator = updateCoordinator
            ?: return UpdateOutcome.Failed(pluginId, "Plugin updates are not wired in this build.")
        if (!userApproved) {
            return UpdateOutcome.NeedsApproval(pluginId)
        }
        val storeEntry = catalog.entryFor(pluginId)
            ?: return UpdateOutcome.Failed(pluginId, "This plugin is not in the catalog.")
        if (storeEntry.bundled) {
            return UpdateOutcome.Failed(
                pluginId,
                "\"${storeEntry.name}\" is a built-in plugin and is managed by the app update — it is not updated from the plugin store."
            )
        }
        // The ACTIVE persisted entry is the current version (a previous update
        // leaves the catalog definition stale); fall back to the catalog copy
        // only when nothing is persisted yet.
        val entry = remoteInstaller?.activeEntryFor(pluginId) ?: storeEntry.entry
        val manifest = when (val fetched = coordinator.fetchManifest()) {
            is ManifestFetchResult.Failed -> return UpdateOutcome.Failed(pluginId, fetched.message)
            is ManifestFetchResult.Loaded -> fetched.manifest
        }
        val info = PluginUpdateChecker.check(listOf(entry), manifest, pins)
            .firstOrNull { it.pluginId == pluginId }
            ?: return UpdateOutcome.Failed(
                pluginId,
                "No newer version of \"${entry.name}\" (v${entry.version}) is available."
            )
        // PluginUpdateChecker guarantees info.newVersion is strictly newer.
        val target = info.toTargetEntry(entry)
        onProgress(0f)
        return coordinator.runUpdate(entry, target, userApproved, onProgress)
    }

    private companion object {
        /** Simulated bundled-install window (ms) so progress states are real. */
        const val INSTALL_DELAY_MS = 350L
    }
}