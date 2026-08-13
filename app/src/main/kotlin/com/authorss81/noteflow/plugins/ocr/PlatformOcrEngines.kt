package com.authorss81.noteflow.plugins.ocr

import android.content.Context

/**
 * Factory for the platform OCR engines.
 *
 * Kept in its own file so [OnDeviceOcrPlugin] references ML Kit only through a
 * single lazy call site: a JVM unit test that injects a fake [OcrEngine] never
 * loads this class, so the framework's JVM testability is preserved while
 * production still wires the real model.
 */
object PlatformOcrEngines {

    /** The bundled, offline ML Kit Latin text recognizer. */
    fun createMlKit(context: Context): OcrEngine = MlKitOcrEngine.create(context)
}
