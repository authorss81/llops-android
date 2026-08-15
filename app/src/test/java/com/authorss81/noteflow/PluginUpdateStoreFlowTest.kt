package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.CaseChangePlugin
import com.authorss81.noteflow.plugins.InMemoryPluginSettingsStore
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginInstallResult
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.runtime.CompileTimePluginPinStore
import com.authorss81.noteflow.plugins.runtime.HostedPluginManifest
import com.authorss81.noteflow.plugins.runtime.HostedPluginVersion
import com.authorss81.noteflow.plugins.runtime.InMemoryPluginEntryStore
import com.authorss81.noteflow.plugins.runtime.ManifestFetchResult
import com.authorss81.noteflow.plugins.runtime.PinnedPluginRelease
import com.authorss81.noteflow.plugins.runtime.PluginEntry
import com.authorss81.noteflow.plugins.runtime.PluginEntrySource
import com.authorss81.noteflow.plugins.runtime.PluginVersion
import com.authorss81.noteflow.plugins.store.InMemoryPluginInstallStore
import com.authorss81.noteflow.plugins.store.PluginStoreCatalog
import com.authorss81.noteflow.plugins.store.PluginStoreController
import com.authorss81.noteflow.plugins.store.PluginUpdateCoordinator
import com.authorss81.noteflow.plugins.store.RemotePluginInstaller
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 24: the store's update flow over a FAKE coordinator — checkForUpdates
 * (manifest → offer, only for installed downloadable plugins, never a
 * downgrade), the mandatory approval gate (NeedsApproval without approval),
 * fresh-manifest target building, failure/rollback propagation, bundled-exclusion
 * and the active-persisted-entry rule for a SECOND update.
 */
class PluginUpdateStoreFlowTest {

    private val baseIds = PluginRegistry.defaultPlugins().map { it.id }

    private val remoteId = "com.authorss81.noteflow.plugins.remote.ocr"

    private val testPin = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    /** A pin store that pins [remoteId] at every version these tests offer,
     *  at the digests [offer] uses, allowing the test artifact host. */
    private fun testPins(): CompileTimePluginPinStore = CompileTimePluginPinStore(
        PinnedPluginRelease(remoteId, PluginVersion(1, 0, 0), "sha-1.0.0", testPin),
        PinnedPluginRelease(remoteId, PluginVersion(1, 2, 0), "sha-1.2.0", testPin),
        PinnedPluginRelease(remoteId, PluginVersion(2, 0, 0), "sha-2.0.0", testPin),
        PinnedPluginRelease(remoteId, PluginVersion(3, 0, 0), "sha-3.0.0", testPin),
        PinnedPluginRelease(remoteId, PluginVersion(9, 0, 0), "sha-9.0.0", testPin),
        allowedDownloadHosts = setOf("plugins.example.com")
    )

    private fun remoteEntry(version: PluginVersion = PluginVersion(1, 0, 0)) = PluginEntry(
        id = remoteId,
        name = "Remote OCR",
        description = "Heavy downloadable OCR engine.",
        version = version,
        capabilities = setOf(PluginCapability.OCR),
        category = "Vision",
        downloadUrl = "https://plugins.example.com/ocr-$version.apk",
        sha256 = "sha-${version}",
        pinnedCertHash = testPin,
        source = PluginEntrySource.REMOTE
    )

    private fun offer(version: PluginVersion, id: String = remoteId) = HostedPluginVersion(
        id = id,
        version = version,
        downloadUrl = "https://plugins.example.com/ocr-$version.apk",
        sha256 = "sha-$version",
        pinnedCertHash = testPin,
        updateChannel = "stable"
    )

    /** Fake coordinator: returns a scripted manifest + outcome and records updates. */
    private class FakeCoordinator(
        var manifest: HostedPluginManifest?,
        var outcome: PluginStoreController.UpdateOutcome,
        var failMessage: String? = null
    ) : PluginUpdateCoordinator {
        val updateCalls = mutableListOf<Pair<PluginEntry, PluginEntry>>()

        override suspend fun fetchManifest(): ManifestFetchResult =
            failMessage?.let { ManifestFetchResult.Failed(it) }
                ?: ManifestFetchResult.Loaded(manifest ?: HostedPluginManifest(emptyList()))

        override suspend fun runUpdate(
            entry: PluginEntry,
            target: PluginEntry,
            userApproved: Boolean,
            onProgress: (Float) -> Unit
        ): PluginStoreController.UpdateOutcome {
            updateCalls.add(entry to target)
            onProgress(1f)
            return outcome
        }
    }

    /** Minimal installer fake so the store's `activeEntryFor` rule can be scripted. */
    private class FakeInstaller(
        private val active: MutableMap<String, PluginEntry>
    ) : RemotePluginInstaller {
        override fun isConsented(pluginId: String) = true
        override fun grantConsent(pluginId: String) {}
        override fun activeEntryFor(pluginId: String): PluginEntry? = active[pluginId]
        override suspend fun install(entry: PluginEntry, onProgress: (Float) -> Unit) =
            PluginStoreController.DownloadOutcome.Installed(entry.id)
        override fun deleteArtifact(entry: PluginEntry) { active.remove(entry.id) }
    }

    private fun newController(
        entryStore: InMemoryPluginEntryStore = InMemoryPluginEntryStore(),
        coordinator: PluginUpdateCoordinator? = null,
        remoteInstaller: RemotePluginInstaller? = null,
        pins: CompileTimePluginPinStore = testPins()
    ): Pair<PluginRegistry, PluginStoreController> {
        val registry = PluginRegistry(
            enableStore = InMemoryEnableStore(),
            settingsStore = InMemoryPluginSettingsStore(),
            installStore = InMemoryPluginInstallStore(baseIds),
            optionalPluginFactories = listOf({ CaseChangePlugin() }),
            currentApiLevel = 26
        )
        val catalog = PluginStoreCatalog(registry, entryStore)
        val controller = PluginStoreController(
            registry, catalog,
            remoteInstaller = remoteInstaller,
            updateCoordinator = coordinator,
            pins = pins
        )
        return registry to controller
    }

    /** Install a remote plugin into the registry the way a Phase-23 download leaves it. */
    private fun installRemote(registry: PluginRegistry) {
        assertTrue(
            registry.installPlugin(TestPlugin(id = remoteId, name = "Remote OCR", capabilities = setOf(PluginCapability.OCR)), null)
                is PluginInstallResult.Installed
        )
    }

    @Test
    fun `checkForUpdates lists a strictly newer offer for an installed remote plugin`() = runBlocking {
        val entryStore = InMemoryPluginEntryStore()
        entryStore.save(remoteEntry())
        val (registry, controller) = newController(
            entryStore = entryStore,
            coordinator = FakeCoordinator(HostedPluginManifest(listOf(offer(PluginVersion(1, 2, 0)))), PluginStoreController.UpdateOutcome.Failed(remoteId, "unused"))
        )
        installRemote(registry)

        val outcome = controller.checkForUpdates(null)

        assertTrue(outcome is PluginStoreController.UpdateCheckOutcome.UpdatesAvailable)
        val updates = (outcome as PluginStoreController.UpdateCheckOutcome.UpdatesAvailable).updates
        assertEquals(listOf(remoteId), updates.map { it.pluginId })
        assertEquals(PluginVersion(1, 2, 0), updates.first().newVersion)
    }

    @Test
    fun `checkForUpdates reports upToDate when nothing is strictly newer`() = runBlocking {
        val entryStore = InMemoryPluginEntryStore()
        entryStore.save(remoteEntry())
        val (registry, controller) = newController(
            entryStore = entryStore,
            coordinator = FakeCoordinator(HostedPluginManifest(listOf(offer(PluginVersion(1, 0, 0)))), PluginStoreController.UpdateOutcome.Failed(remoteId, "unused"))
        )
        installRemote(registry)

        assertTrue(controller.checkForUpdates(null) is PluginStoreController.UpdateCheckOutcome.UpToDate)
    }

    @Test
    fun `checkForUpdates surfaces a fetch failure honestly`() = runBlocking {
        val entryStore = InMemoryPluginEntryStore()
        entryStore.save(remoteEntry())
        val (registry, controller) = newController(
            entryStore = entryStore,
            coordinator = FakeCoordinator(null, PluginStoreController.UpdateOutcome.Failed(remoteId, "unused"), failMessage = "offline")
        )
        installRemote(registry)

        val outcome = controller.checkForUpdates(null)

        assertTrue(outcome is PluginStoreController.UpdateCheckOutcome.Failed)
        assertTrue((outcome as PluginStoreController.UpdateCheckOutcome.Failed).message.contains("offline"))
    }

    @Test
    fun `bundled plugins are never part of an update check`() = runBlocking {
        // No remote plugin installed — only bundled built-ins.
        val (_, controller) = newController(
            coordinator = FakeCoordinator(HostedPluginManifest(listOf(offer(PluginVersion(9, 0, 0)))), PluginStoreController.UpdateOutcome.Failed(remoteId, "unused"))
        )

        assertTrue(controller.checkForUpdates(null) is PluginStoreController.UpdateCheckOutcome.UpToDate)
    }

    @Test
    fun `an update without user approval answers NeedsApproval and never calls the coordinator`() = runBlocking {
        val entryStore = InMemoryPluginEntryStore()
        entryStore.save(remoteEntry())
        val coordinator = FakeCoordinator(HostedPluginManifest(listOf(offer(PluginVersion(1, 2, 0)))), PluginStoreController.UpdateOutcome.Updated(remoteId, PluginVersion(1, 0, 0), PluginVersion(1, 2, 0)))
        val (registry, controller) = newController(entryStore = entryStore, coordinator = coordinator)
        installRemote(registry)

        val outcome = controller.update(remoteId, userApproved = false, onProgress = {})

        assertTrue(outcome is PluginStoreController.UpdateOutcome.NeedsApproval)
        assertTrue(coordinator.updateCalls.isEmpty())
    }

    @Test
    fun `an approved update builds the target from a fresh manifest and swaps`() = runBlocking {
        val entryStore = InMemoryPluginEntryStore()
        entryStore.save(remoteEntry())
        val coordinator = FakeCoordinator(HostedPluginManifest(listOf(offer(PluginVersion(2, 0, 0)))), PluginStoreController.UpdateOutcome.Updated(remoteId, PluginVersion(1, 0, 0), PluginVersion(2, 0, 0)))
        val (registry, controller) = newController(entryStore = entryStore, coordinator = coordinator)
        installRemote(registry)

        val progress = mutableListOf<Float>()
        val outcome = controller.update(remoteId, userApproved = true, onProgress = { progress.add(it) })

        assertTrue(outcome is PluginStoreController.UpdateOutcome.Updated)
        assertEquals(PluginVersion(2, 0, 0), (outcome as PluginStoreController.UpdateOutcome.Updated).toVersion)
        // The coordinator received the CURRENT entry and the fresh-manifest target (v2).
        assertEquals(1, coordinator.updateCalls.size)
        assertEquals(PluginVersion(1, 0, 0), coordinator.updateCalls.first().first.version)
        val target = coordinator.updateCalls.first().second
        assertEquals(PluginVersion(2, 0, 0), target.version)
        assertTrue(progress.isNotEmpty())
        assertEquals(1f, progress.last(), 0f)
    }

    @Test
    fun `an update whose fresh manifest no longer offers a newer version fails`() = runBlocking {
        val entryStore = InMemoryPluginEntryStore()
        entryStore.save(remoteEntry())
        val coordinator = FakeCoordinator(HostedPluginManifest(emptyList()), PluginStoreController.UpdateOutcome.Updated(remoteId, PluginVersion(1, 0, 0), PluginVersion(2, 0, 0)))
        val (registry, controller) = newController(entryStore = entryStore, coordinator = coordinator)
        installRemote(registry)

        val outcome = controller.update(remoteId, userApproved = true, onProgress = {})

        assertTrue(outcome is PluginStoreController.UpdateOutcome.Failed)
        assertTrue((outcome as PluginStoreController.UpdateOutcome.Failed).message.contains("No newer version"))
        assertTrue(coordinator.updateCalls.isEmpty())
    }

    @Test
    fun `a bundled plugin update is refused as managed by app update`() = runBlocking {
        val (_, controller) = newController(
            coordinator = FakeCoordinator(HostedPluginManifest(listOf(offer(PluginVersion(2, 0, 0)))), PluginStoreController.UpdateOutcome.Failed(remoteId, "unused"))
        )
        val bundledId = baseIds.first()

        val outcome = controller.update(bundledId, userApproved = true, onProgress = {})

        assertTrue(outcome is PluginStoreController.UpdateOutcome.Failed)
        assertTrue((outcome as PluginStoreController.UpdateOutcome.Failed).message.contains("managed by the app update"))
    }

    @Test
    fun `a coordinator rollback is propagated`() = runBlocking {
        val entryStore = InMemoryPluginEntryStore()
        entryStore.save(remoteEntry())
        val coordinator = FakeCoordinator(
            HostedPluginManifest(listOf(offer(PluginVersion(2, 0, 0)))),
            PluginStoreController.UpdateOutcome.RolledBack(remoteId, "update failed; previous version active")
        )
        val (registry, controller) = newController(entryStore = entryStore, coordinator = coordinator)
        installRemote(registry)

        val outcome = controller.update(remoteId, userApproved = true, onProgress = {})

        assertTrue(outcome is PluginStoreController.UpdateOutcome.RolledBack)
        assertEquals(remoteId, (outcome as PluginStoreController.UpdateOutcome.RolledBack).pluginId)
    }

    @Test
    fun `a second update compares against the ACTIVE persisted entry not the stale catalog copy`() = runBlocking {
        val entryStore = InMemoryPluginEntryStore()
        entryStore.save(remoteEntry()) // catalog copy sits at v1.0.0
        val activeV2 = remoteEntry(PluginVersion(2, 0, 0)) // the ACTIVE version after the first update
        val installer = FakeInstaller(mutableMapOf(remoteId to activeV2))
        val coordinator = FakeCoordinator(HostedPluginManifest(listOf(offer(PluginVersion(3, 0, 0)))), PluginStoreController.UpdateOutcome.Updated(remoteId, PluginVersion(2, 0, 0), PluginVersion(3, 0, 0)))
        val (registry, controller) = newController(entryStore = entryStore, coordinator = coordinator, remoteInstaller = installer)
        installRemote(registry)

        val outcome = controller.update(remoteId, userApproved = true, onProgress = {})

        assertTrue(outcome is PluginStoreController.UpdateOutcome.Updated)
        assertEquals(PluginVersion(2, 0, 0), (outcome as PluginStoreController.UpdateOutcome.Updated).fromVersion)
        assertEquals(1, coordinator.updateCalls.size)
        // The current version handed to the coordinator is the ACTIVE v2, never the stale catalog v1.
        assertEquals(PluginVersion(2, 0, 0), coordinator.updateCalls.first().first.version)
    }

    @Test
    fun `checkForUpdates refuses a forged offer even when it is strictly newer - B1-NET-03`() = runBlocking {
        val entryStore = InMemoryPluginEntryStore()
        entryStore.save(remoteEntry())
        // The offer is structurally valid and strictly newer, but its sha256 does
        // NOT match the compile-time pin — the store must not list it.
        val forged = offer(PluginVersion(1, 2, 0)).copy(sha256 = "f00d")
        val (registry, controller) = newController(
            entryStore = entryStore,
            coordinator = FakeCoordinator(HostedPluginManifest(listOf(forged)), PluginStoreController.UpdateOutcome.Failed(remoteId, "unused"))
        )
        installRemote(registry)

        val outcome = controller.checkForUpdates(null)

        assertTrue(outcome is PluginStoreController.UpdateCheckOutcome.UpToDate)
    }

    @Test
    fun `update and checkForUpdates fail when no coordinator is wired`() = runBlocking {
        val entryStore = InMemoryPluginEntryStore()
        entryStore.save(remoteEntry())
        val (registry, controller) = newController(entryStore = entryStore)
        installRemote(registry)

        assertTrue(controller.checkForUpdates(null) is PluginStoreController.UpdateCheckOutcome.Failed)
        assertTrue(controller.update(remoteId, userApproved = true, onProgress = {}) is PluginStoreController.UpdateOutcome.Failed)
    }
}