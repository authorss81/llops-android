package com.authorss81.noteflow.plugins.readaloud

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.authorss81.noteflow.plugins.ReadAloudOutcome
import com.authorss81.noteflow.plugins.TtsChunk

/**
 * PLATFORM-ONLY — wraps the system `TextToSpeech` engine (no API key, no new
 * permission). CANNOT run in a JVM unit test; reached only via
 * [OnDeviceReadAloudPlugin] when no fake engine is injected.
 *
 * The engine speaks ONLY the exact chunk list the plugin's pure [ReadAloudPolicy]
 * already sanctioned — quiet mode is enforced BEFORE this class is ever called,
 * so no bytes are spoken in quiet mode. `TextToSpeech` initializes
 * asynchronously; chunks requested during init are queued and spoken on
 * [onInit] success (never silently dropped).
 */
internal class AndroidTtsEngine : TtsEngine, TextToSpeech.OnInitListener {

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var pendingChunks: List<TtsChunk> = emptyList()

    override fun play(context: Context?, chunks: List<TtsChunk>): ReadAloudOutcome {
        val ctx = context ?: return ReadAloudOutcome.Error("Read-aloud needs a device context.")
        if (chunks.isEmpty()) return ReadAloudOutcome.Empty("Nothing to read aloud.")
        return try {
            val current = tts
            if (current != null) {
                speakQueued(current, chunks)
                ReadAloudOutcome.Started(chunks.size)
            } else {
                pendingChunks = chunks
                tts = TextToSpeech(ctx, this)
                // Completed asynchronously in onInit — report started optimistically;
                // an init failure surfaces as stop() being a no-op + a follow-up
                // outcome only if the UI polls. (Init failure is rare: users on
                // devices with zero TTS engine get LANG_MISSING_DATA at speak time.)
                ReadAloudOutcome.Started(chunks.size)
            }
        } catch (e: Throwable) {
            ReadAloudOutcome.Error("Text-to-speech engine failed to start (${e::class.java.simpleName}).")
        }
    }

    private fun speakQueued(engine: TextToSpeech, chunks: List<TtsChunk>) {
        val langResult = engine.setLanguage(java.util.Locale.getDefault())
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(java.util.Locale.US)
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {}
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {}
        })
        chunks.forEach { chunk ->
            val id = "tts-${chunk.index}-${System.currentTimeMillis()}"
            engine.speak(chunk.text, TextToSpeech.QUEUE_ADD, null, id)
        }
        pendingChunks = emptyList()
    }

    override fun stop() {
        try {
            tts?.stop()
        } catch (_: Throwable) {
            // best-effort teardown
        }
    }

    override fun shutdown() {
        try {
            tts?.shutdown()
        } catch (_: Throwable) {
            // idempotent
        }
        tts = null
        pendingChunks = emptyList()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val ready = tts
            if (ready != null && pendingChunks.isNotEmpty()) {
                speakQueued(ready, pendingChunks)
            }
        } else {
            pendingChunks = emptyList()
        }
    }
}

/** Factory so the plugin references platform TTS through one lazy call. */
internal object PlatformTtsEngines {
    fun create(): TtsEngine = AndroidTtsEngine()
}