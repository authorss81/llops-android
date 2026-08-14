package com.authorss81.noteflow.plugins.assistant

import android.content.Context
import com.authorss81.noteflow.plugins.AssistantOutcome
import com.authorss81.noteflow.plugins.AssistantPlugin
import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.PluginAvailability
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginManifest
import com.authorss81.noteflow.plugins.PluginSettings
import com.authorss81.noteflow.plugins.SemanticVersion
import com.authorss81.noteflow.services.SettingsManager
import com.authorss81.noteflow.utils.DeviceCompatibilityManager
import com.authorss81.noteflow.utils.DeviceTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The real, offline AI assistant plugin (Phase 16).
 *
 * Serves the (pre-existing, exclusive) [PluginCapability.Assistant] via
 * [AssistantPlugin]. Runs a small local LLM through MediaPipe `tasks-genai`
 * (the Google AI Edge engine LiteRT-LM extends). The model is NOT bundled: the
 * user downloads a small GGUF once (`~398 MB` default, consent + progress,
 * free-space guarded) into app-private files — after that everything runs with
 * no network and no API key.
 *
 * Device reality gate: on low-end hardware (≤2 cores / ≤3 GB RAM, per
 * [DeviceCompatibilityManager]) the assistant is [PluginAvailability.Unavailable]
 * with a clear reason — never silent degradation. Prompt assembly and the
 * download policy are PURE JVM (unit-tested with a fake engine); only
 * [MediaPipeLlmEngine] + [AssistantModelDownloader] are platform.
 *
 * @param engine injected inference engine (fake in JVM tests). Null → the
 *   platform MediaPipe engine is created lazily on device.
 */
class OnDeviceAssistantPlugin(
    private val engine: InferenceEngine? = null
) : NoteflowPlugin, AssistantPlugin {

    override val manifest = PluginManifest(
        id = "com.authorss81.noteflow.plugins.assistant",
        name = "On-Device Assistant",
        version = SemanticVersion(1, 0, 0),
        minSupportedApi = MIN_API,
        description = "A small local LLM (MediaPipe tasks-genai) that summarizes, " +
            "finds action items, suggests tags and answers questions — 100% offline, " +
            "keyless, after a one-time model download.",
        capabilities = setOf(PluginCapability.Assistant),
        permissions = setOf(com.authorss81.noteflow.plugins.PluginPermission.Internet)
    )

    /** Settings slice captured at enable; holds the optional `model_url` override. */
    @Volatile
    private var settings: PluginSettings? = null

    @Volatile
    private var platformEngine: InferenceEngine? = null

    override fun availability(context: Context?): PluginAvailability {
        return when (val reason = unavailableReason(context)) {
            null -> PluginAvailability.Ok
            else -> PluginAvailability.Unavailable(reason)
        }
    }

    override fun onEnable(context: Context?, settings: PluginSettings) {
        this.settings = settings
    }

    override fun onDisable(context: Context?, settings: PluginSettings) {
        close()
    }

    override fun onConfigChanged(context: Context?, settings: PluginSettings) {
        this.settings = settings
    }

    override fun selfCheck(context: Context?): PluginAvailability {
        val ctx = context ?: return PluginAvailability.Unknown
        unavailableReason(ctx)?.let { return PluginAvailability.Unavailable(it) }
        val model = modelFile(ctx)
        if (model == null) {
            return PluginAvailability.Unavailable(
                "Assistant model not downloaded — open the Assistant and tap Download model."
            )
        }
        val err = kotlinx.coroutines.runBlocking { engineFor().warmUp(ctx, model) }
        return if (err == null) PluginAvailability.Ok
        else PluginAvailability.Unavailable("Assistant self-check: $err")
    }

    // ---- AssistantPlugin serving interface ---------------------------------

    override fun isModelDownloaded(context: Context?): Boolean {
        val file = modelFile(context) ?: return false
        return AssistantStoragePolicy.isPlausibleModelFile(file.length())
    }

    override fun modelFile(context: Context?): File? {
        val ctx = context ?: return null
        return try {
            val dir = File(ctx.filesDir, "noteflow/assistant")
            File(dir, AssistantStoragePolicy.DEFAULT_MODEL_FILE_NAME).takeIf { it.exists() && it.length() > 0 }
        } catch (_: Throwable) {
            null
        }
    }

    override fun expectedModelSizeBytes(): Long = AssistantStoragePolicy.DEFAULT_MODEL_SIZE_BYTES

    override fun unavailableReason(context: Context?): String? {
        if (context == null) return "The assistant needs a device context to be verified."
        return try {
            val settingsManager = SettingsManager(context)
            val tier = DeviceCompatibilityManager.getDeviceTier(context, settingsManager)
            if (tier == DeviceTier.LOW_END) {
                "The on-device assistant needs more capable hardware (this device is low-end). " +
                    "It becomes available if you raise the device tier in Settings."
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    override suspend fun downloadModel(context: Context?, onProgress: (Float) -> Unit): AssistantOutcome {
        val ctx = context ?: return AssistantOutcome.Error("The assistant needs a device context.")
        unavailableReason(ctx)?.let { return AssistantOutcome.Error(it) }
        val url = settings?.getString(SETTING_MODEL_URL)?.takeIf { it != null && it.isNotBlank() }
            ?: AssistantStoragePolicy.DEFAULT_MODEL_URL
        return try {
            val downloader = AssistantModelDownloader(AssistantStoragePolicy.DEFAULT_MODEL_FILE_NAME)
            val result = downloader.download(ctx, url, expectedModelSizeBytes(), onProgress)
            result.error?.let { return AssistantOutcome.Error(it) }
            AssistantOutcome.Success(
                "Assistant model is downloaded and everything now runs fully offline."
            )
        } catch (e: Throwable) {
            AssistantOutcome.Error(
                "Model download failed (${e::class.java.simpleName}). Check your connection and retry."
            )
        }
    }

    override suspend fun summarize(context: Context?, noteText: String): AssistantOutcome =
        runFor(context, noteText) { AssistantPrompts.summarize(it) }

    override suspend fun extractActionItems(context: Context?, noteText: String): AssistantOutcome =
        runFor(context, noteText) { AssistantPrompts.extractActionItems(it) }

    override suspend fun answerQuestion(
        context: Context?,
        noteText: String,
        question: String
    ): AssistantOutcome = runFor(context, noteText) { AssistantPrompts.answerQuestion(it, question) }

    override suspend fun suggestTags(context: Context?, noteText: String): AssistantOutcome =
        runFor(context, noteText) { AssistantPrompts.suggestTags(it) }

    override fun close() {
        engine?.close()
        platformEngine?.close()
        platformEngine = null
    }

    override fun deleteDownloadedAssets(context: Context?) {
        val ctx = context ?: return
        try {
            modelFile(ctx)?.delete()
            File(
                ctx.filesDir,
                "noteflow/assistant/${AssistantStoragePolicy.DEFAULT_MODEL_FILE_NAME}.part"
            ).delete()
        } catch (_: Throwable) {
            // Never throw into the store; leftover bytes are harmless.
        }
    }

    // ---- internals ---------------------------------------------------------

    /** Shared generation path: model present? → load → generate → typed outcome. */
    private suspend fun runFor(
        context: Context?,
        noteText: String,
        buildPrompt: (String) -> String
    ): AssistantOutcome {
        if (noteText.isBlank()) {
            return AssistantOutcome.Error("There's nothing for the assistant to work on — write in the note first.")
        }
        val eng = engineFor()
        if (!eng.isLoaded()) {
            val file = modelFile(context)
            if (file == null) {
                return AssistantOutcome.ModelNotReady(
                    "The assistant model isn't downloaded. Tap “Download model” once " +
                        "(then it runs fully offline)."
                )
            }
            val warmErr = eng.warmUp(context, file)
            if (warmErr?.isNotBlank() == true) {
                return AssistantOutcome.Error("The assistant model couldn't load: $warmErr")
            }
        }
        return try {
            val output = withContext(Dispatchers.Default) { eng.generate(buildPrompt(noteText)) }
            val clean = output.trim()
            if (clean.isBlank()) AssistantOutcome.Error("The assistant returned an empty answer — try again.")
            else AssistantOutcome.Success(clean)
        } catch (e: Throwable) {
            AssistantOutcome.Error("The assistant failed (${e::class.java.simpleName}). Try again.")
        }
    }

    private fun engineFor(): InferenceEngine {
        engine?.let { return it }
        return platformEngine ?: PlatformAssistantEngines.create().also { platformEngine = it }
    }

    private companion object {
        const val MIN_API = 26
        const val SETTING_MODEL_URL = "model_url"
    }
}