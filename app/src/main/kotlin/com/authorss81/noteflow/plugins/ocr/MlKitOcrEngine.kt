package com.authorss81.noteflow.plugins.ocr

import android.content.Context
import android.net.Uri
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
 * The real, on-device OCR model runner: Google's ML Kit text-recognition (Latin
 * script, bundled model). Runs fully offline, needs no API key and no INTERNET —
 * the app's exact constraints for this phase.
 *
 * PLATFORM-ONLY: this class talks to ML Kit's native `TextRecognizer`, which
 * cannot run inside a JVM unit test (no Android runtime, no native model). It is
 * reached only from production code paths ([OnDeviceOcrPlugin] when no engine is
 * injected). The plugin's pure wrapper logic is covered by `OcrPluginWrapperTest`;
 * the model invocation itself is verified on-device/emulator (documented in
 * docs/PLUGINS.md).
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
            // cancellation boundary. Nothing further to release here — the
            // recognizer is process-lifetime and closed in onDisable.
        }
    }

    companion object {
        /** A fresh engine bound to the bundled Latin model. */
        fun create(context: Context): MlKitOcrEngine =
            MlKitOcrEngine(TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS))
    }
}
