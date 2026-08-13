package com.authorss81.noteflow

import android.content.Context
import com.authorss81.noteflow.plugins.OcrOutcome
import com.authorss81.noteflow.plugins.OcrPlugin
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginManager
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.PluginResult
import com.authorss81.noteflow.plugins.ocr.OcrEngine
import com.authorss81.noteflow.plugins.ocr.OcrErrorMapper
import com.authorss81.noteflow.plugins.ocr.OcrInputValidator
import com.authorss81.noteflow.plugins.ocr.OcrTextFormatter
import com.authorss81.noteflow.plugins.ocr.OnDeviceOcrPlugin
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 12 OCR wrapper tests.
 *
 * IMPORTANT (explicit): the real model — Google ML Kit's `TextRecognizer`
 * ([com.authorss81.noteflow.plugins.ocr.MlKitOcrEngine]) — CANNOT run inside a
 * JVM unit test (no Android runtime, no native model). These tests therefore
 * cover the PLATFORM-INDEPENDENT wrapper that production shares with the model:
 * input validation, result formatting, error mapping, cancellation propagation
 * and plugin routing — with an injected fake engine. The model invocation itself
 * is verified on-device/emulator (see docs/PLUGINS.md). Nothing here is silently
 * skipped; the platform-only boundary is the injected engine.
 */
class OcrPluginWrapperTest {

    private class FakeOcrEngine(
        private val raw: String = "Hello  World\n\n\nLine 3",
        private val failure: Throwable? = null,
        var calls: Int = 0
    ) : OcrEngine {
        override suspend fun recognize(context: Context?, imagePath: String): String {
            calls++
            failure?.let { throw it }
            return raw
        }
    }

    private val tempImage: File =
        File.createTempFile("ocr-wrapper-test", ".png").apply { deleteOnExit() }

    // ---- pure wrapper: input validation ------------------------------------

    @Test
    fun `valid existing image path passes validation`() {
        assertNull(OcrInputValidator.validateImagePath(tempImage.absolutePath))
    }

    @Test
    fun `blank or null image path is rejected with a user-facing reason`() {
        assertTrue(OcrInputValidator.validateImagePath(null)!!.isNotBlank())
        assertTrue(OcrInputValidator.validateImagePath("   ")!!.isNotBlank())
    }

    @Test
    fun `nonexistent image path is rejected`() {
        val missing = File(tempImage.parentFile, "does_not_exist_${System.currentTimeMillis()}.png")
        val reason = OcrInputValidator.validateImagePath(missing.absolutePath)
        assertTrue(reason != null && reason.contains("not found"))
    }

    // ---- pure wrapper: result formatting -----------------------------------

    @Test
    fun `formatter trims and collapses whitespace but keeps line breaks`() {
        assertEquals("Hello World\n\nLine 3", OcrTextFormatter.format("  Hello   World\n\n\nLine 3  "))
        assertEquals("", OcrTextFormatter.format("   \n  "))
        assertEquals("One line", OcrTextFormatter.format("One line"))
    }

    // ---- pure wrapper: error mapping ---------------------------------------

    @Test
    fun `error mapper classifies io errors as unreadable image`() {
        assertTrue(OcrErrorMapper.userMessage(IOException("disk")).contains("read"))
        assertTrue(OcrErrorMapper.userMessage(IllegalStateException("boom")).contains("try again"))
        assertTrue(OcrErrorMapper.userMessage(CancellationException("cancelled")).contains("cancelled"))
    }

    // ---- plugin behavior (with injected fake engine) -----------------------

    @Test
    fun `plugin returns formatted recognized text on success`() = runBlocking {
        val plugin = OnDeviceOcrPlugin(FakeOcrEngine("  Hello   World\n\n\nLine 3  "))
        val outcome = plugin.recognizeText(null, tempImage.absolutePath)
        assertEquals(OcrOutcome.Success("Hello World\n\nLine 3"), outcome)
    }

    @Test
    fun `plugin reports no-text outcome when the model finds nothing`() = runBlocking {
        val plugin = OnDeviceOcrPlugin(FakeOcrEngine("   \n  "))
        val outcome = plugin.recognizeText(null, tempImage.absolutePath)
        assertTrue(outcome is OcrOutcome.NoText)
        assertTrue((outcome as OcrOutcome.NoText).message.isNotBlank())
    }

    @Test
    fun `plugin maps engine io failure to a user-facing error outcome`() = runBlocking {
        val plugin = OnDeviceOcrPlugin(FakeOcrEngine(failure = IOException("nope")))
        val outcome = plugin.recognizeText(null, tempImage.absolutePath)
        assertTrue(outcome is OcrOutcome.Error)
        assertTrue((outcome as OcrOutcome.Error).message.contains("read"))
    }

    @Test
    fun `plugin rejects an invalid path without ever invoking the model`() = runBlocking {
        val engine = FakeOcrEngine()
        val plugin = OnDeviceOcrPlugin(engine)
        val outcome = plugin.recognizeText(null, "/no/such/file.png")
        assertTrue(outcome is OcrOutcome.Error)
        assertEquals(0, engine.calls)
    }

    @Test
    fun `plugin propagates cancellation instead of swallowing it`() = runBlocking {
        val plugin = OnDeviceOcrPlugin(FakeOcrEngine(failure = CancellationException("user cancelled")))
        try {
            plugin.recognizeText(null, tempImage.absolutePath)
            fail("CancellationException must propagate")
        } catch (e: CancellationException) {
            // expected — cancellation is never converted into a result
        }
    }

    @Test
    fun `plugin without an injected engine fails loudly rather than silently`() = runBlocking {
        // Default-constructed plugin has no engine; without a device context it
        // must fail loudly with a typed outcome, never hang and never fake text.
        val plugin = OnDeviceOcrPlugin()
        val outcome = plugin.recognizeText(null, tempImage.absolutePath)
        assertTrue(outcome is OcrOutcome.Error)
        assertTrue((outcome as OcrOutcome.Error).message.isNotBlank())
    }

    // ---- registry/manager routing ------------------------------------------

    @Test
    fun `ocr plugin routes through the plugin manager end to end`() = runBlocking {
        val plugin = OnDeviceOcrPlugin(FakeOcrEngine("InkFlow  OCR"))
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
        assertEquals("InkFlow OCR", outcome.text)
    }

    @Test
    fun `ocr plugin registers with its manifest identity and capability`() {
        val id = OnDeviceOcrPlugin(FakeOcrEngine()).id
        val registry = PluginRegistry(
            InMemoryEnableStore(),
            plugins = listOf(OnDeviceOcrPlugin(FakeOcrEngine())),
            currentApiLevel = 26
        )
        val found = registry.allPlugins.first { it.id == id }
        assertTrue(id.startsWith("com.authorss81.noteflow.plugins.ocr"))
        assertTrue(PluginCapability.OCR in found.capabilities)
        // Off by default — user opt-in (framework rule).
        assertTrue(!registry.isEnabled(id))
    }
}
