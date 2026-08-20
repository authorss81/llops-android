package com.authorss81.noteflow.plugins.dictation

import android.content.Context
import com.authorss81.noteflow.plugins.DictationListener
import com.authorss81.noteflow.plugins.DictationSession

/**
 * THE interface between [OnDeviceDictationPlugin] and the platform speech
 * recognizer — exactly the "injected engine" pattern the framework uses for
 * the (now downloadable) OCR plugin: a typed engine seam with the same shape
 * as [com.authorss81.noteflow.plugins.OcrPlugin]. The plugin's wrapper
 * logic stays pure JVM and is unit-tested with a fake engine; only
 * [AndroidDictationEngine] touches `SpeechRecognizer`.
 */
interface DictationEngine {
    /** Whether speech recognition can run on this device at all. */
    fun isRecognitionAvailable(context: Context?): Boolean

    /** Whether OFFLINE (on-device) recognition models exist on this device. */
    fun isOnDeviceSupported(context: Context?): Boolean

    /**
     * Start a live session. The engine must:
     *  - prefer on-device (`EXTRA_PREFER_OFFLINE`) recognition,
     *  - never start recording unless explicitly asked ([OnDeviceDictationPlugin]),
     *  - bridge partials → [DictationListener.onPartialUtterance],
     *    finals → [DictationListener.onFinalUtterance],
     *    platform errors → [DictationListener.onError],
     *    completion → [DictationListener.onEnd] (exactly once).
     */
    fun start(context: Context?, listener: DictationListener): DictationSession
}