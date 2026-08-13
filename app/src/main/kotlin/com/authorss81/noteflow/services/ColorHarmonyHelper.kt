package com.authorss81.noteflow.services

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Color-harmony generation (deterministic HSL rotation math).
 *
 * Converts a color to HSL, rotates the hue by fixed angles for each scheme, and
 * converts back to RGB. Pure Kotlin so it is unit-testable on the JVM without
 * any Android APIs. RGB components are 0..255 floats; HSL hue is degrees 0..360.
 */
enum class HarmonyScheme(val label: String) {
    ANALOGOUS("Analogous"),
    COMPLEMENTARY("Complementary"),
    TRIADIC("Triadic"),
    TETRADIC("Tetradic");
}

object ColorHarmonyHelper {

    data class Hsl(val h: Float, val s: Float, val l: Float)
    data class Rgb(val r: Float, val g: Float, val b: Float)

    private const val HUE_STEP_ANALOGOUS = 30f
    private const val HUE_STEP_COMPLEMENTARY = 180f
    private const val HUE_STEP_TRIADIC = 120f
    private const val HUE_STEP_TETRADIC = 90f

    /** Normalizes a hue into 0..360. */
    fun normalizeHue(hue: Float): Float {
        var h = hue % 360f
        if (h < 0f) h += 360f
        return h
    }

    fun rgbToHsl(r: Float, g: Float, b: Float): Hsl {
        val rr = (r / 255f).coerceIn(0f, 1f)
        val gg = (g / 255f).coerceIn(0f, 1f)
        val bb = (b / 255f).coerceIn(0f, 1f)
        val maxC = max(rr, max(gg, bb))
        val minC = min(rr, min(gg, bb))
        val l = (maxC + minC) / 2f
        if (maxC == minC) return Hsl(0f, 0f, l)
        val d = maxC - minC
        val s = if (l > 0.5f) d / (2f - maxC - minC) else d / (maxC + minC)
        val h = when (maxC) {
            rr -> (gg - bb) / d + if (gg < bb) 6f else 0f
            gg -> (bb - rr) / d + 2f
            else -> (rr - gg) / d + 4f
        }
        return Hsl(normalizeHue(h * 60f), s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
    }

    fun hslToRgb(h: Float, s: Float, l: Float): Rgb {
        val hue = normalizeHue(h)
        val ss = s.coerceIn(0f, 1f)
        val ll = l.coerceIn(0f, 1f)
        if (ss == 0f) {
            val v = (ll * 255f).coerceIn(0f, 255f)
            return Rgb(v, v, v)
        }
        val q = if (ll < 0.5f) ll * (1f + ss) else ll + ss - ll * ss
        val p = 2f * ll - q
        fun hueToRgb(t0: Float): Float {
            var t = t0
            if (t < 0f) t += 1f
            if (t > 1f) t -= 1f
            return when {
                t < 1f / 6f -> p + (q - p) * 6f * t
                t < 1f / 2f -> q
                t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
                else -> p
            }
        }
        return Rgb(
            hueToRgb(hue / 360f + 1f / 3f) * 255f,
            hueToRgb(hue / 360f) * 255f,
            hueToRgb(hue / 360f - 1f / 3f) * 255f
        )
    }

    /**
     * Generates harmony swatches from an RGB color. [scheme] selects the hue
     * rotation set; the *scheme's* hues are returned in scheme order (0° step
     * first for COMPLEMENTARY/TRIADIC/TETRADIC; for ANALOGOUS the 0° step is the
     * middle swatch: -30°, source, +30°). The exact input color is always present
     * wherever the scheme contains a 0° rotation. Results are deduplicated, so a
     * fully desaturated (gray) input collapses to a single swatch per scheme.
     */
    fun generate(rgb: Rgb, scheme: HarmonyScheme): List<Rgb> {
        val hsl = rgbToHsl(rgb.r, rgb.g, rgb.b)
        val steps = when (scheme) {
            HarmonyScheme.ANALOGOUS -> listOf(-HUE_STEP_ANALOGOUS, 0f, HUE_STEP_ANALOGOUS)
            HarmonyScheme.COMPLEMENTARY -> listOf(0f, HUE_STEP_COMPLEMENTARY)
            HarmonyScheme.TRIADIC -> listOf(0f, HUE_STEP_TRIADIC, 2f * HUE_STEP_TRIADIC)
            HarmonyScheme.TETRADIC -> listOf(0f, HUE_STEP_TETRADIC, 2f * HUE_STEP_TETRADIC, 3f * HUE_STEP_TETRADIC)
        }
        return steps
            .map { hslToRgb(normalizeHue(hsl.h + it), hsl.s, hsl.l) }
            .distinctBy { (r, g, b) ->
                listOf(r.toInt(), g.toInt(), b.toInt())
            }
    }

    /**
     * Round-trips: RGB -> HSL -> RGB must reproduce the original channels within
     * a small epsilon (the standard 60°-sector conversion is not perfectly
     * lossless in float, hence the tolerance).
     */
    fun rgbClose(a: Rgb, b: Rgb, epsilon: Float = 1.5f): Boolean =
        abs(a.r - b.r) <= epsilon && abs(a.g - b.g) <= epsilon && abs(a.b - b.b) <= epsilon
}
