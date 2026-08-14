package com.authorss81.noteflow

import com.authorss81.noteflow.services.ColorVibrancy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the Phase 19 render-time vibrancy (saturation lift).
 * The boost must preserve hue, stay in gamut, and be the identity at amount 0.
 */
class ColorVibrancyTest {

    private fun hueOf(r: Float, g: Float, b: Float): Float =
        ColorVibrancy.rgbToHsv(r, g, b).h

    // ---- identity ----------------------------------------------------------

    @Test
    fun `amount zero is the identity transform`() {
        val argb = 0xFF3B82F6.toInt()
        assertEquals(argb, ColorVibrancy.boostColorInt(argb, 0f))
        val rgb = ColorVibrancy.boostRgb(30f, 120f, 240f, 0f)
        assertEquals(30f, rgb.r, 1e-6f)
        assertEquals(120f, rgb.g, 1e-6f)
        assertEquals(240f, rgb.b, 1e-6f)
    }

    @Test
    fun `negative amount behaves like off`() {
        val argb = 0xFFEF4444.toInt()
        assertEquals(argb, ColorVibrancy.boostColorInt(argb, -0.5f))
    }

    // ---- saturation semantics ---------------------------------------------

    @Test
    fun `saturation rises toward one with amount`() {
        assertEquals(0.5f, ColorVibrancy.saturationFor(0.5f, 0f), 1e-6f)
        assertTrue(ColorVibrancy.saturationFor(0.5f, 1f) > 0.99f)
        assertTrue(ColorVibrancy.saturationFor(0.2f, 0.4f) > 0.2f)
    }

    @Test
    fun `higher amount never lowers saturation`() {
        val low = ColorVibrancy.saturationFor(0.3f, 0.2f)
        val high = ColorVibrancy.saturationFor(0.3f, 0.9f)
        assertTrue(high >= low)
    }

    @Test
    fun `grey stays grey regardless of boost`() {
        val g = ColorVibrancy.boostRgb(128f, 128f, 128f, 1f)
        assertEquals(128f, g.r, 1f)
        assertEquals(128f, g.g, 1f)
        assertEquals(128f, g.b, 1f)
    }

    // ---- hue / gamut fidelity ---------------------------------------------

    @Test
    fun `hue is preserved by the boost`() {
        val before = hueOf(200f, 30f, 50f)
        val boosted = ColorVibrancy.boostRgb(200f, 30f, 50f, 0.9f)
        val after = ColorVibrancy.rgbToHsv(boosted.r, boosted.g, boosted.b).h
        assertEquals(before, after, 0.5f)
    }

    @Test
    fun `boosted channels stay in gamut`() {
        for (amount in listOf(0.1f, 0.4f, 1f)) {
            val rgb = ColorVibrancy.boostRgb(255f, 200f, 10f, amount)
            assertTrue(rgb.r in 0f..255f)
            assertTrue(rgb.g in 0f..255f)
            assertTrue(rgb.b in 0f..255f)
        }
    }

    @Test
    fun `fully saturated color is unchanged by any boost`() {
        // s == 1 already => saturationFor leaves it at 1, so rgb must be identical.
        val r = ColorVibrancy.boostRgb(255f, 0f, 0f, 0.9f)
        assertEquals(255f, r.r, 1f)
        assertEquals(0f, r.g, 1f)
        assertEquals(0f, r.b, 1f)
    }

    @Test
    fun `alpha is preserved by boostColorInt`() {
        val argb = (0x80 shl 24) or 0xEF4444
        val out = ColorVibrancy.boostColorInt(argb, 0.8f)
        assertEquals(0x80, (out ushr 24) and 0xFF)
        assertTrue("boost must actually lift saturation", out != argb)
    }

    @Test
    fun `boosted color is more saturated than the source`() {
        fun sat(rgb: ColorVibrancy.Rgb): Float = ColorVibrancy.rgbToHsv(rgb.r, rgb.g, rgb.b).s
        val src = ColorVibrancy.boostRgb(120f, 140f, 160f, 0f)
        val boosted = ColorVibrancy.boostRgb(120f, 140f, 160f, 0.7f)
        assertTrue("boost must raise saturation", sat(boosted) > sat(src))
    }

    @Test
    fun `hsv round-trip is stable`() {
        val hsv = ColorVibrancy.rgbToHsv(200f, 30f, 50f)
        val rgb = ColorVibrancy.hsvToRgb(hsv.h, hsv.s, hsv.v)
        assertEquals(200f, rgb.r, 2f)
        assertEquals(30f, rgb.g, 2f)
        assertEquals(50f, rgb.b, 2f)
    }
}
