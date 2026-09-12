package com.authorss81.noteflow.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.authorss81.noteflow.services.SettingsManager

enum class DeviceTier {
    LOW_END,
    MID_RANGE,
    FLAGSHIP
}

object DeviceCompatibilityManager {

    /**
     * Auto-detects the device tier based on CPU cores, RAM size, low-ram flag, and heuristics.
     *
     * Phase 269: the thresholds live in [DeviceTierPolicy.classifyTier] (pure
     * JVM, documented + unit-tested there); this function only reads the
     * platform inputs and delegates. A broken context (no ActivityManager /
     * unreadable memory info) fails CLOSED to LOW_END — the pre-269 MID_RANGE
     * fallback granted the AGSL GPU path on unknown hardware.
     */
    fun detectDeviceTier(context: Context): DeviceTier {
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return DeviceTier.LOW_END
            if (activityManager.isLowRamDevice) {
                return DeviceTier.LOW_END
            }

            // Get total RAM
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            val totalRamGb = memInfo.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)

            // Get CPU Cores (logical — see DeviceTierPolicy for why RAM gates flagship too)
            val cpuCores = Runtime.getRuntime().availableProcessors()

            return DeviceTierPolicy.classifyTier(
                isLowRamDevice = false,
                totalRamGb = totalRamGb,
                cpuCores = cpuCores
            )
        } catch (e: Exception) {
            return DeviceTier.LOW_END
        }
    }

    /**
     * Gets the active device tier, taking user override into account.
     */
    fun getDeviceTier(context: Context, settings: SettingsManager): DeviceTier {
        val override = settings.deviceTierOverride
        if (override != null) {
            return try {
                DeviceTier.valueOf(override)
            } catch (e: Exception) {
                detectDeviceTier(context)
            }
        }
        return detectDeviceTier(context)
    }

    // Capability Checks
    /**
     * Phase 269: delegates to the single [AgslGate] truth (SDK >= 33 AND tier
     * != LOW_END) — the same gate `AnnotationCanvas` allocates/uses behind,
     * so a LOW_END re-enable of `gpuWetBrushes` can no longer run the shader.
     */
    fun isAgslSupported(context: Context, settings: SettingsManager): Boolean {
        val tier = getDeviceTier(context, settings)
        return AgslGate.isSupported(Build.VERSION.SDK_INT, tier)
    }

    fun isDynamicColorSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S // API 31+
    }
}
