package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.runtime.NotYetImplementedPluginRuntime
import com.authorss81.noteflow.plugins.runtime.PluginArtifact
import com.authorss81.noteflow.plugins.runtime.PluginEntry
import com.authorss81.noteflow.plugins.runtime.PluginEntrySource
import com.authorss81.noteflow.plugins.runtime.PluginRuntime
import com.authorss81.noteflow.plugins.runtime.PluginRuntimeRegistry
import com.authorss81.noteflow.plugins.runtime.PluginVersion
import com.authorss81.noteflow.plugins.runtime.RuntimeOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 22/24: the [PluginRuntime] seam. Phase 23 filled verify/load and
 * Phase 24 filled update/rollback in [SignatureVerifiedPluginRuntime]; the
 * DEFAULT registry runtime ([NotYetImplementedPluginRuntime]) is the honest
 * "no runtime is registered" answer (it never fabricates a verified/loaded/
 * updated result), and the registry seam lets production swap in the real
 * runtime.
 */
class PluginRuntimeSeamTest {

    private fun remoteEntry() = PluginEntry(
        id = "com.authorss81.noteflow.plugins.remote.ocr",
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

    private fun artifact() = PluginArtifact(
        entry = remoteEntry(),
        artifactPath = "/data/noteflow/plugins/ocr-1.0.0.apk",
        expectedSha256 = "ab12cd34ef56",
        expectedPinnedCertHash = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    )

    private fun newerEntry() = remoteEntry().copy(version = PluginVersion(1, 1, 0))

    @Test
    fun `verify without a registered runtime fails honestly`() {
        val result = NotYetImplementedPluginRuntime().verify(artifact())

        assertTrue(result is RuntimeOutcome.Failed)
        assertTrue((result as RuntimeOutcome.Failed).message.contains("no plugin runtime is registered"))
    }

    @Test
    fun `load without a registered runtime fails honestly`() {
        val result = NotYetImplementedPluginRuntime().load(remoteEntry())

        assertTrue(result is RuntimeOutcome.Failed)
        assertTrue((result as RuntimeOutcome.Failed).message.contains("no plugin runtime is registered"))
    }

    @Test
    fun `update without a registered runtime fails honestly`() = runBlocking {
        val result = NotYetImplementedPluginRuntime().update(remoteEntry(), newerEntry(), userApproved = true, onProgress = {})

        assertTrue(result is RuntimeOutcome.Failed)
        assertTrue((result as RuntimeOutcome.Failed).message.contains("no plugin runtime is registered"))
    }

    @Test
    fun `rollback without a registered runtime fails honestly`() = runBlocking {
        val result = NotYetImplementedPluginRuntime().rollback(remoteEntry())

        assertTrue(result is RuntimeOutcome.Failed)
        assertTrue((result as RuntimeOutcome.Failed).message.contains("no plugin runtime is registered"))
    }

    @Test
    fun `stub never fabricates a verified or loaded or updated result`() = runBlocking {
        val runtime = NotYetImplementedPluginRuntime()
        // No operation on the stub may ever claim success.
        assertEquals(0, listOf(
            runtime.verify(artifact()),
            runtime.load(remoteEntry()),
            runtime.update(remoteEntry(), newerEntry(), userApproved = true, onProgress = {}),
            runtime.rollback(remoteEntry())
        ).count { it is RuntimeOutcome.Success<*> })
    }

    @Test
    fun `the registry seam defaults to the stub and can be swapped`() = runBlocking {
        assertTrue(PluginRuntimeRegistry.current() is NotYetImplementedPluginRuntime)

        val fake = object : PluginRuntime {
            override fun verify(artifact: PluginArtifact) =
                RuntimeOutcome.Failed("fake verify")
            override fun load(entry: PluginEntry) =
                RuntimeOutcome.Failed("fake load")
            override suspend fun update(
                entry: PluginEntry,
                target: PluginEntry,
                userApproved: Boolean,
                onProgress: (Float) -> Unit
            ) = RuntimeOutcome.Failed("fake update")
            override suspend fun rollback(entry: PluginEntry) =
                RuntimeOutcome.Failed("fake rollback")
        }
        PluginRuntimeRegistry.register(fake)
        assertEquals(fake, PluginRuntimeRegistry.current())

        // Restore the honest default so other tests see the default seam.
        PluginRuntimeRegistry.register(NotYetImplementedPluginRuntime())
        assertTrue(PluginRuntimeRegistry.current() is NotYetImplementedPluginRuntime)
    }
}
