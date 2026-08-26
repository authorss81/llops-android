package com.authorss81.noteflow.services

import com.authorss81.noteflow.InMemoryEnableStore
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.runtime.PluginDownloader
import com.authorss81.noteflow.plugins.runtime.RuntimeOutcome
import com.authorss81.noteflow.plugins.store.InMemoryPluginInstallStore
import com.authorss81.noteflow.plugins.store.PluginStoreController
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 212: [DownloadablePluginInstaller] — happy path + failure atomicity.
 * A failed install step (download guard, missing pins, verification, load)
 * must leave NO partial state: no persisted catalog entry, no artifact file,
 * no registry install. Real [SettingsManager] + real [PluginArtifactStorage]
 * over fake prefs/temp dirs; transport + runtime are fault-injected fakes.
 */
class DownloadablePluginInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val id = "com.example.remote.plugin"

    private class Fixture(
        val prefs: FakePrefs,
        val settings: SettingsManager,
        val entryStore: SettingsPluginEntryStore,
        val storage: PluginArtifactStorage,
        val runtime: FakePluginRuntime,
        val transport: WritingFakeTransport,
        val registry: PluginRegistry,
        val installer: DownloadablePluginInstaller
    )

    private fun newFixture(verifyOk: Boolean = true, loadOk: Boolean = true): Fixture {
        val prefs = FakePrefs()
        val files = tmp.newFolder("files-" + System.nanoTime())
        val settings = settingsOver(prefs, files)
        val entryStore = SettingsPluginEntryStore(settings)
        val storage = PluginArtifactStorage(FakeContext(prefs, files))
        val runtime = FakePluginRuntime()
        if (!verifyOk) runtime.verifyOutcome = RuntimeOutcome.Failed("sha256 mismatch")
        if (!loadOk) runtime.loadOutcome = RuntimeOutcome.Failed("dex could not be loaded")
        val transport = WritingFakeTransport()
        val downloader = PluginDownloader(
            transport = transport,
            allowedDownloadHosts = setOf("plugins.example.com")
        )
        val registry = PluginRegistry(
            enableStore = InMemoryEnableStore(),
            plugins = emptyList(),
            installStore = InMemoryPluginInstallStore(emptyList()),
            currentApiLevel = 26
        )
        val installer = DownloadablePluginInstaller(
            settings = settings,
            registry = registry,
            entryStore = entryStore,
            storage = storage,
            runtime = runtime,
            downloader = downloader
        )
        return Fixture(prefs, settings, entryStore, storage, runtime, transport, registry, installer)
    }

    @Test
    fun `consent is persisted through SettingsManager`() {
        val f = newFixture()

        assertFalse(f.installer.isConsented(id))
        f.installer.grantConsent(id)
        assertTrue(f.installer.isConsented(id))
        // And it is wiped by a store Delete:
        f.settings.wipePluginState(id)
        assertFalse(f.installer.isConsented(id))
    }

    @Test
    fun `happy path installs REGISTERED-off with artifact and persisted entry`() = runBlocking {
        val f = newFixture()
        val progress = mutableListOf<Float>()

        val outcome = f.installer.install(Phase212Fakes.remoteEntry(), { progress.add(it) })

        assertEquals(PluginStoreController.DownloadOutcome.Installed(id), outcome)
        assertEquals("progress ends at 1f", 1f, progress.last(), 0f)
        // Registry install: present but OFF by default.
        assertTrue(f.registry.allPlugins.any { it.id == id })
        assertFalse(f.registry.isEnabled(id))
        // The verified artifact landed in app-private storage and the entry persisted.
        assertNotNull(f.storage.artifactFor(Phase212Fakes.remoteEntry()))
        assertEquals(id, f.entryStore.find(id)?.id)
        // The runtime saw verify BEFORE load, with the pinned digests from the entry.
        assertEquals(1, f.runtime.verifiedArtifacts.size)
        assertEquals(1, f.runtime.loadedEntries.size)
    }

    @Test
    fun `unextractable payloads keep the plugin installed but write no payload marker`() = runBlocking {
        // Phase 175 contract: a refused payload extraction must NOT fail the
        // install — the verified+loaded plugin stays installed-but-unavailable
        // (its availability gate reports the missing payloads). The default
        // transport writes non-zip bytes, so JarFile parsing refuses below.
        val f = newFixture()

        val outcome = f.installer.install(Phase212Fakes.remoteEntry(), {})

        assertEquals(PluginStoreController.DownloadOutcome.Installed(id), outcome)
        assertTrue("a refused extraction must keep the registry install", f.registry.allPlugins.any { it.id == id })
        assertFalse(f.registry.isEnabled(id))
        assertNull(
            "a refused extraction must not write any payload file",
            f.storage.payloadDir(id).listFiles()?.firstOrNull()
        )
    }

    @Test
    fun `a refused download leaves no partial state`() = runBlocking {
        val f = newFixture()
        f.transport.failInstead = true

        val outcome = f.installer.install(Phase212Fakes.remoteEntry(), {})

        assertTrue(outcome is PluginStoreController.DownloadOutcome.Failed)
        assertNoResidue(f.entryStore, f.storage.dir(), id)
        assertFalse(f.registry.allPlugins.any { it.id == id })
    }

    @Test
    fun `a non-HTTPS downloadUrl is refused before any byte moves`() = runBlocking {
        val f = newFixture()

        val outcome = f.installer.install(Phase212Fakes.remoteEntry(https = false), {})

        assertTrue(outcome is PluginStoreController.DownloadOutcome.Failed)
        assertTrue(f.transport.requestedUrls.isEmpty())
        assertNoResidue(f.entryStore, f.storage.dir(), id)
    }

    @Test
    fun `a pre-existing artifact without pinned sha256 fails clean (MISSING_SHA256)`() = runBlocking {
        val f = newFixture()
        val entry = Phase212Fakes.remoteEntry(id = id, sha256 = null, pinnedCertHash = null)
        // Simulate an artifact already on disk (interrupted earlier run):
        val artifactFile = f.storage.artifactFile(entry)
        artifactFile.parentFile.mkdirs()
        artifactFile.writeBytes("stale".toByteArray())

        val outcome = f.installer.install(entry, {})

        assertTrue(outcome is PluginStoreController.DownloadOutcome.Failed)
        assertNoResidue(f.entryStore, f.storage.dir(), id)
    }

    @Test
    fun `a verification failure removes artifact and entry`() = runBlocking {
        val f = newFixture(verifyOk = false)

        val outcome = f.installer.install(Phase212Fakes.remoteEntry(), {})

        assertTrue(outcome is PluginStoreController.DownloadOutcome.Failed)
        assertTrue((outcome as PluginStoreController.DownloadOutcome.Failed).message.contains("sha256"))
        assertNoResidue(f.entryStore, f.storage.dir(), id)
        assertFalse(f.registry.allPlugins.any { it.id == id })
    }

    @Test
    fun `a load failure removes artifact and entry`() = runBlocking {
        val f = newFixture(loadOk = false)

        val outcome = f.installer.install(Phase212Fakes.remoteEntry(), {})

        assertTrue(outcome is PluginStoreController.DownloadOutcome.Failed)
        assertEquals("verification ran before the failed load", 1, f.runtime.verifiedArtifacts.size)
        assertNoResidue(f.entryStore, f.storage.dir(), id)
        assertFalse(f.registry.allPlugins.any { it.id == id })
    }

    @Test
    fun `deleteArtifact removes both the file and the persisted entry`() = runBlocking {
        val f = newFixture()
        val entry = Phase212Fakes.remoteEntry()
        f.installer.install(entry, {})
        assertNotNull(f.storage.artifactFor(entry))

        f.installer.deleteArtifact(entry)

        assertNull(f.storage.artifactFor(entry))
        assertNull(f.entryStore.find(id))
    }

    @Test
    fun `the installer never echoes hostile download urls into its log lines`() = runBlocking {
        // B2-LOG-04 contract: failure logs carry FIXED reason codes only. The
        // fixture logger records every line; none may contain the URL host.
        val logged = mutableListOf<String>()
        val f = newFixture()
        f.transport.failInstead = true
        val loggingInstaller = DownloadablePluginInstaller(
            settings = f.settings,
            registry = f.registry,
            entryStore = f.entryStore,
            storage = f.storage,
            runtime = f.runtime,
            downloader = PluginDownloader(
                transport = f.transport,
                allowedDownloadHosts = setOf("plugins.example.com")
            ),
            logger = object : com.authorss81.noteflow.plugins.PluginLogger {
                override fun lifecycle(event: String, pluginId: String, pluginName: String) {
                    logged += "$event|$pluginId|$pluginName"
                }

                override fun error(pluginId: String, pluginName: String, detail: String) {
                    logged += "$pluginId|$pluginName|$detail"
                }
            }
        )

        loggingInstaller.install(Phase212Fakes.remoteEntry(), {})
        logged.forEach { line ->
            assertFalse("log must never contain the download host", line.contains("plugins.example.com"))
        }
        Unit
    }
}
