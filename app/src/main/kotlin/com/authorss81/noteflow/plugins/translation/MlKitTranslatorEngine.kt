package com.authorss81.noteflow.plugins.translation

import com.authorss81.noteflow.plugins.TranslationModelStatus
import com.authorss81.noteflow.plugins.TranslationOutcome
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The real, on-device translator: Google ML Kit `translate` (Phase 16).
 *
 * PLATFORM-ONLY — talks to ML Kit's `Translation` client; cannot run in a JVM
 * unit test. Reached only from [OnDeviceTranslationPlugin] when no fake engine
 * is injected.
 *
 * Models are NOT bundled. They download on the user's EXPLICIT action: either
 * the dialog's standalone "Download" button ([downloadModel]) or the translate
 * tap itself ([translate] runs `downloadModelIfNeeded()` as part of the action)
 * — the tap IS the one-time consent, after which translation works fully
 * offline with no API key. A failed/download-blocked model surfaces as
 * [TranslationOutcome.ModelNotReady] with a clear reason — never a crash and
 * never silent degradation.
 *
 * `TranslateRemoteModel` has no public constructor in 17.x, so
 * [isModelDownloaded] answers via [RemoteModelManager.getDownloadedModels]
 * over the already-downloaded translate models.
 */
class MlKitTranslatorEngine : TranslatorEngine {

    override suspend fun isModelDownloaded(targetLanguage: String): Boolean {
        val code = TranslationCatalog.normalize(targetLanguage)
        if (code !in TranslationCatalog.TARGETS) return false
        return try {
            val downloaded = RemoteModelManager.getInstance()
                .getDownloadedModels(TranslateRemoteModel::class.java)
                .await()
            downloaded.any { it.getLanguage() == code }
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun downloadModel(targetLanguage: String): TranslationModelStatus {
        val code = TranslationCatalog.normalize(targetLanguage)
        if (code !in TranslationCatalog.TARGETS) {
            return TranslationModelStatus.Error("'$targetLanguage' isn't a supported target language.")
        }
        return try {
            val translator = newTranslator("en", code)
            translator.downloadModelIfNeeded().await()
            translator.close()
            TranslationModelStatus.Downloaded
        } catch (e: Throwable) {
            TranslationModelStatus.Error(
                "Couldn't download the $code model (${e::class.java.simpleName}). " +
                    "Check your connection and storage, then try again."
            )
        }
    }

    override suspend fun translate(
        sourceLanguage: String,
        targetLanguage: String,
        text: String
    ): TranslationOutcome {
        val src = TranslationCatalog.normalize(sourceLanguage)
        val tgt = TranslationCatalog.normalize(targetLanguage)
        if (tgt !in TranslationCatalog.TARGETS) {
            return TranslationOutcome.Error("'$targetLanguage' isn't a supported target language.")
        }
        if (src == tgt) return TranslationOutcome.Success(text)
        return try {
            val translator = newTranslator(src, tgt)
            try {
                // The user's translate tap is the explicit one-time consent for
                // the (small) model download; after this it runs fully offline.
                translator.downloadModelIfNeeded().await()
                val result = translator.translate(text).await()
                TranslationOutcome.Success(result)
            } finally {
                translator.close()
            }
        } catch (e: com.google.mlkit.common.MlKitException) {
            when (e.errorCode) {
                com.google.mlkit.common.MlKitException.NETWORK_ISSUE -> TranslationOutcome.ModelNotReady(
                    "The ${label(tgt)} model isn't downloaded and you're offline — " +
                        "connect once, tap Download, and it'll work offline from then on."
                )
                com.google.mlkit.common.MlKitException.NOT_ENOUGH_SPACE -> TranslationOutcome.Error(
                    "Not enough storage for the ${label(tgt)} model — free some space and retry."
                )
                else -> TranslationOutcome.Error(
                    "Translation to ${label(tgt)} failed (${e.javaClass.simpleName}). " +
                        "The model may need a download — check your connection."
                )
            }
        } catch (e: Throwable) {
            TranslationOutcome.Error(
                "Translation to ${label(tgt)} failed (${e::class.java.simpleName}). " +
                    "The model may need a download — try again with a connection."
            )
        }
    }

    // ---- internals ---------------------------------------------------------

    private fun newTranslator(source: String, target: String) = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(target)
            .build()
    )

    private fun label(code: String): String =
        TranslationCatalog.TARGETS[code] ?: code.uppercase()
}

/** Suspend `await()`-style wrapper for a GMS [Task]. */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result ->
        if (cont.isActive) cont.resume(result)
    }
    addOnFailureListener { e ->
        if (cont.isActive) cont.resumeWithException(e)
    }
}

/** Factory so the plugin references ML Kit through one lazy call. */
internal object PlatformTranslationEngines {
    fun create(): TranslatorEngine = MlKitTranslatorEngine()
}