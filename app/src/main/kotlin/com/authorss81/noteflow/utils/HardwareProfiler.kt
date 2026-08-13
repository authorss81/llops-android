package com.authorss81.noteflow.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.authorss81.noteflow.services.SettingsManager

enum class RenderingEngineTier(
    val id: String,
    val displayName: String,
    val description: String,
    val minimumApi: Int,
    val recommendedRamGb: Double,
    val recommendedCores: Int
) {
    STANDARD_VECTOR(
        id = "VECTOR",
        displayName = "Standard Vector Engine",
        description = "Low battery consumption, hardware-accelerated Compose Paths, zero GPU shader overhead (120 FPS smooth on all devices).",
        minimumApi = 26,
        recommendedRamGb = 2.0,
        recommendedCores = 2
    ),
    AGSL_WET_SHADER(
        id = "AGSL_WET",
        displayName = "AGSL Shaded Wet-Mixing Engine",
        description = "Real-time procedural AGSL fragment shaders with cold-press paper grain, edge pigment fringe, and 3D impasto relief lighting.",
        minimumApi = 33,
        recommendedRamGb = 4.0,
        recommendedCores = 4
    ),
    LIBMYPAINT_NATIVE(
        id = "LIBMYPAINT",
        displayName = "LibMyPaint C++ Native Studio Engine",
        description = "True C++ pigment physics & fluid dynamics simulation powered by libmypaint native C engine (64-bit NDK / High-End hardware).",
        minimumApi = 33,
        recommendedRamGb = 8.0,
        recommendedCores = 6
    );

    companion object {
        fun fromId(id: String?): RenderingEngineTier {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AGSL_WET_SHADER
        }
    }
}

data class HardwareProfile(
    val apiLevel: Int,
    val totalRamGb: Double,
    val availableCores: Int,
    val memoryClassMb: Int,
    val maxMemoryMb: Long,
    val is64Bit: Boolean,
    val recommendedEngine: RenderingEngineTier,
    val detectedDeviceTier: DeviceTier
)

object HardwareProfiler {

    /**
     * Profiles device hardware specs and determines the optimal recommended engine tier.
     */
    fun profile(context: Context): HardwareProfile {
        val apiLevel = Build.VERSION.SDK_INT
        val cores = Runtime.getRuntime().availableProcessors()
        
        var totalRamGb = 4.0
        var memoryClassMb = 256
        val maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (activityManager != null) {
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            totalRamGb = memInfo.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
            memoryClassMb = activityManager.memoryClass
        }

        val is64Bit = Build.SUPPORTED_64_BIT_ABIS != null && Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        val detectedTier = DeviceCompatibilityManager.detectDeviceTier(context)

        // Recommended engine determination rule:
        val recommendedEngine = when {
            apiLevel < 33 || totalRamGb < 3.5 || cores <= 2 -> {
                RenderingEngineTier.STANDARD_VECTOR
            }
            apiLevel >= 33 && totalRamGb >= 7.5 && cores >= 6 && is64Bit -> {
                RenderingEngineTier.LIBMYPAINT_NATIVE
            }
            apiLevel >= 33 -> {
                RenderingEngineTier.AGSL_WET_SHADER
            }
            else -> {
                RenderingEngineTier.STANDARD_VECTOR
            }
        }

        return HardwareProfile(
            apiLevel = apiLevel,
            totalRamGb = totalRamGb,
            availableCores = cores,
            memoryClassMb = memoryClassMb,
            maxMemoryMb = maxMemoryMb,
            is64Bit = is64Bit,
            recommendedEngine = recommendedEngine,
            detectedDeviceTier = detectedTier
        )
    }

    /**
     * Returns the actively selected rendering engine, accounting for user override in settings.
     */
    fun getActiveEngine(context: Context, settings: SettingsManager): RenderingEngineTier {
        val profile = profile(context)
        val overrideId = settings.renderingEngineOverride
        if (overrideId != null) {
            val userSelected = RenderingEngineTier.fromId(overrideId)
            // Verify minimum API level compatibility
            if (profile.apiLevel >= userSelected.minimumApi) {
                return userSelected
            }
        }
        return profile.recommendedEngine
    }
}
