package com.authorss81.noteflow.llm.engine

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PLATFORM-ONLY — drives MediaPipe `tasks-genai`'s `LlmInference` (the Google
 * AI Edge engine, GGUF support). CANNOT run in a JVM unit test (native .so +
 * Android runtime); reached only from [LocalLlmPlugin] when no fake engine is
 * injected.
 *
 * Uses the CPU/smart backend (no GPU requirement — works on MID_RANGE Android
 * without Vulkan). Model work runs off the main thread; a failure to warm up
 * returns a user-facing message instead of crashing.
 */
internal class MediaPipeLlmEngine : InferenceEngine {

    private var llm: LlmInference? = null

    override fun isLoaded(): Boolean = llm != null

    override suspend fun warmUp(context: Context?, modelFile: File): String? {
        if (!modelFile.exists()) return "The model file is missing — please re-download it."
        return withContext(Dispatchers.Default) {
            try {
                // PHASE 29: the native engine is NOT in the base APK — it lives
                // in the downloadable artifact, so bind the bundled .so files
                // before first use (idempotent; System.loadLibrary finds a lib
                // that was already loaded via System.load).
                val nativeErr = context?.let { NativeLibraryBundle.ensureLoaded(it) }
                if (nativeErr != null) return@withContext nativeErr
                val options = runCatching {
                    LlmInferenceOptions.builder()
                        .setModelPath(modelFile.absolutePath)
                        .setMaxTokens(DEFAULT_MAX_TOKENS)
                        .build()
                }.getOrElse { e ->
                    return@withContext "The model couldn't be loaded (${e::class.java.simpleName})."
                }
                llm?.close()
                llm = LlmInference.createFromOptions(context, options)
                null
            } catch (e: Throwable) {
                "The model couldn't be loaded (${e::class.java.simpleName}). " +
                    "It may be incompatible with this device — try a smaller model."
            }
        }
    }

    override fun generate(prompt: String): String {
        val engine = llm ?: throw IllegalStateException("assistant model is not loaded")
        return engine.generateResponse(prompt)
    }

    override fun close() {
        runCatching { llm?.close() }
        llm = null
    }

    private companion object {
        const val DEFAULT_MAX_TOKENS = 480
    }
}

internal object PlatformAssistantEngines {
    fun create(): InferenceEngine = MediaPipeLlmEngine()
}
