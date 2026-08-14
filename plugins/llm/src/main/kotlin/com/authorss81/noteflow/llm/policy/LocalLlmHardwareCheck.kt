package com.authorss81.noteflow.llm.policy

/**
 * PURE JVM — the downloadable LLM plugin's device-capability gate.
 *
 * The base-app's `DeviceCompatibilityManager` decides the tier for the WHOLE
 * app; a downloadable plugin must be self-contained and carry its OWN gate so it
 * never depends on app internals at runtime. The decision here is a pure
 * function of (cores, totalMemoryBytes, isLowRamDevice), injected by the
 * platform probe ([com.authorss81.noteflow.llm.LocalLlmPlugin] reads
 * `ActivityManager`), so the threshold logic is fully unit-testable.
 *
 * Rule (kept aligned with the app's tier heuristics): a device with ≤ 2 cores,
 * or ≤ 3 GB RAM, or the system `isLowRamDevice` flag, cannot run the local LLM
 * sensibly → the plugin reports [Unsupported] with an honest reason, never a
 * silent mid-inference crash.
 */
object LocalLlmHardwareCheck {

    const val MIN_CORES = 3
    const val MIN_RAM_BYTES = 3L * 1024L * 1024L * 1024L

    sealed class Result {
        data object Supported : Result()
        data class Unsupported(val reason: String) : Result()
    }

    /** Decide support from core count, total RAM and the low-RAM flag. */
    fun evaluate(cpuCores: Int, totalMemoryBytes: Long, isLowRamDevice: Boolean): Result {
        if (isLowRamDevice) {
            return Result.Unsupported(
                "This device is flagged as low-RAM, so the on-device LLM is not available."
            )
        }
        if (cpuCores < MIN_CORES) {
            return Result.Unsupported(
                "The on-device LLM needs at least $MIN_CORES CPU cores (this device has $cpuCores)."
            )
        }
        if (totalMemoryBytes > 0L && totalMemoryBytes < MIN_RAM_BYTES) {
            return Result.Unsupported(
                "The on-device LLM needs at least 3 GB RAM (this device has fewer)."
            )
        }
        return Result.Supported
    }
}