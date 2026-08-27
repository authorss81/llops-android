package com.authorss81.noteflow.services

import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure-JVM tilt-shading policy: maps stylus tilt (0–90°) to width and alpha
 * multipliers. Zero tilt (stylus upright) = identity (1.0, 1.0); maximum tilt
 * (stylus nearly flat) = widest and most transparent.
 *
 * Phase 222 — all functions are pure and unit-testable on the JVM.
 */
object TiltShadingPolicy {

    /**
     * Width multiplier for a given tilt in degrees.
     *
     * - tilt 0° (upright): 1.0 (no change)
     * - tilt 90° (flat):  1.4 (40% wider)
     *
     * Formula: `1 + 0.4 * sin(tilt)`
     */
    fun widthFactor(tiltDeg: Float): Float {
        if (tiltDeg <= 0f) return 1f
        val t = tiltDeg.coerceIn(0f, 90f)
        return 1f + 0.4f * sin(Math.toRadians(t.toDouble())).toFloat()
    }

    /**
     * Alpha multiplier for a given tilt in degrees.
     *
     * - tilt 0° (upright): 1.0 (full opacity)
     * - tilt 90° (flat):  0.6 (40% more transparent)
     *
     * Formula: `0.6 + 0.4 * cos(tilt)`
     */
    fun alphaFactor(tiltDeg: Float): Float {
        if (tiltDeg <= 0f) return 1f
        val t = tiltDeg.coerceIn(0f, 90f)
        return 0.6f + 0.4f * cos(Math.toRadians(t.toDouble())).toFloat()
    }
}
