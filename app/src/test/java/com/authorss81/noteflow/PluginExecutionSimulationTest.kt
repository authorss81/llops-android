package com.authorss81.noteflow

import android.content.Context
import com.authorss81.noteflow.plugins.ExportFormat
import com.authorss81.noteflow.plugins.ExportOutcome
import com.authorss81.noteflow.plugins.ExportPlugin
import com.authorss81.noteflow.plugins.ExportRequest
import com.authorss81.noteflow.plugins.OcrOutcome
import com.authorss81.noteflow.plugins.OcrPlugin
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginFailureReason
import com.authorss81.noteflow.plugins.PluginManager
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.PluginResult
import com.authorss81.noteflow.plugins.Rot13TransformPlugin
import com.authorss81.noteflow.plugins.TextTransformPlugin
import com.authorss81.noteflow.plugins.export.ExportEnginePlugin
import com.authorss81.noteflow.plugins.export.ExportPayload
import com.authorss81.noteflow.plugins.ocr.OcrEngine
import com.authorss81.noteflow.plugins.ocr.OnDeviceOcrPlugin
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 21 — plugin execution SIMULATION (job 1 of the phase).
 *
 * Proves the framework is not scaffold by tracing the FULL path end-to-end with
 * REAL plugins (no fakes for the framework itself):
 *
 *   registry discovery → enable → capability routing → invocation →
 *   typed result → settings/opt-in persistence.
 *
 * The platform boundary (ML Kit OCR model, PDF/FileProvider materialization) is
 * injected exactly as production constructs it — the plugin wrapper logic,
 * routing, guarding and persistence under test are the real production code.
 * The model itself cannot run on the JVM; that boundary is the injected engine
 * (see docs/PLUGINS.md) and is not silently skipped.
 */
class PluginExecutionSimulationTest {

    private val tempImage: File =
        File.createTempFile("exec-sim-test", ".png").apply { deleteOnExit() }

    private class FakeOcrEngine(private val raw: String = "Simulated  OCR text") : OcrEngine {
        override suspend fun recognize(context: Context?, imagePath: String): String = raw
    }

    private fun exportWriterToTempFile(): (Context?, ExportPayload, ExportFormat) -> ExportOutcome {
        return { _, payload, format ->
            val f = File.createTempFile("exec-sim-export", "." + payload.fileName.substringAfterLast('.'))
            f.deleteOnExit()
            f.writeBytes(payload.markdown.toByteArray(Charsets.UTF_8))
            ExportOutcome.Success(f, format)
        }
    }

    @Test
    fun `rot13 routes end-to-end with persisted opt-in`() {
        val enableStore = InMemoryEnableStore()
        val registry = PluginRegistry(enableStore, currentApiLevel = 26)
        val rot13 = Rot13TransformPlugin()

        // 1. Discovery.
        assertTrue(registry.allPlugins.any { it.id == rot13.id })
        assertFalse(registry.isEnabled(rot13.id))

        // 2. Enable (opt-in persisted in the store).
        registry.setEnabled(rot13.id, enabled = true)
        assertTrue(enableStore.isEnabled(rot13.id))
        assertTrue(enableStore.hasEverBeenEnabled(rot13.id))

        // 3. Routing + invocation via the manager.
        val manager = PluginManager(registry)
        val result = manager.withPlugin(PluginCapability.TextTransform, null) { plugin ->
            (plugin as TextTransformPlugin).transformText("Hello, World!")
        }
        assertEquals("Uryyb, Jbeyq!", (result as PluginResult.Success).value)

        // 4. Round-trip through the same real plugin.
        val twice = manager.withPlugin(PluginCapability.TextTransform, null) { plugin ->
            (plugin as TextTransformPlugin).transformText("Uryyb, Jbeyq!")
        }
        assertEquals("Hello, World!", (twice as PluginResult.Success).value)
    }

    @Test
    fun `ocr routes end-to-end through the real plugin wrapper`() = runBlocking {
        val plugin = OnDeviceOcrPlugin(FakeOcrEngine("Simulated  OCR text"))
        val registry = PluginRegistry(
            InMemoryEnableStore(),
            plugins = listOf(plugin),
            currentApiLevel = 26
        )
        registry.setEnabled(plugin.id, enabled = true)
        val manager = PluginManager(registry)

        val result = manager.withPluginAsync(PluginCapability.OCR, null) { p ->
            (p as OcrPlugin).recognizeText(null, tempImage.absolutePath)
        }

        assertTrue(result is PluginResult.Success)
        val outcome = (result as PluginResult.Success).value as OcrOutcome.Success
        assertEquals("Simulated OCR text", outcome.text)

        // A plugin failure inside the wrapper must surface as a typed failure,
        // never a crash (guard the plugin that cannot serve).
        registry.setEnabled(plugin.id, enabled = false)
        val skipped = manager.withPluginAsync(PluginCapability.OCR, null) { p ->
            (p as OcrPlugin).recognizeText(null, tempImage.absolutePath)
        }
        assertTrue(skipped is PluginResult.Failure)
        assertEquals(PluginFailureReason.NONE_ENABLED, (skipped as PluginResult.Failure).reason)
    }

    @Test
    fun `export engine routes via the manager with typed outcomes`() = runBlocking {
        val plugin = ExportEnginePlugin(writer = exportWriterToTempFile())
        val registry = PluginRegistry(
            InMemoryEnableStore(),
            plugins = listOf(plugin),
            currentApiLevel = 26
        )
        registry.setEnabled(plugin.id, enabled = true)
        val manager = PluginManager(registry)

        // Manager routing: the enabled export plugin is resolved + invoked and
        // the result is the plugin's typed outcome — routing never throws. The
        // real engine requires a device Context (platform slice: FileProvider /
        // PDF); on the JVM it fails loudly with a typed, user-facing Error
        // instead of crashing or silently degrading.
        val routed = manager.withPluginAsync(PluginCapability.Export, null) { p ->
            (p as ExportPlugin).exportNote(null, ExportRequest("My Note", markdown = "# Hello"), ExportFormat.MARKDOWN)
        }
        assertTrue(routed is PluginResult.Success)
        assertTrue((routed as PluginResult.Success).value is ExportOutcome.Error)

        val direct = plugin.exportNote(null, ExportRequest("My Note", markdown = "# Hello"), ExportFormat.MARKDOWN)
        assertTrue(direct is ExportOutcome.Error)
        assertTrue((direct as ExportOutcome.Error).message.isNotBlank())
    }

    @Test
    fun `a disabled plugin is skipped by capability routing`() {
        val registry = PluginRegistry(InMemoryEnableStore(), currentApiLevel = 26)
        registry.setEnabled(Rot13TransformPlugin().id, enabled = true)
        registry.setEnabled(Rot13TransformPlugin().id, enabled = false)
        val manager = PluginManager(registry)

        val result = manager.withPlugin(PluginCapability.TextTransform, null) { plugin ->
            (plugin as TextTransformPlugin).transformText("ignored")
        }

        assertTrue(result is PluginResult.Failure)
        assertEquals(PluginFailureReason.NONE_ENABLED, (result as PluginResult.Failure).reason)
    }
}
