package com.authorss81.noteflow.services

import kotlin.math.pow
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
 * IMPORTANT (capture-time semantics): the remap is applied ONCE, while the
 * stroke is being captured, and the remapped value is what is persisted with
 * the stroke's points (see AnnotationCanvas). Selecting a curve is a pen-feel
 * setting: it affects strokes drawn AFTER the change, exactly like adjusting
 * pen pressure sensitivity on a physical stylus. Existing strokes keep the
 * feel they were drawn with (they are never re-remapped at render time), so
 * toggling a curve does not mutate previously saved artwork.
 *
 * Pure math, no Android dependencies, unit-testable on the JVM.
 */
enum class PressureCurve(val label: String, val settingKey: String, val description: String) {
    LINEAR("Linear", "linear", "1:1 pen force"),
    LIGHT("Light", "light", "Thicker at low pressure"),
    HEAVY("Heavy", "heavy", "Needs firmer press"),
    SMOOTH("Smooth", "smooth", "Steadier width at light press");

    companion object {
        fun fromSettingKey(key: String?): PressureCurve =
            entries.firstOrNull { it.settingKey == key } ?: LINEAR
    }
}

object PressureCurveHelper {

    /**
     * Phase 201 (PERF 1.3): gamma-blend exponents for [PressureCurve.SMOOTH].
     * The exponent eases from [SMOOTH_GAMMA_AT_ZERO] at zero pressure down to
     * [SMOOTH_GAMMA_AT_ONE] at full pressure: `remap(p) = p ^ (2.0 - 0.5p)`.
     */
    const val SMOOTH_GAMMA_AT_ZERO = 2.0f
    const val SMOOTH_GAMMA_AT_ONE = 1.5f

    /** Raw stylus pressure below which digitizer jitter dominates the signal. */
    const val LOW_END_BAND = 0.10f

    /**
     * Remaps raw stylus pressure (0..1) through the curve onto (0..1).
     *
     * Phase 201 (PERF 1.3): [PressureCurve.SMOOTH] is a GAMMA curve whose
     * exponent blends 2.0 -> 1.5 across the range (`p ^ (2.0 - 0.5p)`). Because
     * d(p^g)/dp = g·p^(g-1) -> 0 as p -> 0, tiny digitizer fluctuations inside
     * the low-end 0-10% band produce even tinier width changes instead of the
     * visible low-pressure width jitter a linear map amplifies — while the
     * lighter 1.5 tail keeps mid/high pressures responsive (between LINEAR and
     * HEAVY everywhere in (0,1)).
     */
    fun remapPressure(rawPressure: Float, curve: PressureCurve): Float {
        val p = rawPressure.coerceIn(0f, 1f)
        return when (curve) {
            PressureCurve.LINEAR -> p
            PressureCurve.LIGHT -> sqrt(p)
            PressureCurve.HEAVY -> p * p
            PressureCurve.SMOOTH -> {
                if (p <= 0f) 0f
                else {
                    val gamma = SMOOTH_GAMMA_AT_ZERO -
                        (SMOOTH_GAMMA_AT_ZERO - SMOOTH_GAMMA_AT_ONE) * p
                    p.pow(gamma)
                }
            }
        }
    }

    /** Width scale (0.5..1.0) for the remapped pressure. */
    fun widthFactor(rawPressure: Float, curve: PressureCurve): Float =
        0.5f + 0.5f * remapPressure(rawPressure, curve)

    /** Opacity scale (0.6..1.0) for the remapped pressure. */
    fun opacityFactor(rawPressure: Float, curve: PressureCurve): Float =
        0.6f + 0.4f * remapPressure(rawPressure, curve)
}
