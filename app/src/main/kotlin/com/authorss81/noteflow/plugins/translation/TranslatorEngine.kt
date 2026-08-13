package com.authorss81.noteflow.plugins.translation

import com.authorss81.noteflow.plugins.TranslationModelStatus
import com.authorss81.noteflow.plugins.TranslationOutcome

/**
 * THE interface between [OnDeviceTranslationPlugin] and the on-device
 * translator — the injected-engine pattern. The plugin's wrapper logic
 * (catalogue, target validation, source auto-detection) is pure JVM and
 * unit-tested with a fake engine; only [MlKitTranslatorEngine] touches
 * ML Kit's `Translation` client.
 */
interface TranslatorEngine {

    /** Whether [targetLanguage]'s model is already stored on-device. */
    suspend fun isModelDownloaded(targetLanguage: String): Boolean

    /**
     * Download [targetLanguage]'s model on demand. Must surface a typed
     * [TranslationModelStatus] (never throw) and honour coroutine cancellation
     * (a cancelled user-triggered download simply stops).
     */
    suspend fun downloadModel(targetLanguage: String): TranslationModelStatus

    /** Translate [text] from [sourceLanguage] into [targetLanguage]. */
    suspend fun translate(
        sourceLanguage: String,
        targetLanguage: String,
        text: String
    ): TranslationOutcome
}