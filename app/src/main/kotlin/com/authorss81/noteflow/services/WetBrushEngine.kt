package com.authorss81.noteflow.services

import android.os.Build
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

enum class DeviceTier {
    LOW, MID, HIGH
}

class WetBrushEngine {
    private var frameTimeEmaMs: Float = 16.6f
    private val alphaEma = 2f / (20f + 1f) // 20-frame EMA alpha

    var currentQuality: Float = 1.0f
        private set

    var useVectorFallback: Boolean = false
        private set

    private var lowGpuSustainedTimeMs: Long = 0L

    /**
     * Exponential Moving Average calculation for 20 frames of Choreographer timings
     */
    fun recordFrameTime(elapsedMs: Float) {
        frameTimeEmaMs = (alphaEma * elapsedMs) + ((1f - alphaEma) * frameTimeEmaMs)
    }

    fun getFrameTimeEmaMs(): Float = frameTimeEmaMs

    /**
     * Tier and fallback decision based on Thermal status and frame render times
     */
    fun updateTierAndFallback(
        isAgslSupported: Boolean,
        thermalStatus: Int, // e.g. PowerManager.THERMAL_STATUS_SEVERE
        manualOverrideEnabled: Boolean,
        currentTimeMs: Long
    ) {
        if (!isAgslSupported || !manualOverrideEnabled) {
            useVectorFallback = true
            currentQuality = 0.0f
            return
        }

        // Severe or critical thermal throttling (API 29+ thermal levels)
        val isThermalSevere = thermalStatus >= 3 // Severe (3) or Critical (4)
        
        if (isThermalSevere) {
            // Degrade quality and reduce frequency
            currentQuality = 0.35f
            useVectorFallback = false
            return
        }

        // EMA checks for GPU degradation: if Choreographer EMA > 20ms sustained for 1s
        if (frameTimeEmaMs > 20.0f) {
            if (lowGpuSustainedTimeMs == 0L) {
                lowGpuSustainedTimeMs = currentTimeMs
            } else if (currentTimeMs - lowGpuSustainedTimeMs >= 1000L) {
                // Auto-degrade steps: 1.0 -> 0.5 -> 0.35 -> Fallback to Vector
                if (currentQuality > 0.51f) {
                    currentQuality = 0.5f
                } else if (currentQuality > 0.36f) {
                    currentQuality = 0.35f
                } else {
                    useVectorFallback = true
                }
                lowGpuSustainedTimeMs = currentTimeMs // reset timer for next level
            }
        } else {
            lowGpuSustainedTimeMs = 0L
            useVectorFallback = false
            currentQuality = 1.0f
        }
    }

    /**
     * Throttling rule: ≥6px moved or ≥16ms elapsed since last processed point
     */
    fun shouldProcessPoint(
        lastPoint: Offset?,
        newPoint: Offset,
        lastTimeMs: Long,
        currentTimeMs: Long
    ): Boolean {
        if (lastPoint == null) return true
        val dx = newPoint.x - lastPoint.x
        val dy = newPoint.y - lastPoint.y
        val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        
        val timeElapsed = currentTimeMs - lastTimeMs
        return dist >= 6.0f || timeElapsed >= 16L
    }

    /**
     * Segment interpolation for continuous stroke ribbon (not dotted gaps).
     * Walk from prev to cur in steps of radius, capped at 3 sub-steps.
     */
    fun interpolateSegment(
        prev: Offset,
        cur: Offset,
        radius: Float
    ): List<Offset> {
        val dx = cur.x - prev.x
        val dy = cur.y - prev.y
        val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        if (dist <= 0f) return listOf(cur)

        val stepSize = radius.coerceAtLeast(1.0f)
        val numSteps = ceil(dist / stepSize).toInt().coerceIn(1, 3)
        
        val list = mutableListOf<Offset>()
        for (i in 1..numSteps) {
            val ratio = i.toFloat() / numSteps
            list.add(Offset(prev.x + dx * ratio, prev.y + dy * ratio))
        }
        return list
    }

    /**
     * Calculate bounding box/dirty rect with radius + padding
     */
    fun calculateDirtyRect(
        point: Offset,
        radius: Float,
        padding: Float = 8f
    ): android.graphics.RectF {
        val r = radius + padding
        return android.graphics.RectF(
            point.x - r,
            point.y - r,
            point.x + r,
            point.y + r
        )
    }
}
