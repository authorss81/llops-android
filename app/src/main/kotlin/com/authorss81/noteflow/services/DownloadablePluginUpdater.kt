package com.authorss81.noteflow.services

import com.authorss81.noteflow.plugins.PluginLogger
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.runtime.ManifestFetchResult
import com.authorss81.noteflow.plugins.runtime.PluginEntry
import com.authorss81.noteflow.plugins.runtime.PluginManifestFetcher
import com.authorss81.noteflow.plugins.runtime.PluginRuntime
import com.authorss81.noteflow.plugins.runtime.RuntimeOutcome
import com.authorss81.noteflow.plugins.store.PluginStoreController
import com.authorss81.noteflow.plugins.store.PluginUpdateCoordinator

/**
 * Production [PluginUpdateCoordinator] (Phase 24) — the store's update path
 * wired through the real runtime.
 *
 * - [fetchManifest] — fetches + parses the hosted version manifest over HTTPS
 *   ([PluginManifestFetcher] / [HttpsManifestTransport]); keyless and
 *   user-initiated.
 * - [runUpdate] — forwards an ALREADY-APPROVED verified update to
 *   [PluginRuntime.update], then re-loads the swapped artifact and swaps the
 *   live plugin instance in the registry ([PluginRegistry.replaceRemotePlugin])
 *   so the new version serves in the current session. The runtime engine
 *   re-enforces approval, re-verifies (pinned cert + SHA-256), smoke-tests and
 *   rolls back on any failure — this adapter only reports the outcome.
 *
 * Never logs artifact contents or any secret material.
 */
class DownloadablePluginUpdater(
    private val registry: PluginRegistry,
    private val runtime: PluginRuntime,
    private val manifestFetcher: PluginManifestFetcher,
    private val logger: PluginLogger = PluginLogger.NoOp
) : PluginUpdateCoordinator {

    override suspend fun fetchManifest(): ManifestFetchResult =
        manifestFetcher.fetch()

    override suspend fun runUpdate(
        entry: PluginEntry,
        target: PluginEntry,
        userApproved: Boolean,
        onProgress: (Float) -> Unit
    ): PluginStoreController.UpdateOutcome {
        when (val result = runtime.update(entry, target, userApproved, onProgress)) {
            is RuntimeOutcome.Success -> {
                // The swap persisted the new entry + artifact. Re-load and join
                // the new version to the live registry (in-session serving).
                when (val loaded = runtime.load(target)) {
                    is RuntimeOutcome.Success ->
                        registry.replaceRemotePlugin(loaded.value.plugin, null)
                    is RuntimeOutcome.Failed ->
                        // The engine already smoke-tested this artifact; a load
                        // here failing is unexpected, so log (ids only) and rely
                        // on the persisted new entry for the next launch.
                        logger.error(target.id, target.name, "post-update reload failed (${loaded.message.substringBefore('.')})")
                    is RuntimeOutcome.NotYetImplemented ->
                        logger.error(target.id, target.name, "post-update reload not implemented")
                }
                return PluginStoreController.UpdateOutcome.Updated(
                    pluginId = target.id,
                    fromVersion = result.value.fromVersion,
                    toVersion = result.value.toVersion
                )
            }
            // Any failure inside the engine keeps the previous version active.
            is RuntimeOutcome.Failed -> return PluginStoreController.UpdateOutcome.RolledBack(
                pluginId = target.id,
                message = result.message
            )
            is RuntimeOutcome.NotYetImplemented -> return PluginStoreController.UpdateOutcome.Failed(
                pluginId = target.id,
                message = result.message
            )
        }
    }
}