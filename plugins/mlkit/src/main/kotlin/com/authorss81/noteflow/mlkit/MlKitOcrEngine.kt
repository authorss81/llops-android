package com.authorss81.noteflow.mlkit

import android.content.Context
import android.net.Uri
import com.authorss81.noteflow.mlkit.engine.PluginPayloadLoader
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The real, on-device OCR model runner: Google ML Kit text-recognition (Latin
 * script, bundled model), moved out of the base APK in phase-175 (R2-KS-21).
 *
 * PLATFORM-ONLY: this class talks to ML Kit's native `TextRecognizer`, which
 * cannot run inside a JVM unit test (no Android runtime, no native model). The
 * plugin's pure wrapper logic is covered by the JVM tests in this module.
 *
 * [create] first asks [PluginPayloadLoader] to stage the engine payloads the
 * native pipeline needs (libmlkit_google_ocr_pipeline.so preloaded + the 21
 * bundled Latin OCR model files under `mlkit-google-ocr-models/` extracted to
 * app-private files). If the payloads are missing the recognizer is refused with
 * a typed outcome — the host never crashes, and the feature stays honestly off
 * until the artifact is (re)installed.
 *
 * Cancellation: ML Kit's Tasks API exposes no `cancel()` handle on the returned
 * `Task`, so cancellation is honoured by *never resuming a cancelled
 * continuation* — a user tapping "Cancel" aborts the coroutine immediately (the
 * model may finish briefly in the background, which is harmless). Each callback
 * checks `cont.isActive` before resuming, which both makes the suspend call
 * cancellable and prevents a late task callback from double-resuming an
 * already-cancelled continuation (which would throw `IllegalStateException`).
 */
class MlKitOcrEngine private constructor(
    private val recognizer: TextRecognizer
) : OcrEngine {

    override suspend fun recognize(context: Context?, imagePath: String): String {
        val ctx = context ?: throw IllegalStateException("OCR requires a device context")
        val input = InputImage.fromFilePath(ctx, Uri.fromFile(File(imagePath)))
        return suspendCancellableCoroutine { cont ->
            val task = recognizer.process(input)
            task.addOnSuccessListener(
                OnSuccessListener<Text> { result ->
                    if (cont.isActive) cont.resume(result.text)
                }
            )
            task.addOnFailureListener(
                OnFailureListener { e ->
                    if (cont.isActive) cont.resumeWithException(e)
                }
            )
            // No `cancel()` on `Task`; the isActive guards above are the
            // cancellation boundary. The recognizer is process-lifetime and
            // closed in onDisable.
        }
    }

    companion object {
        /**
         * A fresh engine bound to the bundled Latin model, or null when the
         * payloads the native pipeline needs (libmlkit_google_ocr_pipeline.so +
         * mlkit-google-ocr-models assets) are not staged. Returns the refusal
         * message in [out] on failure.
         */
        fun create(context: Context, payloadError: (String) -> Unit): MlKitOcrEngine? {
            val missing = PluginPayloadLoader.ensureOcrModelAssets(context) ?: return null
            payloadError(missing)
            return null
        }
    }
}

/**
 * The platform OCR factory slice (phase-175: moved with the engine).
 *
 * Creating the real recognizer MUST also preload the native OCR pipeline, so it
 * runs inside [create] under a guard — the recognizer client itself fails
 * without the `.so`.
 */
object PlatformOcrEngines {
    /** The bundled, offline ML Kit Latin text recognizer (null when payloads missing). */
    fun createMlKit(context: Context, onPayloadMissing: (String) -> Unit): OcrEngine? {
        val nativeError = PluginPayloadLoader.ensureNativeLibraries(context)
        if (nativeError != null) {
            onPayloadMissing(nativeError)
            return null
        }
        return MlKitOcrEngine.create(context, onPayloadMissing)
    }
}