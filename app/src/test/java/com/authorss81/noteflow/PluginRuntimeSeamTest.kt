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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 22: the [PluginRuntime] seam is HONESTLY stubbed — every operation
 * answers [RuntimeOutcome.NotYetImplemented] naming the phase that implements
 * it, and the registry seam lets Phase 23/24 swap in the real runtime.
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

    @Test
    fun `verify reports NotYetImplemented for phase 23`() {
        val result = NotYetImplementedPluginRuntime().verify(artifact())

        assertTrue(result is RuntimeOutcome.NotYetImplemented)
        assertEquals(23, (result as RuntimeOutcome.NotYetImplemented).phase)
        assertTrue(result.message.contains("verify"))
    }

    @Test
    fun `load reports NotYetImplemented for phase 23`() {
        val result = NotYetImplementedPluginRuntime().load(remoteEntry())

        assertTrue(result is RuntimeOutcome.NotYetImplemented)
        assertEquals(23, (result as RuntimeOutcome.NotYetImplemented).phase)
        assertTrue(result.message.contains("load"))
    }

    @Test
    fun `update reports NotYetImplemented for phase 24`() {
        val result = NotYetImplementedPluginRuntime().update(remoteEntry(), PluginVersion(1, 1, 0))

        assertTrue(result is RuntimeOutcome.NotYetImplemented)
        assertEquals(24, (result as RuntimeOutcome.NotYetImplemented).phase)
        assertTrue(result.message.contains("update"))
    }

    @Test
    fun `rollback reports NotYetImplemented for phase 24`() {
        val result = NotYetImplementedPluginRuntime().rollback(remoteEntry())

        assertTrue(result is RuntimeOutcome.NotYetImplemented)
        assertEquals(24, (result as RuntimeOutcome.NotYetImplemented).phase)
        assertTrue(result.message.contains("rollback"))
    }

    @Test
    fun `stub never fabricates a verified or loaded result`() {
        val runtime = NotYetImplementedPluginRuntime()
        // No operation on the stub may ever claim success.
        assertEquals(0, listOf(
            runtime.verify(artifact()),
            runtime.load(remoteEntry()),
            runtime.update(remoteEntry(), PluginVersion(1, 1, 0)),
            runtime.rollback(remoteEntry())
        ).count { it is RuntimeOutcome.Success<*> })
    }

    @Test
    fun `the registry seam defaults to the stub and can be swapped`() {
        assertTrue(PluginRuntimeRegistry.current() is NotYetImplementedPluginRuntime)

        val fake = object : PluginRuntime {
            override fun verify(artifact: PluginArtifact) =
                RuntimeOutcome.Failed("fake verify")
            override fun load(entry: PluginEntry) =
                RuntimeOutcome.Failed("fake load")
            override fun update(entry: PluginEntry, newVersion: PluginVersion) =
                RuntimeOutcome.Failed("fake update")
            override fun rollback(entry: PluginEntry) =
                RuntimeOutcome.Failed("fake rollback")
        }
        PluginRuntimeRegistry.register(fake)
        assertEquals(fake, PluginRuntimeRegistry.current())

        // Restore the honest stub so other tests see the default seam.
        PluginRuntimeRegistry.register(NotYetImplementedPluginRuntime())
        assertTrue(PluginRuntimeRegistry.current() is NotYetImplementedPluginRuntime)
    }
}
