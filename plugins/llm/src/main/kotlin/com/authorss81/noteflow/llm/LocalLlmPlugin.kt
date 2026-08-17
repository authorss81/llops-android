package com.authorss81.noteflow.llm

import android.app.ActivityManager
import android.content.Context
import com.authorss81.noteflow.llm.engine.AssistantModelDownloader
import com.authorss81.noteflow.llm.engine.InferenceEngine
import com.authorss81.noteflow.llm.engine.PlatformAssistantEngines
import com.authorss81.noteflow.llm.policy.AssistantPrompts
import com.authorss81.noteflow.llm.policy.AssistantStoragePolicy
import com.authorss81.noteflow.llm.policy.LocalLlmHardwareCheck
import com.authorss81.noteflow.plugins.AssistantOutcome
import com.authorss81.noteflow.plugins.AssistantPlugin
import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.PluginAvailability
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginManifest
import com.authorss81.noteflow.plugins.PluginPermission
import com.authorss81.noteflow.plugins.PluginSettings
import com.authorss81.noteflow.plugins.SemanticVersion
import com.authorss81.noteflow.plugins.runtime.PluginContext
import com.authorss81.noteflow.plugins.runtime.PluginContextAware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The downloadable LOCAL LLM assistant plugin (Phase 29).
 *
 * This class is NOT compiled into the base APK — it lives in the
 * `plugins/llm` module and is delivered as a signature-verified downloadable
 * artifact (see `docs/plugin-architecture.md` § Downloadable runtime). The base
 * app ships only an ["install me"] unavailable stub; once the user installs the
 * artifact from the store (consent → HTTPS → pinned-cert + SHA-256 verify →
 * DexClassLoader load), THIS plugin is materialized and serves
 * [PluginCapability.Assistant].
 *
 * It implements the exact framework surface the app's classloader resolves
 * ([NoteflowPlugin] + [AssistantPlugin] + [PluginContextAware] from
 * `plugin-sdk`), plus the load-time wiring contract that hands it a
 * capability [PluginContext] — it holds the facade but never reaches for a
 * direct `Context`, DB, keystore or decrypted-content handle.
 *
 * The engine (MediaPipe tasks-genai) and its native `.so` libraries ride inside
 * the artifact; the model GGUF is downloaded once (consent + progress) into
 * app-private files, after which everything runs offline and keyless.
 *
 * B2-DEPS-05 (phase-77): the model download is pinned — fixed URL, allow-listed
 * huggingface.co host family, `instanceFollowRedirects = false` + per-hop
 * re-validation, exact size AND SHA-256 must match the published digest. The
 * old arbitrary `model_url` override is gone.
 *
 * @param engine injected inference engine (fake in JVM tests). Null → the
 *   platform MediaPipe engine is created lazily on device.
 */
class LocalLlmPlugin(
    private val engine: InferenceEngine? = null
) : NoteflowPlugin, AssistantPlugin, PluginContextAware {

    override val manifest = PluginManifest(
        id = PLUGIN_ID,
        name = "On-Device Assistant",
        version = SemanticVersion(1, 0, 0),
        minSupportedApi = MIN_API,
        description = "A small local LLM (MediaPipe tasks-genai) that summarizes, " +
            "finds action items, suggests tags and answers questions — 100% offline, " +
            "keyless, after a one-time model download.",
        capabilities = setOf(PluginCapability.Assistant),
        permissions = setOf(PluginPermission.Internet)
    )

    /**
     * The capability facade the Phase-23 runtime injected at load.
     */
    @Volatile
    private var injectedContext: PluginContext? = null

    @Volatile
    private var platformEngine: InferenceEngine? = null

    override fun setContext(context: PluginContext) {
        injectedContext = context
    }

    override fun availability(context: Context?): PluginAvailability {
        return when (val reason = unavailableReason(context)) {
            null -> PluginAvailability.Ok
            else -> PluginAvailability.Unavailable(reason)
        }
    }

    // B2-DEPS-05: the plugin no longer reads any per-plugin setting (the old
    // `model_url` override was the arbitrary-URL vector). The lifecycle hooks
    // keep their interface shape but the download is always the pinned model.
    override fun onEnable(context: Context?, settings: PluginSettings) {
    }

    override fun onDisable(context: Context?, settings: PluginSettings) {
        close()
    }

    override fun onConfigChanged(context: Context?, settings: PluginSettings) {
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
        val err = kotlinx.coroutines.runBlocking { engineFor(ctx).warmUp(ctx, model) }
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
            val dir = File(ctx.filesDir, STORAGE_REL_DIR)
            File(dir, AssistantStoragePolicy.DEFAULT_MODEL_FILE_NAME).takeIf { it.exists() && it.length() > 0 }
        } catch (_: Throwable) {
            null
        }
    }

    override fun expectedModelSizeBytes(): Long = AssistantStoragePolicy.DEFAULT_MODEL_SIZE_BYTES

    override fun unavailableReason(context: Context?): String? {
        if (context == null) return "The on-device assistant needs a device context to be verified."
        return try {
            when (val gate = hardwareProbe(context)) {
                is LocalLlmHardwareCheck.Result.Unsupported -> gate.reason
                LocalLlmHardwareCheck.Result.Supported -> null
            }
        } catch (_: Throwable) {
            // Unknown hardware state must not brick the flow — the engine's own
            // warm-up will fail loudly with a device-specific message instead.
            null
        }
    }

    override suspend fun downloadModel(context: Context?, onProgress: (Float) -> Unit): AssistantOutcome {
        val ctx = context ?: return AssistantOutcome.Error("The on-device assistant needs a device context.")
        unavailableReason(ctx)?.let { return AssistantOutcome.Error(it) }
        return try {
            // B2-DEPS-05: the model is a PINNED identity (URL + size + SHA-256,
            // see AssistantStoragePolicy + ModelDownloadPolicy) — there is no
            // user-supplied URL override. The downloader verifies the host
            // allow-list, every redirect hop, the exact byte count AND the
            // SHA-256 before the file is accepted.
            val downloader = AssistantModelDownloader(AssistantStoragePolicy.DEFAULT_MODEL_FILE_NAME)
            val result = downloader.download(ctx, onProgress)
            result.error?.let { return AssistantOutcome.Error(it) }
            AssistantOutcome.Success(
                "Assistant model is downloaded and verified — everything now runs fully offline."
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
                "$STORAGE_REL_DIR/${AssistantStoragePolicy.DEFAULT_MODEL_FILE_NAME}.part"
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
        val eng = engineFor(context)
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

    private fun engineFor(context: Context?): InferenceEngine {
        engine?.let { return it }
        return platformEngine ?: PlatformAssistantEngines.create().also { platformEngine = it }
    }

    /** Platform probe feeding the pure-JVM [LocalLlmHardwareCheck]. */
    private fun hardwareProbe(context: Context): LocalLlmHardwareCheck.Result {
        val am = (if (context != null) {
            context.getSystemService(Context.ACTIVITY_SERVICE)
        } else null) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        return LocalLlmHardwareCheck.evaluate(
            cpuCores = Runtime.getRuntime().availableProcessors(),
            totalMemoryBytes = memInfo.totalMem,
            isLowRamDevice = am?.isLowRamDevice ?: false
        )
    }

    private companion object {
        const val PLUGIN_ID = "com.authorss81.noteflow.plugins.llm"
        const val MIN_API = 26
        const val STORAGE_REL_DIR = "noteflow/assistant"
    }
}