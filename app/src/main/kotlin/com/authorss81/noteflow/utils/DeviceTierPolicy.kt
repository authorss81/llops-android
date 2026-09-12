package com.authorss81.noteflow.utils

/**
 * Phase 269 (compat): recalibrated device-tier decision table.
 *
 * The pre-269 heuristic in `DeviceCompatibilityManager.detectDeviceTier` had
 * four defects (see `workspace/phase-269/PROMPT.md`):
 *  1. `totalRamGb <= 3.0` classified 3 GB phones as LOW_END — 3 GB is a
 *     mid-range device; Android Go (the `isLowRamDevice` signal) sits at
 *     ~2 GB and below.
 *  2. `availableProcessors()` counts LOGICAL cores, so 8× little-core
 *     (Cortex-A53) SoCs escaped into FLAGSHIP on core count alone.
 *  3. `cpuCores > 6` excluded exactly-6 big-core / 8 GB devices (MID) that
 *     are flagship-class for a 2D-ink workload.
 *  4. The `catch` fell back to MID_RANGE — on a broken context (no
 *     ActivityManager) that GRANTS the AGSL GPU path; fail-closed is LOW_END.
 *
 * Recalibrated table (pure JVM, unit-testable — Android calls stay in
 * `DeviceCompatibilityManager`, which only reads the inputs and delegates):
 *
 * | inputs | tier |
 * |---|---|
 * | `isLowRamDevice == true` (OS Go signal) | LOW_END |
 * | `totalRamGb <= 2.0` | LOW_END |
 * | `cpuCores <= 2` | LOW_END |
 * | `cpuCores >= 6 && totalRamGb >= 6.0` | FLAGSHIP |
 * | otherwise (incl. 3 GB phones, 8× little + small RAM, 6c/8 GB is FLAGSHIP now) | MID_RANGE |
 *
 * Non-finite / non-positive RAM or core readings are treated as unknown
 * hardware: when the OS cannot even report them the fail-closed answer is
 * LOW_END (no AGSL shader, capped graph, grain off) rather than MID.
 */
object DeviceTierPolicy {

    /** At or below this RAM the device is Go-class, regardless of core count. */
    const val LOW_END_MAX_RAM_GB = 2.0

    /** At or below this core count the device cannot hold the GPU path. */
    const val LOW_END_MAX_CORES = 2

    /** Flagship needs BOTH this many logical cores AND the RAM below. */
    const val FLAGSHIP_MIN_CORES = 6
    const val FLAGSHIP_MIN_RAM_GB = 6.0

    fun classifyTier(
        isLowRamDevice: Boolean,
        totalRamGb: Double,
        cpuCores: Int
    ): DeviceTier {
        if (isLowRamDevice) return DeviceTier.LOW_END
        if (!totalRamGb.isFinite() || totalRamGb <= 0.0 || cpuCores <= 0) {
            return DeviceTier.LOW_END
        }
        if (totalRamGb <= LOW_END_MAX_RAM_GB || cpuCores <= LOW_END_MAX_CORES) {
            return DeviceTier.LOW_END
        }
        if (cpuCores >= FLAGSHIP_MIN_CORES && totalRamGb >= FLAGSHIP_MIN_RAM_GB) {
            return DeviceTier.FLAGSHIP
        }
        return DeviceTier.MID_RANGE
    }
}
