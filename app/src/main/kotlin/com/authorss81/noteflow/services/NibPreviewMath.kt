package com.authorss81.noteflow.services

import kotlin.math.sqrt

/**
 * Pure-JVM parameter math for the live nib/stroke previews (Phase 35).
 *
 * Drives [com.authorss81.noteflow.ui.components.PenNibVisualPreview] so that a
 * preview updates as the user drags the pressure / tilt / wetness sliders in
 * the width picker. Kept free of Android/Compose dependencies so every curve
 * below is unit-testable on the JVM.
 *
 * Component semantics:
 * - Pressure is a stylus force 0..1 (real events report ~0.05 at rest up to 1.0).
 * - Tilt is the stylus tilt angle in degrees 0..90 (0 = upright, looking at the
 *   paper edge-on).
 * - Wetness is the simulated "pigment load in the brush" 0..1 — how much the
 *   ink bleeds into the paper and feathers at the edges.
 */
object NibPreviewMath {

    /** Width scale from pressure. [minScale] soft-cap, [maxScale] press-down cap. */
    fun pressureToWidthScale(pressure: Float, minScale: Float = 0.35f, maxScale: Float = 1.15f): Float {
        val ms = minScale.coerceIn(0f, 1f)
        val xs = maxScale.coerceAtLeast(ms)
        val p = pressure.coerceIn(0f, 1f)
        // Resting pen still spreads a little ink (p ~0.05 -> ~0.35), a hard
        // press flattens the nib (p -> 1.0 -> maxScale).
        val resting = 1f - p
        val scale = ms + (xs - ms) * (1f - resting * resting)
        return scale.coerceIn(ms, xs)
    }

    /** Width actually rendered for a pressure + base width. */
    fun widthForPressure(baseWidth: Float, pressure: Float, minScale: Float = 0.35f, maxScale: Float = 1.15f): Float =
        (baseWidth.coerceAtLeast(0f) * pressureToWidthScale(pressure, minScale, maxScale)).coerceAtLeast(0f)

    /**
     * Opacity factor from tilt. A very tilted nib (90°) lays down a thinner,
     * more feathered kiss of ink, so opacity fades toward [minOpacity]; an
     * upright nib (0°) applies the full pigment.
     */
    fun tiltToOpacityFactor(tiltDeg: Float, minOpacity: Float = 0.30f): Float {
        val m = minOpacity.coerceIn(0f, 1f)
        val t = tiltDeg.coerceIn(0f, 90f)
        val drop = (t / 90f) * (1f - m)
        return (1f - drop).coerceIn(m, 1f)
    }

    /**
     * Simulated pigment wetness coefficients from a wetness slider 0..1.
     * [bleed] (edge feather fraction of the stroke width), [blurWeight]
     * (edge softness 0..1) and [keepAlpha] (how much pigment opacity survives
     * the wash). Dry cheats: bleed 0 → crisp edge. Soaking wet: up to ~95% of
     * the stroke width feathers out.
     */
    data class WetnessCoeffs(
        val bleed: Float,
        val blurWeight: Float,
        val keepAlpha: Float
    )

    fun wetnessToCoeffs(wetness: Float): WetnessCoeffs {
        val w = wetness.coerceIn(0f, 1f)
        return WetnessCoeffs(
            bleed = 0.35f * w,
            blurWeight = 0.75f * w,
            keepAlpha = (1f - 0.62f * w).coerceIn(0f, 1f)
        )
    }

    /** Stroke alpha to render a wet-wash preview at a given wetness. */
    fun strokeAlphaForWetness(wetness: Float): Float {
        val w = wetness.coerceIn(0f, 1f)
        return (0.92f - 0.55f * w).coerceIn(0.30f, 0.92f)
    }

    /** Feathering width (px style unit) for [wetness] around a base stroke width. */
    fun featherRadius(baseWidth: Float, wetness: Float): Float =
        baseWidth.coerceAtLeast(0f) * wetness.coerceIn(0f, 1f) * 0.35f

    /**
     * Combined preview parameters used by the live preview renderer so the UI
     * stays a thin adapter over tested math.
     */
    data class PreviewParams(
        val width: Float,
        val alpha: Float,
        val feather: Float,
        val tiltFade: Float
    )

    fun previewParamsFor(
        baseWidth: Float,
        pressure: Float,
        tiltDeg: Float,
        wetness: Float,
        maxScale: Float = 1.15f
    ): PreviewParams {
        val coeffs = wetnessToCoeffs(wetness)
        val width = widthForPressure(baseWidth, pressure, maxScale = maxScale)
        val feather = featherRadius(width, wetness)
        val alpha = (strokeAlphaForWetness(wetness) * tiltToOpacityFactor(tiltDeg)).coerceIn(0f, 1f)
        return PreviewParams(
            width = width,
            alpha = alpha,
            feather = feather,
            tiltFade = tiltToOpacityFactor(tiltDeg)
        )
    }

    /** Display helper: percentage text for the pressure/wetness sliders. */
    fun percent(v: Float): Int = (v.coerceIn(0f, 1f) * 100).toInt()

    /** Display helper: nearest point width for a pressure curve. */
    fun sqrtPress(pressure: Float): Float = sqrt(pressure.coerceIn(0f, 1f))
}