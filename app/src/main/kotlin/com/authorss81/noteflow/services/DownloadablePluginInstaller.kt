package com.authorss81.noteflow.services

import com.authorss81.noteflow.plugins.PluginInstallResult
import com.authorss81.noteflow.plugins.PluginLogger
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.runtime.PluginArtifact
import com.authorss81.noteflow.plugins.runtime.PluginDownloader
import com.authorss81.noteflow.plugins.runtime.PluginEntry
import com.authorss81.noteflow.plugins.runtime.PluginEntryStore
import com.authorss81.noteflow.plugins.runtime.PluginRuntime
import com.authorss81.noteflow.plugins.runtime.RuntimeOutcome
import com.authorss81.noteflow.plugins.store.PluginStoreController
import com.authorss81.noteflow.plugins.store.RemotePluginInstaller

/**
 * Production [RemotePluginInstaller] (Phase 23): the store's remote-download
 * path, wired through the real runtime.
 *
 * Consent is persisted via [SettingsManager] (`plugin_download_consent_<id>`).
 * The download runs through [PluginDownloader] into app-private storage
 * ([PluginArtifactStorage]), then:
 *
 * ```
 * download → runtime.verify (sha256 + pinned signing cert) → runtime.load
 *          → registry.installPlugin  (REGISTERED — OFF by default)
 * ```
 *
 * Every failure removes the partially-installed artifact + persisted entry so
 * a bad download never lingers. Never logs artifact contents.
 */
class DownloadablePluginInstaller(
    private val settings: SettingsManager,
    private val registry: PluginRegistry,
    private val entryStore: PluginEntryStore,
    private val storage: PluginArtifactStorage,
    private val runtime: PluginRuntime,
    private val downloader: PluginDownloader,
    private val logger: PluginLogger = PluginLogger.NoOp
) : RemotePluginInstaller {

    override fun isConsented(pluginId: String): Boolean =
        settings.isPluginDownloadConsented(pluginId)

    override fun grantConsent(pluginId: String) {
        settings.setPluginDownloadConsented(pluginId, true)
    }

    override fun activeEntryFor(pluginId: String): PluginEntry? =
        entryStore.find(pluginId)

    override suspend fun install(
        entry: PluginEntry,
        onProgress: (Float) -> Unit
    ): PluginStoreController.DownloadOutcome {
        // Persist the catalog entry so the plugin (and its pinned digests)
        // survives a process restart.
        entryStore.save(entry)
        onProgress(0f)
        val artifactFile = storage.artifactFor(entry)
        if (artifactFile == null) {
            when (val download = downloader.download(entry, storage.dir(), userConsented = true, onProgress = onProgress)) {
                is PluginDownloader.DownloadOutcome.Success -> onProgress(0.5f)
                is PluginDownloader.DownloadOutcome.Failed -> {
                    entryStore.remove(entry.id)
                    return PluginStoreController.DownloadOutcome.Failed(entry.id, download.message)
                }
            }
        } else {
            onProgress(0.4f)
        }
        val sha256 = entry.sha256 ?: return failCleanup(entry, "remote entry '${entry.id}' is missing its pinned sha256.")
        val pinnedCertHash = entry.pinnedCertHash ?: return failCleanup(entry, "remote entry '${entry.id}' is missing its pinned certificate hash.")
        val artifact = PluginArtifact(entry, storage.artifactFor(entry)!!.canonicalPath, sha256, pinnedCertHash)
        onProgress(0.6f)
        when (val verification = runtime.verify(artifact)) {
            is RuntimeOutcome.Success -> onProgress(0.75f)
            is RuntimeOutcome.Failed -> return failCleanup(entry, verification.message)
            is RuntimeOutcome.NotYetImplemented -> return failCleanup(entry, verification.message)
        }
        when (val loaded = runtime.load(entry)) {
            is RuntimeOutcome.Success -> {
                onProgress(0.9f)
                return when (val result = registry.installPlugin(loaded.value.plugin, null)) {
                    is PluginInstallResult.Installed -> {
                        onProgress(1f)
                        logger.lifecycle("store-remote-download", entry.id, entry.name)
                        PluginStoreController.DownloadOutcome.Installed(entry.id)
                    }
                    is PluginInstallResult.Refused -> failCleanup(entry, result.reason)
                }
            }
            is RuntimeOutcome.Failed -> return failCleanup(entry, loaded.message)
            is RuntimeOutcome.NotYetImplemented -> return failCleanup(entry, loaded.message)
        }
    }

    override fun deleteArtifact(entry: PluginEntry) {
        storage.delete(entry)
        entryStore.remove(entry.id)
    }

    private fun failCleanup(entry: PluginEntry, message: String): PluginStoreController.DownloadOutcome {
        storage.delete(entry)
        entryStore.remove(entry.id)
        logger.error(entry.id, entry.name, "remote install failed: ${message.substringBefore('.')}")
        return PluginStoreController.DownloadOutcome.Failed(entry.id, message)
    }
}