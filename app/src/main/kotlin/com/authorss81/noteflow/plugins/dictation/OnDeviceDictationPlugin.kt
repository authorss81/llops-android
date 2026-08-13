package com.authorss81.noteflow.plugins.dictation

import android.content.Context
import com.authorss81.noteflow.plugins.DictationListener
import com.authorss81.noteflow.plugins.DictationPlugin
import com.authorss81.noteflow.plugins.DictationSession
import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.PluginAvailability
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginManifest
import com.authorss81.noteflow.plugins.PluginPermission
import com.authorss81.noteflow.plugins.PluginSettings
import com.authorss81.noteflow.plugins.SemanticVersion

/**
 * The real, on-device dictation plugin (Phase 16).
 *
 * Serves [PluginCapability.Dictation] via [DictationPlugin]. Voice activation
 * is ALWAYS explicit — the UI shows a mic button and the session only starts on
 * a direct user tap; nothing is ever recorded ambiently.
 *
 * Split exactly like [com.authorss81.noteflow.plugins.ocr.OnDeviceOcrPlugin]:
 *  - [DictationAssembler] — PURE JVM utterance-assembly rules (unit-tested).
 *  - [SpeechErrorMapper] — PURE JVM error mapping (unit-tested).
 *  - [AndroidDictationEngine] — platform `SpeechRecognizer` glue (platform-only).
 *
 * @param engine injected engine (fake in JVM tests / future recognizers). Null
 *   → the platform `SpeechRecognizer` is used lazily on device.
 */
class OnDeviceDictationPlugin(
    private val engine: DictationEngine? = null
) : NoteflowPlugin, DictationPlugin {

    override val manifest = PluginManifest(
        id = "com.authorss81.noteflow.plugins.dictation",
        name = "On-Device Dictation",
        version = SemanticVersion(1, 0, 0),
        minSupportedApi = MIN_API,
        description = "Type by voice. Speech recognition runs on-device whenever " +
            "offline models exist; activation is always a deliberate mic tap.",
        capabilities = setOf(PluginCapability.Dictation),
        permissions = setOf(PluginPermission.RecordAudio)
    )

    override fun availability(context: Context?): PluginAvailability {
        return try {
            if (engineFor(context).isRecognitionAvailable(context)) PluginAvailability.Ok
            else PluginAvailability.Unavailable(SpeechErrorMapper.isAvailableMessage())
        } catch (e: Throwable) {
            PluginAvailability.Unavailable(
                "Dictation engine unavailable (${e::class.java.simpleName})."
            )
        }
    }

    override fun onEnable(context: Context?, settings: PluginSettings) {
        // No settings yet — a punctuation/period-pause option would migrate here
        // (settings_schema pattern, see docs/PLUGIN_SDK.md § 4).
    }

    override fun onDisable(context: Context?, settings: PluginSettings) {
        platformEngine?.let { engine ->
            (engine as? AutoCloseable)?.close()
            platformEngine = null
        }
    }

    override fun selfCheck(context: Context?): PluginAvailability {
        val ctx = context ?: return PluginAvailability.Unknown
        return try {
            if (engineFor(ctx).isRecognitionAvailable(ctx)) PluginAvailability.Ok
            else PluginAvailability.Unavailable("No speech recognizer present on this device.")
        } catch (e: Throwable) {
            PluginAvailability.Unavailable("Dictation engine init failed (${e::class.java.simpleName}).")
        }
    }

    // ---- DictationPlugin serving interface ----------------------------------

    override fun isOnDeviceAvailable(context: Context?): Boolean {
        return try {
            engineFor(context).isOnDeviceSupported(context)
        } catch (_: Throwable) {
            false
        }
    }

    override fun onDeviceAvailabilityMessage(): String =
        "Offline speech models aren't installed on this device, so dictation would " +
            "send audio to a network recognizer. Add an offline speech pack in " +
            "System settings → Language & input, or keep dictation off to stay private."

    override fun startSession(context: Context?, listener: DictationListener): DictationSession =
        engineFor(context).start(context, listener)

    override fun appendUtterance(currentText: String, utterance: String): String =
        DictationAssembler.appendUtterance(currentText, utterance)

    // ---- internals ---------------------------------------------------------

    private fun engineFor(context: Context?): DictationEngine {
        engine?.let { return it }
        return platformEngine ?: PlatformDictationEngines.create().also { platformEngine = it }
    }

    @Volatile
    private var platformEngine: DictationEngine? = null

    private companion object {
        const val MIN_API = 26
    }
}