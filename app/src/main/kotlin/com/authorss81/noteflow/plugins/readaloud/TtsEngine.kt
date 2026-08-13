package com.authorss81.noteflow.plugins.readaloud

import android.content.Context
import com.authorss81.noteflow.plugins.ReadAloudOutcome
import com.authorss81.noteflow.plugins.TtsChunk

/**
 * THE interface between [OnDeviceReadAloudPlugin] and the platform TTS — the
 * injected-engine pattern again. The plugin's wrapper logic is pure JVM
 * (unit-tested with a fake engine); only [AndroidTtsEngine] touches
 * `TextToSpeech`. [TtsEngine] is intentionally coarse: the plugin already
 * decided WHAT to speak via [ReadAloudPolicy]; the engine merely speaks an
 * already-sanctioned chunk list.
 */
interface TtsEngine {

    /**
     * Start speaking [chunks] (queued). Returns the outcome WITHOUT throwing —
     * a plugin-engine failure is a user-facing [ReadAloudOutcome.Error], never
     * an exception escaping to the framework.
     */
    fun play(context: Context?, chunks: List<TtsChunk>): ReadAloudOutcome

    /** Silence any active playback immediately. */
    fun stop()

    /** Release the platform engine (disable/teardown). */
    fun shutdown()
}