package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.TextTransformPlugin
import com.authorss81.noteflow.plugins.runtime.ClassLoaderFactory
import com.authorss81.noteflow.plugins.runtime.LoadedPlugin
import com.authorss81.noteflow.plugins.runtime.PluginArtifact
import com.authorss81.noteflow.plugins.runtime.PluginArtifactResolver
import com.authorss81.noteflow.plugins.runtime.PluginContextFactory
import com.authorss81.noteflow.plugins.runtime.PluginEntry
import com.authorss81.noteflow.plugins.runtime.PluginEntrySource
import com.authorss81.noteflow.plugins.runtime.PluginVersion
import com.authorss81.noteflow.plugins.runtime.RuntimeOutcome
import com.authorss81.noteflow.plugins.runtime.SignatureVerifiedPluginRuntime
import java.net.URLClassLoader
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 23: the real runtime — download → verify (sha256 + pinned cert) → load
 * (descriptor → classloader → instantiate → capability facade) → execute.
 * A tampered artifact or a wrong signing key is rejected BEFORE any plugin code
 * is materialized, and verification is re-run on EVERY load.
 *
 * Pure JVM: the plugin classloader is a URLClassLoader over the signed test
 * jar (production uses DexClassLoader via AppClassLoaderFactory); the download
 * step is exercised in PluginDownloaderTest with a fake transport.
 */
class DownloadablePluginRuntimeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun remoteEntryFor(artifact: TestArtifactBuilder.SignedArtifact, id: String = TestDownloadablePlugin.TEST_PLUGIN_ID) =
        PluginEntry(
            id = id,
            name = "Remote Test",
            description = "Remote test plugin.",
            version = PluginVersion(1, 0, 0),
            capabilities = setOf(PluginCapability.TextTransform),
            category = "Text",
            downloadUrl = "https://plugins.example.com/test.apk",
            sha256 = artifact.sha256Hex,
            pinnedCertHash = artifact.pinnedCertHash,
            source = PluginEntrySource.REMOTE
        )

    private fun resolverFor(entry: PluginEntry, file: java.io.File) =
        // Mirrors PluginArtifactStorage.artifactFor: only an existing file is a
        // downloadable artifact (a missing one is "not downloaded").
        PluginArtifactResolver { e -> if (e.id == entry.id && file.isFile) file else null }

    private fun urlClassLoaderFactory() = ClassLoaderFactory { artifactPath, parent ->
        URLClassLoader(arrayOf(java.io.File(artifactPath).toURI().toURL()), parent)
    }

    private fun runtime(
        entry: PluginEntry,
        file: java.io.File
    ) = SignatureVerifiedPluginRuntime(
        artifactResolver = resolverFor(entry, file),
        classLoaderFactory = urlClassLoaderFactory(),
        contextFactory = PluginContextFactory.capabilityAware(RecordingHostCompat()),
        parentClassLoader = TestDownloadablePlugin::class.java.classLoader ?: javaClass.classLoader
    )

    @Test
    fun `download-then-load-then-execute happy path`() {
        val ks = TestArtifactBuilder.newKeystore(tmp.root, "happy-signer")
        val artifact = TestArtifactBuilder.build(tmp.root, ks)
        val entry = remoteEntryFor(artifact)
        val rt = runtime(entry, artifact.file)

        // verify: both digests match → Verified.
        val verification = rt.verify(PluginArtifact(entry, artifact.file.absolutePath, entry.sha256.orEmpty(), entry.pinnedCertHash.orEmpty()))
        assertTrue(verification is RuntimeOutcome.Success)
        assertEquals(artifact.sha256Hex, (verification as RuntimeOutcome.Success).value.sha256)

        // load: re-verifies, then materializes the plugin.
        val loaded = rt.load(entry)
        assertTrue("load -> ${(loaded as? RuntimeOutcome.Failed)?.message}", loaded is RuntimeOutcome.Success)
        val loadedPlugin = (loaded as RuntimeOutcome.Success).value

        assertEquals(entry.id, loadedPlugin.entry.id)
        assertEquals(TestDownloadablePlugin.TEST_PLUGIN_ID, loadedPlugin.plugin.id)
        assertTrue(loadedPlugin.plugin is TextTransformPlugin)

        // execute: the materialized plugin really serves its capability.
        val result = (loadedPlugin.plugin as TextTransformPlugin).transformText("hello")
        assertEquals("remote:HELLO", result)

        // the plugin received its capability-aware context at load time.
        val injected = (loadedPlugin.plugin as TestDownloadablePlugin).injectedContext
        assertTrue(injected != null)
        assertEquals(entry.id, injected?.pluginId)
    }

    @Test
    fun `a tampered artifact is rejected before load - plugin code never materializes`() {
        val ks = TestArtifactBuilder.newKeystore(tmp.root, "tamper-signer")
        val artifact = TestArtifactBuilder.build(tmp.root, ks)
        val entry = remoteEntryFor(artifact)
        // Use the artifact's OWN sha/pin in the entry, then tamper the file.
        val tampered = java.io.File(tmp.root, "tampered-runtime.jar")
        artifact.file.copyTo(tampered)
        tampered.appendBytes(byteArrayOf(0x42))

        val rt = runtime(entry, tampered)

        val loaded = rt.load(entry)
        assertTrue(loaded is RuntimeOutcome.Failed)
        assertTrue((loaded as RuntimeOutcome.Failed).message.contains("SHA-256"))
    }

    @Test
    fun `an artifact signed by the wrong key is rejected before load`() {
        val signerA = TestArtifactBuilder.newKeystore(tmp.root, "runtime-signer-a")
        val signerB = TestArtifactBuilder.newKeystore(tmp.root, "runtime-signer-b")
        val artA = TestArtifactBuilder.build(tmp.root, signerA)
        val artB = TestArtifactBuilder.build(tmp.root, signerB)

        // Entry claims A's sha but B's pin: the cert check must fail.
        val entry = remoteEntryFor(artA).copy(pinnedCertHash = artB.pinnedCertHash)
        val rt = runtime(entry, artA.file)

        val loaded = rt.load(entry)
        assertTrue(loaded is RuntimeOutcome.Failed)
        assertTrue((loaded as RuntimeOutcome.Failed).message.contains("certificate"))
    }

    @Test
    fun `load re-verifies on every call even after a previous success`() {
        val ks = TestArtifactBuilder.newKeystore(tmp.root, "reverify-signer")
        val artifact = TestArtifactBuilder.build(tmp.root, ks)
        val entry = remoteEntryFor(artifact)
        val tampered = java.io.File(tmp.root, "reverify-tampered.jar")
        artifact.file.copyTo(tampered)

        val fileHolder = AtomicReference(tampered)
        val rt = SignatureVerifiedPluginRuntime(
            artifactResolver = PluginArtifactResolver { fileHolder.get() },
            classLoaderFactory = urlClassLoaderFactory(),
            contextFactory = PluginContextFactory.capabilityAware(RecordingHostCompat()),
            parentClassLoader = TestDownloadablePlugin::class.java.classLoader ?: javaClass.classLoader
        )

        // First load: file intact → success.
        val first = rt.load(entry)
        assertTrue(first is RuntimeOutcome.Success)

        // Now tamper the artifact AFTER the first successful load.
        fileHolder.set(java.io.File(tmp.root, "reverify-tampered-after.jar").apply {
            artifact.file.copyTo(this)
            appendBytes(byteArrayOf(0x7f))
        })

        // Second load MUST re-verify and refuse the now-tampered artifact.
        val second = rt.load(entry)
        assertTrue(second is RuntimeOutcome.Failed)
        assertTrue((second as RuntimeOutcome.Failed).message.contains("SHA-256"))
    }

    @Test
    fun `a bundled entry is never loaded through the runtime`() {
        val ks = TestArtifactBuilder.newKeystore(tmp.root, "bundled-signer")
        val artifact = TestArtifactBuilder.build(tmp.root, ks)
        val bundled = remoteEntryFor(artifact).copy(
            source = PluginEntrySource.BUNDLED,
            downloadUrl = null,
            sha256 = null,
            pinnedCertHash = null
        )
        val rt = runtime(bundled, artifact.file)

        val loaded = rt.load(bundled)
        assertTrue(loaded is RuntimeOutcome.Failed)
        assertTrue((loaded as RuntimeOutcome.Failed).message.contains("bundled"))
    }

    @Test
    fun `a missing artifact is reported honestly`() {
        val ks = TestArtifactBuilder.newKeystore(tmp.root, "missing-signer")
        val artifact = TestArtifactBuilder.build(tmp.root, ks)
        val entry = remoteEntryFor(artifact)
        val rt = runtime(entry, java.io.File(tmp.root, "not-on-disk.apk"))

        val loaded = rt.load(entry)
        assertTrue(loaded is RuntimeOutcome.Failed)
        assertTrue((loaded as RuntimeOutcome.Failed).message.contains("no downloaded artifact"))
    }

    @Test
    fun `update and rollback are honest Phase-24 stubs`() {
        val ks = TestArtifactBuilder.newKeystore(tmp.root, "stub-signer")
        val artifact = TestArtifactBuilder.build(tmp.root, ks)
        val entry = remoteEntryFor(artifact)
        val rt = runtime(entry, artifact.file)

        val update = rt.update(entry, PluginVersion(1, 1, 0))
        assertTrue(update is RuntimeOutcome.NotYetImplemented)
        assertEquals(24, (update as RuntimeOutcome.NotYetImplemented).phase)

        val rollback = rt.rollback(entry)
        assertTrue(rollback is RuntimeOutcome.NotYetImplemented)
        assertEquals(24, (rollback as RuntimeOutcome.NotYetImplemented).phase)
    }
}

/** Minimal host (capability-aware) for runtime tests — records nothing. */
private class RecordingHostCompat : com.authorss81.noteflow.plugins.runtime.FacadeHost {
    override fun insertText(text: String) = com.authorss81.noteflow.plugins.runtime.FacadeResult.Granted(Unit)
    override fun showResult(title: String, body: String) = com.authorss81.noteflow.plugins.runtime.FacadeResult.Granted(Unit)
    override fun httpGet(url: String) = com.authorss81.noteflow.plugins.runtime.FacadeResult.Granted("body")
    override fun readSelection() = com.authorss81.noteflow.plugins.runtime.FacadeResult.Granted("selection")
    override fun requestModelDownload(sizeBytes: Long) = com.authorss81.noteflow.plugins.runtime.FacadeResult.Granted(Unit)
}
