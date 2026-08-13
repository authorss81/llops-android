package com.authorss81.noteflow.plugins.assistant

/**
 * PURE JVM — the assistant's model-download policy: default model identity,
 * expected size and the free-space rule. No Android dependencies, fully
 * unit-tested.
 *
 * The default model is an unlocked, Apache-2.0 small chat GGUF (Qwen2-0.5B
 * Instruct, `q4_k_m` — 398 MB) hosted on Hugging Face. The model is never
 * bundled in the APK and never committed to git; the user downloads it once
 * (consent + progress) into app-private files, after which everything works
 * offline.
 */
object AssistantStoragePolicy {

    /**
     * Default model. URL is user-visible (Settings → Plugins → Assistant) and
     * overridable via the `plugins.<id>.model_url` setting.
     */
    const val DEFAULT_MODEL_URL =
        "https://huggingface.co/Qwen/Qwen2-0.5B-Instruct-GGUF/resolve/main/qwen2-0_5b-instruct-q4_k_m.gguf"

    const val DEFAULT_MODEL_FILE_NAME = "assistant-model.gguf"

    /** On-disk size of the default model, in bytes (398 MiB). */
    const val DEFAULT_MODEL_SIZE_BYTES: Long = 398L * 1024 * 1024

    /** Result of a free-space preflight for a download of [required] bytes. */
    sealed class SpaceCheck {
        data object Ok : SpaceCheck()
        data class Insufficient(val availableBytes: Long, val neededBytes: Long) : SpaceCheck()
    }

    /**
     * Preflight: [availableBytes] must cover the model plus a safety margin
     * (the GGUF is downloaded to a temp file before atomically moving it).
     */
    fun checkSpace(availableBytes: Long, requiredBytes: Long): SpaceCheck {
        if (availableBytes < 0 || requiredBytes <= 0) return SpaceCheck.Ok
        val needed = requiredBytes + SAFETY_MARGIN_BYTES
        return if (availableBytes >= needed) SpaceCheck.Ok
        else SpaceCheck.Insufficient(availableBytes, needed)
    }

    /** A successfully downloaded model must at least look like one (> 1 MB). */
    fun isPlausibleModelFile(lengthBytes: Long): Boolean = lengthBytes > 1024L * 1024

    private const val SAFETY_MARGIN_BYTES = 64L * 1024 * 1024
}