package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.CaseChangePlugin
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginInstallResult
import com.authorss81.noteflow.plugins.PluginLifecycleState
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.InMemoryPluginSettingsStore
import com.authorss81.noteflow.plugins.runtime.InMemoryPluginEntryStore
import com.authorss81.noteflow.plugins.runtime.PluginEntry
import com.authorss81.noteflow.plugins.runtime.PluginEntrySource
import com.authorss81.noteflow.plugins.runtime.PluginEntryStore
import com.authorss81.noteflow.plugins.runtime.PluginVersion
import com.authorss81.noteflow.plugins.store.InMemoryPluginInstallStore
import com.authorss81.noteflow.plugins.store.PluginStoreCatalog
import com.authorss81.noteflow.plugins.store.PluginStoreController
import com.authorss81.noteflow.plugins.store.RemotePluginInstaller
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 23: the store's REMOTE (downloadable) path — the FIRST download needs
 * explicit consent ([DownloadOutcome.NeedsConsent]); after consent the
 * installer performs download → verify → load → registry install; Delete wipes
 * the artifact + persisted entry + registry state. The installer is a fake
 * (the real one is exercised against the runtime in DownloadablePluginRuntimeTest).
 */
class RemotePluginStoreDownloadTest {

    private val baseIds = PluginRegistry.defaultPlugins().map { it.id }

    private val remoteId = "com.authorss81.noteflow.plugins.remote.ocr"

    private fun remoteEntry(id: String = remoteId) = PluginEntry(
        id = id,
        name = "Remote OCR",
        description = "Heavy downloadable OCR engine.",
        version = PluginVersion(1, 0, 0),
        capabilities = setOf(PluginCapability.OCR),
        category = "Vision",
        downloadUrl = "https://plugins.example.com/ocr-1.0.0.apk",
        sha256 = "ab12cd34ef56",
        pinnedCertHash = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        source = PluginEntrySource.REMOTE
    )

    /**
     * In-memory [RemotePluginInstaller] whose `install` reaches the REAL
     * registry via [PluginRegistry.installPlugin] — the same end state the
     * production installer produces.
     */
    private class RecordingInstaller(
        private val registry: PluginRegistry,
        val entryStore: PluginEntryStore
    ) : RemotePluginInstaller {
        val consented = mutableSetOf<String>()
        val installed = mutableListOf<String>()
        val deletedArtifacts = mutableListOf<String>()

        override fun isConsented(pluginId: String): Boolean = pluginId in consented
        override fun grantConsent(pluginId: String) { consented += pluginId }

        override suspend fun install(
            entry: PluginEntry,
            onProgress: (Float) -> Unit
        ): PluginStoreController.DownloadOutcome {
            entryStore.save(entry)
            onProgress(0.5f)
            val plugin = TestPlugin(id = entry.id, name = entry.name, capabilities = entry.capabilities)
            return when (val install = registry.installPlugin(plugin, null)) {
                is PluginInstallResult.Installed -> {
                    installed += entry.id
                    onProgress(1f)
                    PluginStoreController.DownloadOutcome.Installed(entry.id)
                }
                is PluginInstallResult.Refused ->
                    PluginStoreController.DownloadOutcome.Failed(entry.id, install.reason)
            }
        }

        override fun deleteArtifact(entry: PluginEntry) {
            deletedArtifacts += entry.id
            entryStore.remove(entry.id)
        }
    }

    /** A self-contained store fixture: registry + persisted entry store + controller. */
    private class StoreFixture(
        val registry: PluginRegistry,
        val entryStore: PluginEntryStore,
        val controller: PluginStoreController
    )

    private fun newStore(entryStore: PluginEntryStore = InMemoryPluginEntryStore(), remote: RemotePluginInstaller? = null): StoreFixture {
        val registry = PluginRegistry(
            enableStore = InMemoryEnableStore(),
            settingsStore = InMemoryPluginSettingsStore(),
            installStore = InMemoryPluginInstallStore(baseIds),
            optionalPluginFactories = listOf({ CaseChangePlugin() }),
            currentApiLevel = 26
        )
        val catalog = PluginStoreCatalog(registry, entryStore)
        return StoreFixture(registry, entryStore, PluginStoreController(registry, catalog, remoteInstaller = remote))
    }

    @Test
    fun `a remote plugin without consent answers NeedsConsent and installs nothing`() = runBlocking {
        val entryStore = InMemoryPluginEntryStore()
        entryStore.save(remoteEntry())
        val registry = PluginRegistry(
            enableStore = InMemoryEnableStore(),
            settingsStore = InMemoryPluginSettingsStore(),
            installStore = InMemoryPluginInstallStore(baseIds),
            optionalPluginFactories = listOf({ CaseChangePlugin() }),
            currentApiLevel = 26
        )
        val installer = RecordingInstaller(registry, entryStore)
        val controller = PluginStoreController(registry, PluginStoreCatalog(registry, entryStore), remoteInstaller = installer)

        val outcome = controller.download(remoteId, null) { }

        assertTrue(outcome is PluginStoreController.DownloadOutcome.NeedsConsent)
        assertTrue((outcome as PluginStoreController.DownloadOutcome.NeedsConsent).message.contains("signature-verified"))
        assertFalse(installer.installed.isNotEmpty())
        assertFalse(registry.isInstalled(remoteId))
    }

    @Test
    fun `after explicit consent the remote plugin downloads installs and starts OFF`() = runBlocking {
        val entryStore = InMemoryPluginEntryStore()
        entryStore.save(remoteEntry())
        val registry = PluginRegistry(
            enableStore = InMemoryEnableStore(),
            settingsStore = InMemoryPluginSettingsStore(),
            installStore = InMemoryPluginInstallStore(baseIds),
            optionalPluginFactories = listOf({ CaseChangePlugin() }),
            currentApiLevel = 26
        )
        val installer = RecordingInstaller(registry, entryStore)
        val controller = PluginStoreController(registry, PluginStoreCatalog(registry, entryStore), remoteInstaller = installer)
        installer.grantConsent(remoteId)

        val progress = mutableListOf<Float>()
        val outcome = controller.download(remoteId, null) { progress.add(it) }

        assertTrue(outcome is PluginStoreController.DownloadOutcome.Installed)
        assertEquals(listOf(remoteId), installer.installed)
        assertTrue(progress.isNotEmpty())
        assertEquals(1f, progress.last(), 0f)
        // Downloaded plugins are REGISTERED — OFF by default — not auto-enabled.
        assertTrue(registry.isInstalled(remoteId))
        assertFalse(registry.isEnabled(remoteId))
        assertEquals(PluginLifecycleState.REGISTERED, registry.stateOf(remoteId)?.state)
    }

    @Test
    fun `the store lists a persisted remote plugin as not-installed until downloaded`() {
        val entryStore = InMemoryPluginEntryStore()
        entryStore.save(remoteEntry())
        val fixture = newStore(entryStore = entryStore, remote = null)

        val row = fixture.controller.rows(null).first { it.entry.pluginId == remoteId }
        assertTrue(row.entry.bundled.not())
        assertFalse(row.installed)
        assertTrue(fixture.registry.isInstalled(remoteId).not())
    }

    @Test
    fun `a remote download without a wired installer fails loudly`() = runBlocking {
        val entryStore = InMemoryPluginEntryStore()
        entryStore.save(remoteEntry())
        val fixture = newStore(entryStore = entryStore, remote = null)

        val outcome = fixture.controller.download(remoteId, null) { }

        assertTrue(outcome is PluginStoreController.DownloadOutcome.Failed)
        assertTrue((outcome as PluginStoreController.DownloadOutcome.Failed).message.contains("remote"))
        assertFalse(fixture.registry.isInstalled(remoteId))
    }

    @Test
    fun `delete wipes the remote artifact and persisted entry along with the plugin`() = runBlocking {
        val entryStore = InMemoryPluginEntryStore()
        entryStore.save(remoteEntry())
        val registry = PluginRegistry(
            enableStore = InMemoryEnableStore(),
            settingsStore = InMemoryPluginSettingsStore(),
            installStore = InMemoryPluginInstallStore(baseIds),
            optionalPluginFactories = listOf({ CaseChangePlugin() }),
            currentApiLevel = 26
        )
        val installer = RecordingInstaller(registry, entryStore)
        val controller = PluginStoreController(registry, PluginStoreCatalog(registry, entryStore), remoteInstaller = installer)
        installer.grantConsent(remoteId)
        assertTrue(controller.download(remoteId, null) { } is PluginStoreController.DownloadOutcome.Installed)
        assertTrue(entryStore.find(remoteId) != null)
        assertTrue(registry.isInstalled(remoteId))

        val deleted = controller.delete(remoteId, null)

        assertTrue(deleted is PluginStoreController.DeleteOutcome.Deleted)
        assertFalse(registry.isInstalled(remoteId))
        assertEquals(listOf(remoteId), installer.deletedArtifacts)
        assertTrue(entryStore.find(remoteId) == null)
    }

    @Test
    fun `grantRemoteConsent persists the user's explicit consent on the wired installer`() {
        val entryStore = InMemoryPluginEntryStore()
        val registry = PluginRegistry(
            enableStore = InMemoryEnableStore(),
            settingsStore = InMemoryPluginSettingsStore(),
            installStore = InMemoryPluginInstallStore(baseIds),
            optionalPluginFactories = listOf({ CaseChangePlugin() }),
            currentApiLevel = 26
        )
        val installer = RecordingInstaller(registry, entryStore)
        val controller = PluginStoreController(registry, PluginStoreCatalog(registry, entryStore), remoteInstaller = installer)

        assertFalse(installer.isConsented(remoteId))
        assertTrue(controller.grantRemoteConsent(remoteId))
        assertTrue(installer.isConsented(remoteId))
    }

    @Test
    fun `grantRemoteConsent fails when no remote installer is wired`() {
        val fixture = newStore(remote = null)

        assertFalse(fixture.controller.grantRemoteConsent(remoteId))
    }
}