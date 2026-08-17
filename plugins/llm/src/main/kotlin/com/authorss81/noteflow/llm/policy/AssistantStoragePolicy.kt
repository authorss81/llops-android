package com.authorss81.noteflow.llm.policy

/**
 * PURE JVM — the assistant's model-download policy: the PINNED default model
 * identity (URL, filename, exact byte size and SHA-256), the free-space rule
 * and the coarse plausibility check. No Android dependencies, fully
 * unit-tested.
 *
 * The default model is an unlocked, Apache-2.0 small chat GGUF (Qwen2-0.5B
 * Instruct, `q4_k_m` — 379 MB) hosted on Hugging Face. The model is never
 * bundled in the APK and never committed to git; the user downloads it once
 * (consent + progress) into app-private files, after which everything works
 * offline.
 *
 * B2-DEPS-05 (phase-77): the model is the one non-code artifact in the trust
 * chain, so it now carries the SAME treatment the plugin code artifact does —
 * a PINNED identity. [DEFAULT_MODEL_SHA256] is the git-LFS SHA-256 HuggingFace
 * publishes for this exact file and [DEFAULT_MODEL_SIZE_BYTES] is the file's
 * real byte count (both re-verified 2026-08-17 against the HF repo tree API).
 * `AssistantModelDownloader` accepts the file ONLY when its size AND SHA-256
 * both match, and the download host is allow-listed to huggingface.co + the HF
 * CDN family. The URL is deliberately NO LONGER overridable via any setting —
 * the pinned model is the only one the app accepts.
 */
object AssistantStoragePolicy {

    /**
     * Default model. URL is user-visible (Settings → Plugins → Assistant) and
     * FIXED — the previous `plugins.<id>.model_url` override is gone (B2-DEPS-05):
     * an arbitrary user-supplied URL cannot be hash-verified and would let any
     * host serve attacker-chosen weight bytes.
     */
    const val DEFAULT_MODEL_URL =
        "https://huggingface.co/Qwen/Qwen2-0.5B-Instruct-GGUF/resolve/main/qwen2-0_5b-instruct-q4_k_m.gguf"

    const val DEFAULT_MODEL_FILE_NAME = "assistant-model.gguf"

    /** On-disk size of the default model, in bytes. This is the git-LFS `size`
     *  HuggingFace publishes for `qwen2-0_5b-instruct-q4_k_m.gguf`
     *  (397,805,248 B = 379.4 MiB — the pre-fix 398 MiB approximation was
     *  wrong and was never compared against anything). */
    const val DEFAULT_MODEL_SIZE_BYTES: Long = 397_805_248L

    /** SHA-256 of the default model's bytes — the git-LFS `oid` HuggingFace
     *  publishes for this exact file. Every accepted download must hash to
     *  exactly this value; a stale/poisoned file on disk is re-verified and
     *  re-downloaded when it does not. */
    const val DEFAULT_MODEL_SHA256 =
        "f0a42bb979ca62b5e61f3bf924ab4b6a40aa091825ee7dcb4039949980ab81a8"

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