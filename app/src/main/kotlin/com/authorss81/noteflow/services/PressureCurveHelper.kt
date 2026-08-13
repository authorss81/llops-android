package com.authorss81.noteflow.services

import kotlin.math.sqrt

/**
 * Pressure-response curve presets.
 *
 * A pressure->width/opacity remap lets stylus users tune pen feel. Each curve is
 * a monotonic function from raw stylus pressure (0..1) onto a remapped pressure
 * (0..1); the remapped value is what flows into the pressure-sensitive brush
 * path, and the [widthFactor]/[opacityFactor] helpers derive the width/opacity
 * for classic brush rendering.
 *
 * Pure math, no Android dependencies, unit-testable on the JVM.
 */
enum class PressureCurve(val label: String, val settingKey: String, val description: String) {
    LINEAR("Linear", "linear", "1:1 pen force"),
    LIGHT("Light", "light", "Thicker at low pressure"),
    HEAVY("Heavy", "heavy", "Needs firmer press");

    companion object {
        fun fromSettingKey(key: String?): PressureCurve =
            entries.firstOrNull { it.settingKey == key } ?: LINEAR
    }
}

object PressureCurveHelper {

    /** Remaps raw stylus pressure (0..1) through the curve onto (0..1). */
    fun remapPressure(rawPressure: Float, curve: PressureCurve): Float {
        val p = rawPressure.coerceIn(0f, 1f)
        return when (curve) {
            PressureCurve.LINEAR -> p
            PressureCurve.LIGHT -> sqrt(p)
            PressureCurve.HEAVY -> p * p
        }
    }

    /** Width scale (0.5..1.0) for the remapped pressure. */
    fun widthFactor(rawPressure: Float, curve: PressureCurve): Float =
        0.5f + 0.5f * remapPressure(rawPressure, curve)

    /** Opacity scale (0.6..1.0) for the remapped pressure. */
    fun opacityFactor(rawPressure: Float, curve: PressureCurve): Float =
        0.6f + 0.4f * remapPressure(rawPressure, curve)
}