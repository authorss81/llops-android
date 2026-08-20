package com.authorss81.noteflow.mlkit

import android.content.Context
import com.authorss81.noteflow.plugins.OcrOutcome
import com.authorss81.noteflow.plugins.TranslationModelStatus
import com.authorss81.noteflow.plugins.TranslationOutcome
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 175 ML Kit plugin JVM tests (moved from the base app's
 * OcrPluginWrapperTest + TranslationPluginTest).
 *
 * IMPORTANT (explicit): the real models — Google ML Kit's `TextRecognizer` and
 * `Translation` client ([MlKitOcrEngine] / [MlKitTranslatorEngine]) — CANNOT run
 * inside a JVM unit test (no Android runtime, no native model). These tests
 * cover the PLATFORM-INDEPENDENT wrapper that production shares with the model:
 * input validation, result formatting, error mapping, cancellation propagation,
 * the translation catalogue + target validation and engine delegation — with an
 * injected fake engine. The model invocation itself is verified on-device
 * (see docs/PLUGINS.md). Nothing here is silently skipped; the platform-only
 * boundary is the injected engine.
 */
class OnDeviceMlKitPluginTest {

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

    private class FakeTranslatorEngine : TranslatorEngine {
        var downloaded: Boolean = false
        var result: TranslationOutcome = TranslationOutcome.Error("empty")
        var status: TranslationModelStatus = TranslationModelStatus.NotDownloaded
        var translateCalls: Int = 0
        var lastDownloadTarget: String? = null

        override suspend fun isModelDownloaded(targetLanguage: String): Boolean = downloaded

        override suspend fun downloadModel(targetLanguage: String): TranslationModelStatus {
            lastDownloadTarget = targetLanguage
            return status
        }

        override suspend fun translate(
            sourceLanguage: String,
            targetLanguage: String,
            text: String
        ): TranslationOutcome {
            translateCalls++
            return result
        }
    }

    private val tempImage: File =
        File.createTempFile("mlkit-plugin-test", ".png").apply { deleteOnExit() }

    // ---- OCR: pure wrapper -------------------------------------------------

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

    @Test
    fun `formatter trims and collapses whitespace but keeps line breaks`() {
        assertEquals("Hello World\n\nLine 3", OcrTextFormatter.format("  Hello   World\n\n\nLine 3  "))
        assertEquals("", OcrTextFormatter.format("   \n  "))
        assertEquals("One line", OcrTextFormatter.format("One line"))
    }

    @Test
    fun `error mapper classifies io errors as unreadable image`() {
        assertTrue(OcrErrorMapper.userMessage(IOException("disk")).contains("read"))
        assertTrue(OcrErrorMapper.userMessage(IllegalStateException("boom")).contains("try again"))
        assertTrue(OcrErrorMapper.userMessage(CancellationException("cancelled")).contains("cancelled"))
    }

    @Test
    fun `plugin returns formatted recognized text on success`() = runBlocking {
        val plugin = OnDeviceMlKitPlugin(ocrEngine = FakeOcrEngine("  Hello   World\n\n\nLine 3  "))
        val outcome = plugin.recognizeText(null, tempImage.absolutePath)
        assertEquals(OcrOutcome.Success("Hello World\n\nLine 3"), outcome)
    }

    @Test
    fun `plugin reports no-text outcome when the model finds nothing`() = runBlocking {
        val plugin = OnDeviceMlKitPlugin(ocrEngine = FakeOcrEngine("   \n  "))
        val outcome = plugin.recognizeText(null, tempImage.absolutePath)
        assertTrue(outcome is OcrOutcome.NoText)
        assertTrue((outcome as OcrOutcome.NoText).message.isNotBlank())
    }

    @Test
    fun `plugin maps engine io failure to a user-facing error outcome`() = runBlocking {
        val plugin = OnDeviceMlKitPlugin(ocrEngine = FakeOcrEngine(failure = IOException("nope")))
        val outcome = plugin.recognizeText(null, tempImage.absolutePath)
        assertTrue(outcome is OcrOutcome.Error)
        assertTrue((outcome as OcrOutcome.Error).message.contains("read"))
    }

    @Test
    fun `plugin rejects an invalid path without ever invoking the model`() = runBlocking {
        val engine = FakeOcrEngine()
        val plugin = OnDeviceMlKitPlugin(ocrEngine = engine)
        val outcome = plugin.recognizeText(null, "/no/such/file.png")
        assertTrue(outcome is OcrOutcome.Error)
        assertEquals(0, engine.calls)
    }

    @Test
    fun `plugin propagates cancellation instead of swallowing it`() = runBlocking {
        val plugin = OnDeviceMlKitPlugin(ocrEngine = FakeOcrEngine(failure = CancellationException("user cancelled")))
        try {
            plugin.recognizeText(null, tempImage.absolutePath)
            fail("CancellationException must propagate")
        } catch (e: CancellationException) {
            // expected — cancellation is never converted into a result
        }
    }

    @Test
    fun `plugin without an injected engine fails loudly rather than silently`() = runBlocking {
        // Default-constructed plugin (no real payloads on the JVM) must fail with
        // a typed outcome, never hang and never fake text.
        val plugin = OnDeviceMlKitPlugin()
        val outcome = plugin.recognizeText(null, tempImage.absolutePath)
        assertTrue(outcome is OcrOutcome.Error)
        assertTrue((outcome as OcrOutcome.Error).message.isNotBlank())
    }

    // ---- Translation: wrapper logic ----------------------------------------

    @Test
    fun `plugin exposes the curated target languages`() {
        val list = OnDeviceMlKitPlugin(translatorEngine = FakeTranslatorEngine()).supportedTargetLanguages()
        assertTrue(list.size >= 25)
        assertTrue(list.any { it.code == "en" })
        assertTrue(list.any { it.code == "ja" })
    }

    @Test
    fun `unsupported target yields an Error without touching the engine`() {
        val fake = FakeTranslatorEngine()
        val result = runBlocking { OnDeviceMlKitPlugin(translatorEngine = fake).translate("xx", "Hello") }
        assertTrue(result is TranslationOutcome.Error)
        assertTrue(fake.translateCalls == 0)
    }

    @Test
    fun `supported target delegates to the engine`() {
        val fake = FakeTranslatorEngine()
        fake.result = TranslationOutcome.Success("Hola")
        val result = runBlocking { OnDeviceMlKitPlugin(translatorEngine = fake).translate("es", "Hello") }
        val translated = result as TranslationOutcome.Success
        assertEquals("Hola", translated.translatedText)
        assertEquals(1, fake.translateCalls)
    }

    @Test
    fun `downloadModel normalizes the language code and forwards the status`() {
        val fake = FakeTranslatorEngine()
        fake.status = TranslationModelStatus.Downloaded
        val status = runBlocking { OnDeviceMlKitPlugin(translatorEngine = fake).downloadModel("pt-br") }
        assertEquals(TranslationModelStatus.Downloaded, status)
        assertEquals("pt", fake.lastDownloadTarget)
    }

    @Test
    fun `isModelDownloaded surfaces the engine answer`() {
        val fake = FakeTranslatorEngine()
        val plugin = OnDeviceMlKitPlugin(translatorEngine = fake)
        fake.downloaded = true
        assertTrue(runBlocking { plugin.isModelDownloaded("fr") })
        fake.downloaded = false
        assertFalse(runBlocking { plugin.isModelDownloaded("fr") })
    }

    @Test
    fun `engine failure surfaces as a typed Error`() {
        val fake = FakeTranslatorEngine()
        fake.result = TranslationOutcome.Error("model offline")
        val result = runBlocking { OnDeviceMlKitPlugin(translatorEngine = fake).translate("de", "Hello") }
        assertTrue(result is TranslationOutcome.Error)
    }

    // ---- SourceProber / catalog --------------------------------------------

    @Test
    fun `source prober distinguishes script blocks and defaults to english`() {
        assertEquals("zh", SourceProber.detect("你好世界"))
        assertEquals("ja", SourceProber.detect("こんにちは"))
        assertEquals("ko", SourceProber.detect("안녕하세요"))
        assertEquals("ar", SourceProber.detect("مرحبا بالعالم"))
        assertEquals("he", SourceProber.detect("שָׁלוֹם עוֹלָם"))
        assertEquals("th", SourceProber.detect("สวัสดี"))
        assertEquals("ru", SourceProber.detect("Привет мир"))
        assertEquals("el", SourceProber.detect("Γειά σου κόσμε"))
        assertEquals("en", SourceProber.detect("Hello, planet Earth"))
        assertEquals("en", SourceProber.detect(""))
    }

    @Test
    fun `catalogue normalizes dialect codes for mlkit`() {
        assertEquals("no", TranslationCatalog.normalize("nb"))
        assertEquals("zh", TranslationCatalog.normalize("zh-CN"))
        assertEquals("pt", TranslationCatalog.normalize("pt-BR"))
    }
}