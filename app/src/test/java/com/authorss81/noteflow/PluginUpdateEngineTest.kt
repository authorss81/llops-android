package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.runtime.ArtifactSignatureVerifier
import com.authorss81.noteflow.plugins.runtime.ClassLoaderFactory
import com.authorss81.noteflow.plugins.runtime.DownloadRequest
import com.authorss81.noteflow.plugins.runtime.DownloadTransport
import com.authorss81.noteflow.plugins.runtime.DownloadTransportResult
import com.authorss81.noteflow.plugins.runtime.InMemoryPluginEntryStore
import com.authorss81.noteflow.plugins.runtime.InMemoryPluginUpdateStore
import com.authorss81.noteflow.plugins.runtime.PluginArtifactResolver
import com.authorss81.noteflow.plugins.runtime.PluginContextFactory
import com.authorss81.noteflow.plugins.runtime.PluginDownloader
import com.authorss81.noteflow.plugins.runtime.PluginEntry
import com.authorss81.noteflow.plugins.runtime.PluginEntrySource
import com.authorss81.noteflow.plugins.runtime.PluginUpdateEngine
import com.authorss81.noteflow.plugins.runtime.PluginUpdateStore
import com.authorss81.noteflow.plugins.runtime.PluginVersion
import com.authorss81.noteflow.plugins.runtime.RuntimeOutcome
import com.authorss81.noteflow.plugins.runtime.RuntimePluginLoader
import java.io.File
import java.net.URLClassLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 24: the verified-update engine — approval gate, no-downgrade rule,
 * download → sha256+pinned-cert re-verify → load smoke-test → keep previous →
 * atomic swap, and rollback to the recorded previous version. Uses REAL signed
 * test artifacts (jarsigner + keystore, as in the Phase-23 runtime tests) with
 * a fake download transport, so the full trust chain is exercised in pure JVM.
 */
class PluginUpdateEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val installedId = TestDownloadablePlugin.TEST_PLUGIN_ID

    private fun entryFor(
        version: PluginVersion,
        sha: String,
        pin: String,
        stock: TestArtifactBuilder.SignedArtifact
    ): PluginEntry = PluginEntry(
        id = installedId,
        name = "Remote Test",
        description = "Remote test plugin.",
        version = version,
        capabilities = setOf(PluginCapability.TextTransform),
        category = "Text",
        downloadUrl = "https://plugins.example.com/test-$version.apk",
        sha256 = sha,
        pinnedCertHash = pin,
        source = PluginEntrySource.REMOTE
    )

    /** A transport that "downloads" into [request.target] by copying [payload]. */
    private class PayloadTransport(
        private val payload: File,
        private val failMessage: String? = null
    ) : DownloadTransport {
        override suspend fun download(request: DownloadRequest): DownloadTransportResult {
            val message = failMessage
            if (message != null) return DownloadTransportResult.Failed(message)
            payload.copyTo(request.target, overwrite = true)
            return DownloadTransportResult.Completed(request.target.length())
        }
    }

    private class EngineFixture(
        val storageDir: File,
        val entryStore: InMemoryPluginEntryStore,
        val updateStore: PluginUpdateStore,
        val engine: PluginUpdateEngine,
        transport: PayloadTransport
    )

    private fun newEngine(payload: File, failMessage: String? = null): EngineFixture {
        val storageDir = File(tmp.root, "plugins").apply { mkdirs() }
        val entryStore = InMemoryPluginEntryStore()
        val updateStore = InMemoryPluginUpdateStore()
        val transport = PayloadTransport(payload, failMessage)
        val downloader = PluginDownloader(transport, freeSpace = { Long.MAX_VALUE })
        val loader = RuntimePluginLoader(
            classLoaderFactory = ClassLoaderFactory { artifactPath, parent ->
                URLClassLoader(arrayOf(File(artifactPath).toURI().toURL()), parent)
            },
            contextFactory = PluginContextFactory.DEFAULT,
            parentClassLoader = TestDownloadablePlugin::class.java.classLoader ?: javaClass.classLoader
        )
        val resolver = com.authorss81.noteflow.plugins.runtime.PluginArtifactResolver { e ->
            storageDir.listFiles()?.firstOrNull { it.name == PluginDownloader.artifactFileNameFor(e) }
        }
        val engine = PluginUpdateEngine(
            downloader = downloader,
            storageDir = storageDir,
            artifactResolver = resolver,
            entryStore = entryStore,
            updateStore = updateStore,
            verifier = ArtifactSignatureVerifier(),
            loader = loader
        )
        return EngineFixture(storageDir, entryStore, updateStore, engine, transport)
    }

    private fun signedFixture(): Pair<EngineFixture, Triple<TestArtifactBuilder.SignedArtifact, TestArtifactBuilder.SignedArtifact, com.authorss81.noteflow.plugins.runtime.PluginEntry>> {
        val ks = TestArtifactBuilder.newKeystore(tmp.root, "engine-signer")
        val v1 = TestArtifactBuilder.build(tmp.root, ks)
        val v2 = TestArtifactBuilder.build(tmp.root, ks)
        val entry = entryFor(PluginVersion(1, 0, 0), v1.sha256Hex, v1.pinnedCertHash, v1)
        val target = entryFor(PluginVersion(2, 0, 0), v2.sha256Hex, v2.pinnedCertHash, v2)
        return newEngine(payload = v2.file) to Triple(v1, v2, entry)
    }

    /** Simulate a previously-installed version: its artifact is already on disk. */
    private fun placeOnDisk(fixture: EngineFixture, entry: PluginEntry, artifact: TestArtifactBuilder.SignedArtifact) {
        artifact.file.copyTo(
            File(fixture.storageDir, PluginDownloader.artifactFileNameFor(entry)),
            overwrite = true
        )
    }

    @Test
    fun `an approved update downloads re-verifies smoke-tests swaps and keeps the previous version`() = runBlocking {
        val (fixture, artifacts) = signedFixture()
        val _v1 = artifacts.first
        val v2 = artifacts.second
        val entry = artifacts.third
        fixture.entryStore.save(entry)
        val target = entryFor(PluginVersion(2, 0, 0), v2.sha256Hex, v2.pinnedCertHash, v2)
        val progress = mutableListOf<Float>()

        val result = fixture.engine.update(entry, target, userApproved = true, onProgress = { progress.add(it) })

        assertTrue("update -> ${(result as? RuntimeOutcome.Failed)?.message}", result is RuntimeOutcome.Success)
        val active = fixture.entryStore.find(installedId)!!
        assertEquals(PluginVersion(2, 0, 0), active.version)
        // The active entry's persisted digests are the NEW artifact's digests.
        assertEquals(v2.sha256Hex, active.sha256)
        // The new artifact is on disk and the previous version is recorded (rollback root).
        assertTrue(File(fixture.storageDir, PluginDownloader.artifactFileNameFor(target)).isFile)
        assertEquals(PluginVersion(1, 0, 0), fixture.updateStore.previousFor(installedId)?.version)
        // Progress reached 100% (real, monotonic end state).
        assertEquals(1f, progress.last(), 0f)
        assertTrue(progress.first() == 0f)
    }

    @Test
    fun `an update without user approval is refused and nothing moves`() = runBlocking {
        val (fixture, artifacts) = signedFixture()
        val _v1 = artifacts.first
        val v2 = artifacts.second
        val entry = artifacts.third
        fixture.entryStore.save(entry)
        val target = entryFor(PluginVersion(2, 0, 0), v2.sha256Hex, v2.pinnedCertHash, v2)

        val result = fixture.engine.update(entry, target, userApproved = false, onProgress = {})

        assertTrue(result is RuntimeOutcome.Failed)
        assertTrue((result as RuntimeOutcome.Failed).message.contains("not approved"))
        // Nothing moved: the active entry is untouched, and no previous version was recorded.
        assertEquals(PluginVersion(1, 0, 0), fixture.entryStore.find(installedId)?.version)
        assertNull(fixture.updateStore.previousFor(installedId))
        assertTrue(File(fixture.storageDir, PluginDownloader.artifactFileNameFor(target)).isFile.not())
    }

    @Test
    fun `a downgrade or no-op target is refused`() = runBlocking {
        val ks = TestArtifactBuilder.newKeystore(tmp.root, "downgrade-signer")
        val v1 = TestArtifactBuilder.build(tmp.root, ks)
        val entry = entryFor(PluginVersion(1, 0, 0), v1.sha256Hex, v1.pinnedCertHash, v1)
        val fixture = newEngine(payload = v1.file)
        fixture.entryStore.save(entry)

        // Equal version → no-op, refused.
        val noOp = entryFor(PluginVersion(1, 0, 0), v1.sha256Hex, v1.pinnedCertHash, v1)
        val noOpResult = fixture.engine.update(entry, noOp, userApproved = true, onProgress = {})
        assertTrue(noOpResult is RuntimeOutcome.Failed)
        assertTrue((noOpResult as RuntimeOutcome.Failed).message.contains("not newer"))

        // Older version → downgrade, refused.
        val downgrade = entryFor(PluginVersion(0, 9, 0), v1.sha256Hex, v1.pinnedCertHash, v1)
        val downResult = fixture.engine.update(entry, downgrade, userApproved = true, onProgress = {})
        assertTrue(downResult is RuntimeOutcome.Failed)
        assertTrue((downResult as RuntimeOutcome.Failed).message.contains("not newer"))
        assertEquals(PluginVersion(1, 0, 0), fixture.entryStore.find(installedId)?.version)
    }

    @Test
    fun `a download failure keeps the previous version active`() = runBlocking {
        val (_v1, _v2, entry) = signedFixture().second
        val fixture = newEngine(payload = _v2.file, failMessage = "no bytes from server")
        fixture.entryStore.save(entry)
        val target = entryFor(PluginVersion(2, 0, 0), _v2.sha256Hex, _v2.pinnedCertHash, _v2)

        val result = fixture.engine.update(entry, target, userApproved = true, onProgress = {})

        assertTrue(result is RuntimeOutcome.Failed)
        assertTrue((result as RuntimeOutcome.Failed).message.contains("still active"))
        // The active entry is untouched and the failed artifact is gone.
        assertEquals(PluginVersion(1, 0, 0), fixture.entryStore.find(installedId)?.version)
        assertTrue(File(fixture.storageDir, PluginDownloader.artifactFileNameFor(target)).isFile.not())
        // The previous version was still recorded BEFORE the failure (rollback root).
        assertEquals(PluginVersion(1, 0, 0), fixture.updateStore.previousFor(installedId)?.version)
    }

    @Test
    fun `a hash mismatch on the downloaded artifact is never applied`() = runBlocking {
        val (_v1, _v2, entry) = signedFixture().second
        // Serve the v1 bytes while the target promises v2's digests → SHA-256 mismatch.
        val fixture = newEngine(payload = _v1.file)
        fixture.entryStore.save(entry)
        val target = entryFor(PluginVersion(2, 0, 0), _v2.sha256Hex, _v2.pinnedCertHash, _v2)

        val result = fixture.engine.update(entry, target, userApproved = true, onProgress = {})

        assertTrue(result is RuntimeOutcome.Failed)
        assertTrue((result as RuntimeOutcome.Failed).message.contains("signature verification"))
        assertEquals(PluginVersion(1, 0, 0), fixture.entryStore.find(installedId)?.version)
        assertTrue(File(fixture.storageDir, PluginDownloader.artifactFileNameFor(target)).isFile.not())
    }

    @Test
    fun `a signed-but-broken artifact fails its load smoke-test and is refused`() = runBlocking {
        val ks = TestArtifactBuilder.newKeystore(tmp.root, "broken-signer")
        // NotAPlugin is in the test sources but is NOT a NoteflowPlugin — the
        // artifact is correctly signed but its class cannot load as a plugin.
        val broken = TestArtifactBuilder.build(tmp.root, ks, pluginClassName = NotAPlugin::class.java.name)
        val _v1 = TestArtifactBuilder.build(tmp.root, ks)
        val entry = entryFor(PluginVersion(1, 0, 0), _v1.sha256Hex, _v1.pinnedCertHash, _v1)
        val fixture = newEngine(payload = broken.file)
        fixture.entryStore.save(entry)
        val target = entryFor(PluginVersion(2, 0, 0), broken.sha256Hex, broken.pinnedCertHash, broken)

        val result = fixture.engine.update(entry, target, userApproved = true, onProgress = {})

        assertTrue(result is RuntimeOutcome.Failed)
        assertTrue((result as RuntimeOutcome.Failed).message.contains("smoke-test"))
        assertEquals(PluginVersion(1, 0, 0), fixture.entryStore.find(installedId)?.version)
        assertTrue(File(fixture.storageDir, PluginDownloader.artifactFileNameFor(target)).isFile.not())
    }

    @Test
    fun `rollback restores the recorded previous verified version`() = runBlocking {
        val (fixture, artifacts) = signedFixture()
        val _v1 = artifacts.first
        val v2 = artifacts.second
        val entry = artifacts.third
        fixture.entryStore.save(entry)
        // The installed (previous) version's artifact is already on disk, as a
        // real download would have left it — the rollback source.
        placeOnDisk(fixture, entry, _v1)
        val target = entryFor(PluginVersion(2, 0, 0), v2.sha256Hex, v2.pinnedCertHash, v2)
        assertTrue(fixture.engine.update(entry, target, userApproved = true, onProgress = {}) is RuntimeOutcome.Success)

        // Roll the plugin back from v2 to the recorded v1.
        val activeV2 = fixture.entryStore.find(installedId)!!
        val result = fixture.engine.rollback(activeV2)

        assertTrue("rollback -> ${(result as? RuntimeOutcome.Failed)?.message}", result is RuntimeOutcome.Success)
        assertEquals(PluginVersion(1, 0, 0), fixture.entryStore.find(installedId)?.version)
        // The new (rolled-back-from) artifact is removed; the previous record is cleared.
        assertTrue(File(fixture.storageDir, PluginDownloader.artifactFileNameFor(target)).isFile.not())
        assertNull(fixture.updateStore.previousFor(installedId))
    }

    @Test
    fun `rollback with no recorded previous version fails honestly`() = runBlocking {
        val artifacts = signedFixture().second
        val _v1 = artifacts.first
        val _v2 = artifacts.second
        val entry = artifacts.third
        val fixture = newEngine(payload = _v2.file)
        fixture.entryStore.save(entry)

        val result = fixture.engine.rollback(entry)

        assertTrue(result is RuntimeOutcome.Failed)
        assertTrue((result as RuntimeOutcome.Failed).message.contains("nothing to roll back"))
    }

    @Test
    fun `rollback refuses a previous artifact that no longer passes verification`() = runBlocking {
        val (fixture, artifacts) = signedFixture()
        val _v1 = artifacts.first
        val v2 = artifacts.second
        val entry = artifacts.third
        fixture.entryStore.save(entry)
        placeOnDisk(fixture, entry, _v1)
        val target = entryFor(PluginVersion(2, 0, 0), v2.sha256Hex, v2.pinnedCertHash, v2)
        assertTrue(fixture.engine.update(entry, target, userApproved = true, onProgress = {}) is RuntimeOutcome.Success)

        // Tamper the previous version's artifact ON DISK — rollback must refuse it.
        val previousFile = File(fixture.storageDir, com.authorss81.noteflow.plugins.runtime.PluginDownloader.artifactFileNameFor(entry))
        previousFile.appendBytes(byteArrayOf(0x7f))

        val result = fixture.engine.rollback(fixture.entryStore.find(installedId)!!)

        assertTrue(result is RuntimeOutcome.Failed)
        assertTrue((result as RuntimeOutcome.Failed).message.contains("no longer passes verification"))
        // The active v2 is untouched.
        assertEquals(PluginVersion(2, 0, 0), fixture.entryStore.find(installedId)?.version)
    }

    @Test
    fun `a failed update leaves the previous version as active and rollback no-ops to it`() = runBlocking {
        val artifacts = signedFixture().second
        val _v1 = artifacts.first
        val _v2 = artifacts.second
        val entry = artifacts.third
        val fixture = newEngine(payload = _v2.file, failMessage = "server error")
        fixture.entryStore.save(entry)
        val target = entryFor(PluginVersion(2, 0, 0), _v2.sha256Hex, _v2.pinnedCertHash, _v2)
        assertTrue(fixture.engine.update(entry, target, userApproved = true, onProgress = {}) is RuntimeOutcome.Failed)
        // Active entry is still v1; the recorded previous is ALSO v1 (the update
        // never applied), so a rollback is an honest no-op success.
        assertEquals(PluginVersion(1, 0, 0), fixture.entryStore.find(installedId)?.version)

        val result = fixture.engine.rollback(fixture.entryStore.find(installedId)!!)

        assertTrue(result is RuntimeOutcome.Success)
        assertEquals(PluginVersion(1, 0, 0), (result as RuntimeOutcome.Success).value.restoredVersion)
    }
}