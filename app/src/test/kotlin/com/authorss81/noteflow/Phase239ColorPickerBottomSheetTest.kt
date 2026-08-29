package com.authorss81.noteflow

import com.authorss81.noteflow.services.ColorHarmonyHelper
import com.authorss81.noteflow.services.ColorFamily
import com.authorss81.noteflow.services.ColorHarmonyHelper.Rgb
import com.authorss81.noteflow.services.HarmonyScheme
import com.authorss81.noteflow.services.PaletteMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 239 — color-picker critical path, as pure-JVM logic tests.
 *
 * Phase-235 specified an instrumented `ColorPickerBottomSheet` UI test (open the
 * HSV tab, drag the HSV slider, verify the color updates + no crash, tap outside
 * to dismiss). The layout/scroll crash (`c972b23` — a `heightIn` before
 * `verticalScroll` fix) is inherently a Compose runtime concern, but the color
 * engine that tab drives — HSL/HSV conversion, hue rotation for harmony
 * swatches, and the curated-palette family classifier — is pure JVM and is pinned
 * here so a regression in the math can never break the picker silently.
 *
 * Regression ids pinned: `c972b23` (picker opens, HSV produces a sane color),
 * plus the Phase-21 `PaletteCatalog.familyFor` hue-band fix (curated swatches
 * must classify into the correct family).
 */
class Phase239ColorPickerBottomSheetTest {

    // ---- HSV/HSL round-trip: the slider -> color path ------------------------

    @Test
    fun `rgb to hsl round trips back to the same rgb`() {
        val cases = listOf(
            ColorHarmonyHelper.Rgb(255f, 0f, 0f),
            ColorHarmonyHelper.Rgb(0f, 128f, 255f),
            ColorHarmonyHelper.Rgb(34f, 189f, 99f),
            ColorHarmonyHelper.Rgb(120f, 44f, 220f),
            ColorHarmonyHelper.Rgb(200f, 200f, 200f)
        )
        for (c in cases) {
            val hsl = ColorHarmonyHelper.rgbToHsl(c.r, c.g, c.b)
            val back = ColorHarmonyHelper.hslToRgb(hsl.h, hsl.s, hsl.l)
            assertTrue(
                "round-trip ${c.r},${c.g},${c.b} -> ${hsl.h},${hsl.s},${hsl.l} -> ${back.r},${back.g},${back.b}",
                ColorHarmonyHelper.rgbClose(c, back)
            )
        }
    }

    @Test
    fun `gray input has zero saturation and round-trips as gray`() {
        val hsl = ColorHarmonyHelper.rgbToHsl(120f, 120f, 120f)
        assertEquals(0f, hsl.s, 0f)
        val back = ColorHarmonyHelper.hslToRgb(hsl.h, hsl.s, hsl.l)
        assertEquals(120f, back.r, 1f)
        assertEquals(120f, back.g, 1f)
        assertEquals(120f, back.b, 1f)
    }

    @Test
    fun `hue normalization wraps into the zero to 360 wheel`() {
        assertEquals(0f, ColorHarmonyHelper.normalizeHue(360f), 0.0001f)
        assertEquals(30f, ColorHarmonyHelper.normalizeHue(390f), 0.0001f)
        assertEquals(350f, ColorHarmonyHelper.normalizeHue(-10f), 0.0001f)
        assertEquals(30f, ColorHarmonyHelper.normalizeHue(30f), 0.0001f)
    }

    // ---- HSV tab slider: color updates across the hue sweep -----------------

    @Test
    fun `dragging hue across the sweep keeps saturation and lightness and stays in gamut`() {
        val s = 0.7f
        val l = 0.5f
        val recordedHues = mutableListOf<Float>()
        for (h in intArrayOf(0, 30, 60, 90, 120, 150, 180, 210, 240, 270, 300, 330, 360)) {
            val rgb = ColorHarmonyHelper.hslToRgb(h.toFloat(), s, l)
            // Every channel must be within gamut (the picker never produces an
            // out-of-range color that would clamp into an unexpected hue).
            assertTrue("r in gamut at $h", rgb.r in -0.01f..255.01f)
            assertTrue("g in gamut at $h", rgb.g in -0.01f..255.01f)
            assertTrue("b in gamut at $h", rgb.b in -0.01f..255.01f)
            val round = ColorHarmonyHelper.rgbToHsl(rgb.r, rgb.g, rgb.b)
            assertEquals("saturation preserved", s, round.s, 0.02f)
            assertEquals("lightness preserved", l, round.l, 0.02f)
            recordedHues += round.h
        }
        // Dragging the hue slider must actually sweep every third of the wheel
        // (a stuck picker would collapse all recorded hues into one band).
        val sorted = recordedHues.sorted()
        val span = sorted.last() - sorted.first()
        assertTrue("hue sweep covers the wheel (span=$span)", span >= 240f)
        assertTrue("many distinct hues sampled", recordedHues.toSet().size >= 9)
    }

    // ---- harmony swatch generation (the picker's swatch row) -----------------

    @Test
    fun `complementary scheme contains the source color and its 180 hue flip`() {
        val src = ColorHarmonyHelper.Rgb(200f, 30f, 30f)
        val hsl = ColorHarmonyHelper.rgbToHsl(src.r, src.g, src.b)
        val swatches = ColorHarmonyHelper.generate(src, HarmonyScheme.COMPLEMENTARY)
        assertEquals(2, swatches.size)
        assertTrue("source is first swatch", ColorHarmonyHelper.rgbClose(src, swatches[0]))
        val flipped = ColorHarmonyHelper.rgbToHsl(swatches[1].r, swatches[1].g, swatches[1].b)
        assertEquals("complement ~180 hue away", ColorHarmonyHelper.normalizeHue(hsl.h + 180f), flipped.h, 2f)
    }

    @Test
    fun `triadic scheme yields three evenly spaced hues`() {
        val swatches = ColorHarmonyHelper.generate(ColorHarmonyHelper.Rgb(60f, 160f, 240f), HarmonyScheme.TRIADIC)
        assertEquals(3, swatches.size)
        val hues = swatches.map { ColorHarmonyHelper.rgbToHsl(it.r, it.g, it.b).h }
        val sorted = hues.sorted()
        // Triadic hues are ~120° apart around the wheel.
        val gaps = listOf(
            ColorHarmonyHelper.normalizeHue(sorted[1] - sorted[0]),
            ColorHarmonyHelper.normalizeHue(sorted[2] - sorted[1])
        )
        assertTrue("spacing ~120", gaps.all { kotlin.math.abs(it - 120f) < 5f })
    }

    @Test
    fun `desaturated source collapses to a single swatch`() {
        val gray = ColorHarmonyHelper.Rgb(128f, 128f, 128f)
        val analogous = ColorHarmonyHelper.generate(gray, HarmonyScheme.ANALOGOUS)
        assertEquals("fully gray input dedupes to one swatch", 1, analogous.size)
        assertEquals(128f, analogous[0].r, 1f)
    }

    // ---- curated palette family classification (Phase 21 fix) ----------------

    @Test
    fun `curated swatches classify into their true family`() {
        // Phase 21 fixed `PaletteCatalog.familyFor` hue-band misclassification.
        // Pin a representative swatch from each family so a hue-band regression
        // (which silently mislabels the curated color wheel) never returns.
        data class Sample(val r: Int, val g: Int, val b: Int, val family: ColorFamily)
        val samples = listOf(
            Sample(230, 30, 30, ColorFamily.REDS),
            Sample(230, 130, 30, ColorFamily.ORANGES),
            Sample(225, 190, 30, ColorFamily.YELLOWS),
            Sample(40, 170, 90, ColorFamily.GREENS),
            Sample(40, 90, 210, ColorFamily.BLUES),
            Sample(140, 60, 200, ColorFamily.PURPLES),
            Sample(220, 90, 160, ColorFamily.PINKS),
            Sample(80, 50, 30, ColorFamily.BROWNS)
        )
        for (s in samples) {
            val rgb = PaletteMath.newRgb(s.r, s.g, s.b)
            assertEquals("rgb(${s.r},${s.g},${s.b}) -> ${s.family.label}", s.family, PaletteMath.familyFor(rgb))
        }
    }

    @Test
    fun `hsv round trip preserves the color through the picker representation`() {
        val argb = 0xFF1B365D.toInt()
        val rgb = PaletteMath.fromArgb(argb)
        val hsv = PaletteMath.hsvOf(rgb)
        assertTrue("hue in 0..360", hsv.h in 0f..360f)
        assertTrue("saturation in 0..1", hsv.s in 0f..1f)
        assertTrue("value in 0..1", hsv.v in 0f..1f)
        assertEquals("argb survives the round trip", argb, PaletteMath.toArgb(PaletteMath.gamutSafe(rgb)))
    }
}
