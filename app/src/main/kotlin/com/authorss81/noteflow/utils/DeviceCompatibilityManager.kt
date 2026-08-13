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
     */
    fun detectDeviceTier(context: Context): DeviceTier {
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (activityManager != null && activityManager.isLowRamDevice) {
                return DeviceTier.LOW_END
            }

            // Get total RAM
            val memInfo = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memInfo)
            val totalRamGb = memInfo.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)

            // Get CPU Cores
            val cpuCores = Runtime.getRuntime().availableProcessors()

            // Heuristics:
            // Low-end: ≤ 2 cores or ≤ 3.0 GB RAM
            if (cpuCores <= 2 || totalRamGb <= 3.0) {
                return DeviceTier.LOW_END
            }

            // Flagship: > 6 cores AND > 6.0 GB RAM
            if (cpuCores > 6 && totalRamGb > 6.0) {
                return DeviceTier.FLAGSHIP
            }

            // Default to Mid-Range
            return DeviceTier.MID_RANGE
        } catch (e: Exception) {
            return DeviceTier.MID_RANGE
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
    fun isAgslSupported(context: Context, settings: SettingsManager): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val tier = getDeviceTier(context, settings)
        if (tier == DeviceTier.LOW_END) {
            // On Low-End, restrict AGSL wet brushes to save memory/prevent lag
            return false
        }
        return true
    }

    fun isDynamicColorSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S // API 31+
    }

    fun isHardwareBitmapsSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O // API 26+
    }
}
