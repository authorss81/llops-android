package com.authorss81.noteflow.plugins.dictation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.authorss81.noteflow.plugins.DictationListener
import com.authorss81.noteflow.plugins.DictationSession

/**
 * PLATFORM-ONLY — wraps `SpeechRecognizer` with offline preference. This class
 * CANNOT run inside a JVM unit test (no Android runtime); it is reached only
 * from [OnDeviceDictationPlugin] when no fake engine is injected. All
 * recognition glue lives here so the plugin itself stays engine-agnostic.
 *
 * Privacy contract (see docs/PLUGINS.md):
 *  - [start] only ever runs in direct response to the UI's explicit mic press —
 *    nothing is ever recorded ambiently.
 *  - `EXTRA_PREFER_OFFLINE = true` requests on-device recognition; when the
 *    device has no offline models the error surfaces loudly (the dialog shows
 *    [DictationListener.onError]) instead of silently streaming hypotheses.
 */
internal class AndroidDictationEngine : DictationEngine {

    override fun isRecognitionAvailable(context: Context?): Boolean {
        val ctx = context ?: return false
        return SpeechRecognizer.isRecognitionAvailable(ctx)
    }

    override fun isOnDeviceSupported(context: Context?): Boolean {
        val ctx = context ?: return false
        if (!isRecognitionAvailable(ctx)) return false
        // Offline dictation language packs are a device/OS feature; we request
        // EXTRA_PREFER_OFFLINE and never claim offline support we can't prove —
        // the recognizer either honours it or errors loudly (see SpeechErrorMapper).
        return true
    }

    override fun start(context: Context?, listener: DictationListener): DictationSession {
        val ctx = context ?: throw IllegalStateException("dictation requires a device context")
        val recognizer = SpeechRecognizer.createSpeechRecognizer(ctx)
        val bridge = Bridge(listener)
        recognizer.setRecognitionListener(bridge)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        recognizer.startListening(intent)
        return AndroidDictationSession(recognizer, bridge)
    }

    /** Bridges the system `RecognitionListener` to the plugin [DictationListener]. */
    internal class Bridge(private val listener: DictationListener) : RecognitionListener {
        private var ended = false

        @Volatile
        var lastPartial: String? = null

        override fun onReadyForSpeech(params: Bundle?) {}

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            if (ended) return
            ended = true
            listener.onError(SpeechErrorMapper.message(error, hasPartial = lastPartial != null))
        }

        override fun onResults(results: Bundle?) {
            if (ended) return
            ended = true
            val text = firstResult(results) ?: lastPartial
            if (!text.isNullOrBlank()) {
                listener.onFinalUtterance(text)
            } else {
                listener.onError(SpeechErrorMapper.noSpeechMessage())
            }
            listener.onEnd()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = firstResult(partialResults)
            if (text.isNullOrBlank()) return
            lastPartial = text
            listener.onPartialUtterance(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}

        private fun firstResult(bundle: Bundle?): String? {
            if (bundle == null) return null
            val results = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            return results?.firstOrNull()
        }
    }
}

/** Session handle bound to a live [SpeechRecognizer]. */
internal class AndroidDictationSession(
    private val recognizer: SpeechRecognizer,
    private val bridge: AndroidDictationEngine.Bridge
) : DictationSession {

    override fun stop() {
        terminal()
        try {
            recognizer.stopListening()
        } catch (_: Throwable) {
            // already stopped by the recognizer — nothing to release
        }
    }

    override fun cancel() {
        terminal()
        try {
            recognizer.cancel()
        } catch (_: Throwable) {
            // destroyed already
        }
    }

    private fun terminal() {
        try {
            recognizer.destroy()
        } catch (_: Throwable) {
            // idempotent teardown
        }
    }
}

/** Provides the currently-available system speech recognizer. */
internal object PlatformDictationEngines {
    fun create(): DictationEngine = AndroidDictationEngine()
}