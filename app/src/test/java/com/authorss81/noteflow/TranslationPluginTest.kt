package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.TranslationModelStatus
import com.authorss81.noteflow.plugins.TranslationOutcome
import com.authorss81.noteflow.plugins.translation.OnDeviceTranslationPlugin
import com.authorss81.noteflow.plugins.translation.TranslatorEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 16 Translation pure-JVM tests: the wrapper's logic (target validation,
 * source auto-detection, engine delegation) runs against a fake engine.
 * The real ML Kit engine is never created.
 */
class TranslationPluginTest {

    private val fake = FakeTranslatorEngine()

    @Test
    fun `supportedTargetLanguages exposes the curated catalogue`() {
        val list = OnDeviceTranslationPlugin(fake).supportedTargetLanguages()
        assertTrue(list.size >= 25)
        assertTrue(list.any { it.code == "en" })
        assertTrue(list.any { it.code == "ja" })
    }

    @Test
    fun `unsupported target yields an Error without touching the engine`() {
        val result = runBlockingKt {
            OnDeviceTranslationPlugin(fake).translate("xx", "Hello")
        }
        assertTrue(result is TranslationOutcome.Error)
        assertTrue(fake.translateCalls == 0)
    }

    @Test
    fun `supported target delegates to the engine`() {
        fake.result = TranslationOutcome.Success("Hola")
        val result = runBlockingKt {
            OnDeviceTranslationPlugin(fake).translate("es", "Hello")
        }
        val translated = result as TranslationOutcome.Success
        assertEquals("Hola", translated.translatedText)
        assertEquals(1, fake.translateCalls)
    }

    @Test
    fun `downloadModel normalizes the language code and forwards the status`() {
        fake.status = TranslationModelStatus.Downloaded
        val status = runBlockingKt {
            OnDeviceTranslationPlugin(fake).downloadModel("pt-br")
        }
        assertEquals(TranslationModelStatus.Downloaded, status)
        assertEquals("pt", fake.lastDownloadTarget)
    }

    @Test
    fun `isModelDownloaded surfaces the engine answer`() {
        fake.downloaded = true
        assertTrue(runBlockingKt { OnDeviceTranslationPlugin(fake).isModelDownloaded("fr") })
        fake.downloaded = false
        assertFalse(runBlockingKt { OnDeviceTranslationPlugin(fake).isModelDownloaded("fr") })
    }

    @Test
    fun `engine failure surfaces as a typed Error`() {
        fake.result = TranslationOutcome.Error("model offline")
        val result = runBlockingKt {
            OnDeviceTranslationPlugin(fake).translate("de", "Hello")
        }
        assertTrue(result is TranslationOutcome.Error)
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
}

/** Tiny coroutine runner so tests don't need an explicit dispatcher. */
private fun <T> runBlockingKt(block: suspend () -> T): T =
    kotlinx.coroutines.runBlocking { block() }