package com.authorss81.noteflow.plugins.assistant

import android.content.Context
import java.io.File

/**
 * THE interface between [OnDeviceAssistantPlugin] and the LLM runtime — the
 * injected-engine pattern. [MediaPipeLlmEngine] is platform-only (MediaPipe
 * `tasks-genai`); JVM unit tests inject a fake engine and drive the pure
 * prompt/policy logic.
 */
interface InferenceEngine {

    /** True when a model has been loaded into memory (generation is possible). */
    fun isLoaded(): Boolean

    /**
     * Load [modelFile] into the runtime. Returns null on success, or a
     * user-facing error message on failure. [context] is nullable (JVM tests
     * pass null); runs off the main thread.
     */
    suspend fun warmUp(context: Context?, modelFile: File): String?

    /** Generate a completion for [prompt]. Blocking engine call — run off the
     *  main thread. Never throws (a throwing engine is contained by the plugin). */
    fun generate(prompt: String): String

    /** Release the loaded model (plugin disable / teardown). */
    fun close()
}