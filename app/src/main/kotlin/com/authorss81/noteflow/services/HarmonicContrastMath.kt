package com.authorss81.noteflow.services

import kotlin.math.abs
import kotlin.math.pow

/**
 * Harmonic contrast checking (Phase 35): mathematically valid complementary /
 * analogous suggestions for a chosen swatch together with their WCAG contrast
 * ratio against a background (normally the canvas's paper color).
 *
 * Pure JVM — reuses [ColorHarmonyHelper]'s HSL math. All colors are 0..255
 * float RGB. Contrast ratio follows WCAG relative luminance:
 *
 *     L = 0.2126 R' + 0.7152 G' + 0.0722 B'   (gamma-expanded channels)
 *     ratio = (L1 + 0.05) / (L2 + 0.05)
 */
object HarmonicContrastMath {

    private fun channel(c: Float): Float {
        val cc = (c / 255f).coerceIn(0f, 1f)
        return if (cc <= 0.04045f) cc / 12.92f else ((cc + 0.055f) / 1.055f).pow(2.4f)
    }

    fun relativeLuminance(rgb: ColorHarmonyHelper.Rgb): Float =
        0.2126f * channel(rgb.r) + 0.7152f * channel(rgb.g) + 0.0722f * channel(rgb.b)

    /** WCAG contrast ratio (1.0..21.0). Foreground/background order is irrelevant. */
    fun contrastRatio(fg: ColorHarmonyHelper.Rgb, bg: ColorHarmonyHelper.Rgb): Float {
        val l1 = relativeLuminance(fg)
        val l2 = relativeLuminance(bg)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    /** Hue-opposite of [source] at the standard 180° rotation. */
    fun complementary(source: ColorHarmonyHelper.Rgb): ColorHarmonyHelper.Rgb {
        val hsl = ColorHarmonyHelper.rgbToHsl(source.r, source.g, source.b)
        return ColorHarmonyHelper.hslToRgb(ColorHarmonyHelper.normalizeHue(hsl.h + 180f), hsl.s, hsl.l)
    }

    /** The two 30°-analogous neighbours (excluding the source itself). */
    fun analogousPicks(source: ColorHarmonyHelper.Rgb): List<ColorHarmonyHelper.Rgb> {
        val hsl = ColorHarmonyHelper.rgbToHsl(source.r, source.g, source.b)
        return listOf(
            ColorHarmonyHelper.hslToRgb(ColorHarmonyHelper.normalizeHue(hsl.h - 30f), hsl.s, hsl.l),
            ColorHarmonyHelper.hslToRgb(ColorHarmonyHelper.normalizeHue(hsl.h + 30f), hsl.s, hsl.l)
        )
    }

    data class Suggestion(val color: ColorHarmonyHelper.Rgb, val label: String, val ratio: Float)

    /**
     * Curated suggestion set for [source] against [background]:
     * complementary, the two analogues, and a lightened + darkened pad aimed at
     * readability (each reported with its actual WCAG ratio).
     */
    fun suggestions(source: ColorHarmonyHelper.Rgb, background: ColorHarmonyHelper.Rgb): List<Suggestion> {
        val out = mutableListOf<Suggestion>()
        val comp = complementary(source)
        out += Suggestion(comp, "Complement", contrastRatio(comp, background))
        for (a in analogousPicks(source)) {
            val label = pickLabel(source, a)
            out += Suggestion(a, label, contrastRatio(a, background))
        }
        out += Suggestion(
            padFor(source, ColorHarmonyHelper.Rgb(255f, 255f, 255f)),
            "Lighter",
            contrastRatio(padFor(source, ColorHarmonyHelper.Rgb(255f, 255f, 255f)), background)
        )
        val dark = padFor(source, ColorHarmonyHelper.Rgb(0f, 0f, 0f))
        out += Suggestion(dark, "Darker", contrastRatio(dark, background))
        return out
    }

    private fun pickLabel(source: ColorHarmonyHelper.Rgb, candidate: ColorHarmonyHelper.Rgb): String {
        val srcH = ColorHarmonyHelper.rgbToHsl(source.r, source.g, source.b).h
        val cndH = ColorHarmonyHelper.rgbToHsl(candidate.r, candidate.g, candidate.b).h
        val ccw = (srcH - cndH + 360f) % 360f
        return if (ccw <= 180f) "Analogous −30°" else "Analogous +30°"
    }

    /**
     * Adjusts [source]'s lightness toward black or white (whichever crank
     * improves contrast) until the WCAG ratio against [background] reaches
     * [minRatio]. Returns [source] unchanged if it already clears the bar, or
     * the best achievable shade otherwise. Never overshoots the gamut.
     */
    fun padFor(
        source: ColorHarmonyHelper.Rgb,
        background: ColorHarmonyHelper.Rgb,
        minRatio: Float = 4.5f
    ): ColorHarmonyHelper.Rgb {
        val hsl = ColorHarmonyHelper.rgbToHsl(source.r, source.g, source.b)
        val start = contrastRatio(source, background)
        if (start >= minRatio) return source

        val towardLight = contrastRatio(
            ColorHarmonyHelper.hslToRgb(hsl.h, hsl.s, 0.98f), background
        ) > contrastRatio(ColorHarmonyHelper.hslToRgb(hsl.h, hsl.s, 0.02f), background)

        var best = source
        var bestRatio = start
        // Walk lightness in 32 steps toward the more contrasting end.
        val steps = 32
        for (i in 1..steps) {
            val t = if (towardLight) i.toFloat() / steps else (steps - i).toFloat() / steps
            val candidate = ColorHarmonyHelper.hslToRgb(hsl.h, hsl.s, t)
            val ratio = contrastRatio(candidate, background)
            if (ratio > bestRatio) {
                bestRatio = ratio
                best = candidate
            }
            if (ratio >= minRatio) return candidate
        }
        return best
    }

    /** Gamut sanity: all channels within 0..255. */
    fun inGamut(rgb: ColorHarmonyHelper.Rgb): Boolean =
        rgb.r in 0f..255f && rgb.g in 0f..255f && rgb.b in 0f..255f

    /** Hue-distance helper used by the UI to label which "side" an analogous pick sits on. */
    fun hueDeltaDeg(a: Float, b: Float): Float {
        val d = abs((a - b + 360f) % 360f)
        return if (d > 180f) 360f - d else d
    }
}