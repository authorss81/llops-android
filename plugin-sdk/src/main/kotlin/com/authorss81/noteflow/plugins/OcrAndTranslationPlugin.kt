package com.authorss81.noteflow.plugins

import android.content.Context

/**
 * Phase 175: the OCR + translation serving interfaces live in `plugin-sdk` (like
 * [AssistantPlugin]) so the downloadable `plugins/mlkit` artifact is compiled
 * against the EXACT interfaces the base app resolves through its classloader.
 * These were previously app-side (`app/.../plugins/NoteflowPlugin.kt`) because
 * OCR + translation were compile-time plugins; phase-175 moves the heavy ML Kit
 * payloads out of the base APK, so the serving surface must ship in the shared
 * framework instead.
 */

/**
 * Outcome of an on-device OCR request.
 *
 * Typed so the UI can distinguish a real extraction ([Success]), a genuinely
 * empty image ([NoText], with a user-facing reason) and a validated, user-facing
 * failure ([Error]) — while the plugin still fails loudly instead of silently
 * returning nothing. A plugin must NEVER return null (the manager treats that as
 * `PluginResult.Failure`).
 */
sealed class OcrOutcome {
    /** The recognized text (may still need trimming). */
    data class Success(val text: String) : OcrOutcome()

    /** The model ran but found no readable text; [message] is user-facing. */
    data class NoText(val message: String) : OcrOutcome()

    /** The request failed; [message] is a validated, user-facing reason. */
    data class Error(val message: String) : OcrOutcome()
}

/**
 * Serving interface for the [PluginCapability.OCR] capability.
 *
 * A plugin that implements this interface extracts text from an on-device image
 * (the file at [imagePath]). Implementations MUST run the model off the main
 * thread (e.g. `withContext(Dispatchers.IO)`) and MUST be cancelable when the
 * calling coroutine is cancelled. The returned [OcrOutcome] carries extracted
 * text or a user-facing failure — never a silent empty result.
 *
 * [context] is nullable exactly like the plugin lifecycle hooks — production
 * always passes a real Context; tests pass null.
 */
interface OcrPlugin {
    suspend fun recognizeText(context: Context?, imagePath: String): OcrOutcome
}

/** A language the on-device translator can translate into (code + label). */
data class TranslationLanguage(val code: String, val displayName: String)

/** Outcome of an on-device translation request. */
sealed class TranslationOutcome {
    data class Success(val translatedText: String) : TranslationOutcome()
    data class ModelNotReady(val message: String) : TranslationOutcome()
    data class Error(val message: String) : TranslationOutcome()
}

/** Progress state of an on-demand translation model download. */
sealed class TranslationModelStatus {
    data object Downloaded : TranslationModelStatus()
    data object NotDownloaded : TranslationModelStatus()
    data class Downloading(val progress: Float) : TranslationModelStatus()
    data class Error(val message: String) : TranslationModelStatus()
}

/**
 * Serving interface for the [PluginCapability.Translation] capability.
 *
 * Models are NOT bundled: they download once on first use after explicit user
 * consent (with clear progress) and then work fully offline. A failed/offline
 * download surfaces [TranslationModelStatus.Error]/[TranslationOutcome.Error]
 * with a clear message — it never crashes. The translator engine is injected
 * (fake in unit tests; ML Kit behind it in production).
 */
interface TranslationPlugin {
    /** The target languages offered by the UI (a curated ML Kit subset). PURE JVM. */
    fun supportedTargetLanguages(): List<TranslationLanguage>

    /** True when [targetLanguage]'s model is already stored on-device. */
    suspend fun isModelDownloaded(targetLanguage: String): Boolean

    /** Download [targetLanguage]'s model on demand. User-initiated, guarded. */
    suspend fun downloadModel(targetLanguage: String): TranslationModelStatus

    /** Translate [text] into [targetLanguage]. */
    suspend fun translate(targetLanguage: String, text: String): TranslationOutcome
}