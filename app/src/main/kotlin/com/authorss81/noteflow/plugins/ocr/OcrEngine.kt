package com.authorss81.noteflow.plugins.ocr

import android.content.Context

/**
 * The one place a real OCR model runs.
 *
 * [OnDeviceOcrPlugin] deliberately depends only on this interface — the actual
 * model runner is a platform implementation ([MlKitOcrEngine]). Keeping the
 * plugin itself engine-agnostic makes the whole wrapper (input validation,
 * result formatting, error mapping, cancellation) pure JVM and unit-testable
 * with an injected fake engine; the ML Kit model itself is platform-only and
 * CANNOT run inside a JVM unit test (no Android runtime / native model).
 */
interface OcrEngine {

    /**
     * Recognize text in the image file at [imagePath] and return the raw
     * extracted text (line structure preserved).
     *
     * Implementations MUST be suspension-friendly and cancelable: when the
     * calling coroutine is cancelled, the underlying model task must be
     * cancelled too. The raw text is NOT normalized here — formatting is the
     * plugin's pure wrapper's job.
     */
    suspend fun recognize(context: Context?, imagePath: String): String
}
