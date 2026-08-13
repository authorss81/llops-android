package com.authorss81.noteflow.services

/**
 * Kotlin reference implementations of the AGSL wet-mixing math in
 * [com.authorss81.noteflow.ui.components.AgslShaders.WET_MIXING_SHADER].
 *
 * The project rule is that tuned blend math must live in a tested Kotlin
 * reference first; the shader string mirrors it. If either side changes, the
 * other and the unit tests must be updated together.
 */
object WetMixingMath {

    /**
     * Source-over alpha accumulation (wet-on-wet watercolor).
     * Overlapping washes must monotonically darken: a second wash over an
     * existing one can never lower the resulting alpha below the larger input.
     * Mirrors the shader's `base.a + brushA * (1.0 - base.a)`.
     */
    fun sourceOverAlpha(baseA: Float, brushA: Float): Float {
        val a = baseA.coerceIn(0f, 1f) + brushA.coerceIn(0f, 1f) * (1f - baseA.coerceIn(0f, 1f))
        return a.coerceIn(0f, 1f)
    }

    /**
     * Pigment-space (subtractive/complementary-product) mixing for a single
     * channel. Absorbances multiply: result = 1-(1-base)(1-brush). Interpolates
     * with [factor] (0.0 -> keep base, 1.0 -> fully deposit brush).
     * Mirrors the shader's pigment mixing branch.
     */
    fun pigmentMix(base: Float, brush: Float, factor: Float): Float {
        val f = factor.coerceIn(0f, 1f)
        val b = base.coerceIn(0f, 1f)
        val c = brush.coerceIn(0f, 1f)
        val combined = 1f - (1f - b) * (1f - c)
        return (b + (combined - b) * f).coerceIn(0f, 1f)
    }

    /**
     * Pigment-space mixing for the three RGB channels of a color deposit.
     */
    fun pigmentMixRgb(
        baseR: Float, baseG: Float, baseB: Float,
        brushR: Float, brushG: Float, brushB: Float,
        factor: Float
    ): FloatArray = floatArrayOf(
        pigmentMix(baseR, brushR, factor),
        pigmentMix(baseG, brushG, factor),
        pigmentMix(baseB, brushB, factor)
    )
}