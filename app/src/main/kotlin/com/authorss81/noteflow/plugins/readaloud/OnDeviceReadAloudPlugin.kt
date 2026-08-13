package com.authorss81.noteflow.plugins.readaloud

import android.content.Context
import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.PluginAvailability
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginManifest
import com.authorss81.noteflow.plugins.PluginSettings
import com.authorss81.noteflow.plugins.ReadAloudOutcome
import com.authorss81.noteflow.plugins.ReadAloudPlugin
import com.authorss81.noteflow.plugins.SemanticVersion
import com.authorss81.noteflow.plugins.TtsChunk
import com.authorss81.noteflow.plugins.TtsSpeechPlan

/**
 * The real read-aloud plugin (Phase 16).
 *
 * Serves [PluginCapability.ReadAloud] via [ReadAloudPlugin]. Playback is NEVER
 * automatic — [play] only runs in direct response to an explicit user tap, and
 * a user-enabled quiet mode (SilentToggle) makes the queue refuse with
 * [ReadAloudOutcome.Quiet] before the engine is ever touched. Uses the
 * platform `TextToSpeech` engine: no API key, no new permission, fully offline.
 *
 * Split like every Phase-15/16 plugin:
 *  - [TtsChunkSplitter] + [ReadAloudPolicy] — PURE JVM (unit-tested).
 *  - [AndroidTtsEngine] — platform `TextToSpeech` glue (platform-only).
 *
 * @param engine injected TTS engine (fake in JVM tests). Null → the platform
 *   `TextToSpeech` engine is created lazily on device.
 */
class OnDeviceReadAloudPlugin(
    private val engine: TtsEngine? = null
) : NoteflowPlugin, ReadAloudPlugin {

    override val manifest = PluginManifest(
        id = "com.authorss81.noteflow.plugins.readaloud",
        name = "Read Aloud",
        version = SemanticVersion(1, 0, 0),
        minSupportedApi = MIN_API,
        description = "Reads the current note aloud with the platform text-to-speech " +
            "engine — offline, keyless, respects SilentToggle (a quiet mode that " +
            "refuses to speak rather than degrading silently).",
        capabilities = setOf(PluginCapability.ReadAloud)
    )

    override fun availability(context: Context?): PluginAvailability {
        // TextToSpeech works on virtually every Android device, with or without
        // Google TTS. Gate only on the platform existing at all (API check).
        return if (android.os.Build.VERSION.SDK_INT < MIN_API) {
            PluginAvailability.Unavailable("Read-aloud needs Android 8.0 (API 26) or newer.")
        } else {
            PluginAvailability.Ok
        }
    }

    override fun onEnable(context: Context?, settings: PluginSettings) {
        // No settings yet — a reading-speed option would migrate here.
    }

    override fun onDisable(context: Context?, settings: PluginSettings) {
        engine?.shutdown()
        platformEngine?.shutdown()
        platformEngine = null
    }

    override fun selfCheck(context: Context?): PluginAvailability {
        if (context == null) return PluginAvailability.Unknown
        return try {
            // TextToSpeech can't be probed without initializing the engine; a
            // real availability answer requires a device. Default to Ok on a
            // device context (API gate already passed) so "Test now" doesn't
            // false-alarm; failures surface at [play] time with a clear message.
            PluginAvailability.Ok
        } catch (_: Throwable) {
            PluginAvailability.Unavailable("Text-to-speech could not be verified on this device.")
        }
    }

    // ---- ReadAloudPlugin serving interface ----------------------------------

    override fun chunkText(passage: String, maxChunkChars: Int): List<TtsChunk> =
        TtsChunkSplitter.chunkText(passage, maxChunkChars)

    override fun plan(passage: String, quietMode: Boolean, maxChunkChars: Int): TtsSpeechPlan =
        ReadAloudPolicy.plan(passage, quietMode, maxChunkChars)

    override fun play(context: Context?, passage: String, quietMode: Boolean): ReadAloudOutcome {
        return when (val plan = plan(passage, quietMode)) {
            is TtsSpeechPlan.Play -> {
                try {
                    engineFor(context).play(context, plan.chunks)
                } catch (e: Throwable) {
                    ReadAloudOutcome.Error(
                        "Read-aloud engine failed (${e::class.java.simpleName}). Try again."
                    )
                }
            }
            is TtsSpeechPlan.RefuseQuiet -> ReadAloudOutcome.Quiet(plan.message)
            TtsSpeechPlan.NothingToSpeak -> ReadAloudOutcome.Empty(
                "There's nothing to read aloud in this note yet."
            )
        }
    }

    override fun stop(context: Context?) {
        try {
            engine?.stop()
            platformEngine?.stop()
        } catch (_: Throwable) {
            // best-effort
        }
    }

    override fun shutdown(context: Context?) {
        engine?.shutdown()
        platformEngine?.shutdown()
        platformEngine = null
    }

    // ---- internals ---------------------------------------------------------

    private fun engineFor(context: Context?): TtsEngine {
        engine?.let { return it }
        return platformEngine ?: PlatformTtsEngines.create().also { platformEngine = it }
    }

    @Volatile
    private var platformEngine: TtsEngine? = null

    private companion object {
        const val MIN_API = 26
    }
}