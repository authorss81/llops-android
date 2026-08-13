package com.authorss81.noteflow

import com.authorss81.noteflow.services.ColorHarmonyHelper
import com.authorss81.noteflow.services.HarmonyScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ColorHarmonyHelperTest {

    @Test
    fun `rgb to hsl to rgb round-trips`() {
        val rgb = ColorHarmonyHelper.Rgb(220f, 30f, 60f)
        val hsl = ColorHarmonyHelper.rgbToHsl(rgb.r, rgb.g, rgb.b)
        val back = ColorHarmonyHelper.hslToRgb(hsl.h, hsl.s, hsl.l)
        assertTrue("round-trip failed: $rgb -> $back", ColorHarmonyHelper.rgbClose(rgb, back, 2f))
    }

    @Test
    fun `complementary of red is cyan-ish`() {
        val red = ColorHarmonyHelper.Rgb(240f, 10f, 10f)
        val complements = ColorHarmonyHelper.generate(red, HarmonyScheme.COMPLEMENTARY)
        // [0] is the source (red), [1] is the complement.
        assertEquals(2, complements.size)
        val comp = complements[1]
        // Cyan-ish means green & blue dominate while red is low.
        assertTrue("complement should be cyan-ish: $comp", comp.g > comp.r && comp.b > comp.r)
        assertTrue("complement should not be reddish", comp.r < 120f)

        val sourceHue = ColorHarmonyHelper.rgbToHsl(complements[0].r, complements[0].g, complements[0].b).h
        val compHue = ColorHarmonyHelper.rgbToHsl(comp.r, comp.g, comp.b).h
        hueDiffClose(sourceHue, compHue, 180f)
    }

    @Test
    fun `triadic hues are 120 degrees apart`() {
        val blue = ColorHarmonyHelper.Rgb(30f, 40f, 240f)
        val triadic = ColorHarmonyHelper.generate(blue, HarmonyScheme.TRIADIC)
        assertEquals(3, triadic.size)
        val hues = triadic.map { ColorHarmonyHelper.rgbToHsl(it.r, it.g, it.b).h }
        hueDiffClose(hues[0], hues[1], 120f)
        hueDiffClose(hues[1], hues[2], 120f)
        hueDiffClose(hues[2], hues[0], 120f)
    }

    @Test
    fun `tetradic hues step by 90 degrees`() {
        val green = ColorHarmonyHelper.Rgb(20f, 200f, 80f)
        val tetradic = ColorHarmonyHelper.generate(green, HarmonyScheme.TETRADIC)
        assertEquals(4, tetradic.size)
        val hues = tetradic.map { ColorHarmonyHelper.rgbToHsl(it.r, it.g, it.b).h }
        hueDiffClose(hues[0], hues[1], 90f)
        hueDiffClose(hues[1], hues[2], 90f)
        hueDiffClose(hues[2], hues[3], 90f)
    }

    @Test
    fun `analogous hues sit at minus and plus 30 degrees`() {
        val orange = ColorHarmonyHelper.Rgb(230f, 120f, 20f)
        val analogous = ColorHarmonyHelper.generate(orange, HarmonyScheme.ANALOGOUS)
        assertEquals(3, analogous.size)
        val hues = analogous.map { ColorHarmonyHelper.rgbToHsl(it.r, it.g, it.b).h }
        hueDiffClose(hues[0], hues[1], 30f) // -30 vs source
        hueDiffClose(hues[1], hues[2], 30f) // source vs +30
        hueDiffClose(hues[0], hues[2], 60f) // -30 vs +30
    }

    @Test
    fun `neutral colors produce symmetric harmonious grays`() {
        val gray = ColorHarmonyHelper.Rgb(128f, 128f, 128f)
        listOf(
            HarmonyScheme.ANALOGOUS,
            HarmonyScheme.COMPLEMENTARY,
            HarmonyScheme.TRIADIC,
            HarmonyScheme.TETRADIC
        ).forEach { scheme ->
            val swatches = ColorHarmonyHelper.generate(gray, scheme)
            swatches.forEach { s ->
                // All swatches of a gray should stay gray (saturated hue is forced 0).
                assertTrue(
                    "grayscale input must yield grayscale swatches for $scheme: $s",
                    abs(s.r - s.g) < 0.6f && abs(s.g - s.b) < 0.6f
                )
            }
        }
    }

    private fun hueDiffClose(a: Float, b: Float, expected: Float, tolerance: Float = 2f) {
        val diff = abs(a - b)
        val wrapped = minOf(diff, 360f - diff) // hues wrap around
        assertEquals("hue diff should be $expected (a=$a b=$b)", expected, wrapped, tolerance)
    }
}
