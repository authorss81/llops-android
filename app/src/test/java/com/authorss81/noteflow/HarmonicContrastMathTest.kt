package com.authorss81.noteflow

import com.authorss81.noteflow.services.ColorHarmonyHelper
import com.authorss81.noteflow.services.HarmonicContrastMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the Phase 35 harmonic contrast math: WCAG luminance &
 * ratios, complementary / analogous colour picks, saturation pads, and gamut
 * safety. Known reference values come from the WCAG 2.x spec.
 */
class HarmonicContrastMathTest {

    private fun rgb(r: Int, g: Int, b: Int) = ColorHarmonyHelper.Rgb(r.toFloat(), g.toFloat(), b.toFloat())

    // ---- WCAG luminance --------------------------------------------------------

    @Test
    fun `black and white luminances match spec`() {
        assertEquals(0f, HarmonicContrastMath.relativeLuminance(rgb(0, 0, 0)), 1e-5f)
        assertEquals(1f, HarmonicContrastMath.relativeLuminance(rgb(255, 255, 255)), 1e-5f)
    }

    @Test
    fun `pure green luminance matches the spec value`() {
        // WCAG: L(#00FF00) = 0.7152. Linearized channel = 1.0 -> 0.7152.
        assertEquals(0.7152f, HarmonicContrastMath.relativeLuminance(rgb(0, 255, 0)), 1e-4f)
    }

    @Test
    fun `red is less luminous than green`() {
        assertTrue(
            HarmonicContrastMath.relativeLuminance(rgb(255, 0, 0)) <
                HarmonicContrastMath.relativeLuminance(rgb(0, 255, 0))
        )
    }

    // ---- contrast ratio ---------------------------------------------------------

    @Test
    fun `identical colors yield ratio 1`() {
        val c = rgb(120, 90, 60)
        assertEquals(1f, HarmonicContrastMath.contrastRatio(c, c), 1e-4f)
    }

    @Test
    fun `black on white is 21 to 1`() {
        assertEquals(21f, HarmonicContrastMath.contrastRatio(rgb(0, 0, 0), rgb(255, 255, 255)), 0.05f)
    }

    @Test
    fun `white on black is also 21 to 1 (order independent)`() {
        assertEquals(
            HarmonicContrastMath.contrastRatio(rgb(255, 255, 255), rgb(0, 0, 0)),
            HarmonicContrastMath.contrastRatio(rgb(0, 0, 0), rgb(255, 255, 255)),
            1e-4f
        )
    }

    @Test
    fun `ratio is always within wcag bounds`() {
        for (r in intArrayOf(0, 80, 170, 255)) for (g in intArrayOf(0, 80, 170, 255)) for (b in intArrayOf(0, 80, 170, 255)) {
            val ratio = HarmonicContrastMath.contrastRatio(rgb(r, g, b), rgb(255, 255, 255))
            assertTrue(ratio in 1f..21.01f)
        }
    }

    // ---- complementary / analogous -----------------------------------------------

    @Test
    fun `complementary rotates hue by 180 degrees`() {
        val source = rgb(220, 40, 40)
        val comp = HarmonicContrastMath.complementary(source)
        val sH = ColorHarmonyHelper.rgbToHsl(source.r, source.g, source.b).h
        val cH = ColorHarmonyHelper.rgbToHsl(comp.r, comp.g, comp.b).h
        assertEquals(180f, HarmonicContrastMath.hueDeltaDeg(sH, cH), 1f)
    }

    @Test
    fun `complementary stays in gamut`() {
        val comp = HarmonicContrastMath.complementary(rgb(220, 40, 200))
        assertTrue(HarmonicContrastMath.inGamut(comp))
    }

    @Test
    fun `analogous picks sit roughly 30 degrees away`() {
        val source = rgb(60, 140, 220)
        val sH = ColorHarmonyHelper.rgbToHsl(source.r, source.g, source.b).h
        for (a in HarmonicContrastMath.analogousPicks(source)) {
            val aH = ColorHarmonyHelper.rgbToHsl(a.r, a.g, a.b).h
            val d = HarmonicContrastMath.hueDeltaDeg(sH, aH)
            assertTrue("analogous delta $d should be ~30", d in 28f..32f)
        }
    }

    @Test
    fun `analogous returns two distinct colors`() {
        val picks = HarmonicContrastMath.analogousPicks(rgb(60, 140, 220))
        assertEquals(2, picks.size)
        assertNotEquals(picks[0], picks[1])
    }

    // ---- suggestions ---------------------------------------------------------------

    @Test
    fun `suggestions always yield complement analogues and pads`() {
        val s = HarmonicContrastMath.suggestions(rgb(90, 120, 200), rgb(255, 255, 255))
        assertEquals(5, s.size)
        assertEquals("Complement", s[0].label)
        assertTrue(s.any { it.label == "Lighter" })
        assertTrue(s.any { it.label == "Darker" })
        for (item in s) {
            assertTrue(HarmonicContrastMath.inGamut(item.color))
            assertTrue(item.ratio >= 1f)
            assertTrue(item.label.isNotBlank())
        }
    }

    @Test
    fun `a dark source gets a lighter pad that likely clears AA`() {
        val s = HarmonicContrastMath.suggestions(rgb(30, 20, 60), rgb(255, 255, 255))
        val lighter = s.first { it.label == "Lighter" }
        val darker = s.first { it.label == "Darker" }
        assertTrue("lighter pad should be brighter than source", HarmonicContrastMath.relativeLuminance(lighter.color) >= HarmonicContrastMath.relativeLuminance(rgb(30, 20, 60)))
        assertTrue("darker pad must still be in gamut", HarmonicContrastMath.inGamut(darker.color))
    }

    // ---- padFor ----------------------------------------------------------------------

    @Test
    fun `padFor returns source unchanged when it already clears the bar`() {
        val source = rgb(20, 20, 20)
        val bg = rgb(255, 255, 255)
        assertEquals(source, HarmonicContrastMath.padFor(source, bg, minRatio = 4.5f))
    }

    @Test
    fun `padFor reaches the target ratio against white`() {
        // a mid grey cannot reach AA on white unless lightened or darkened.
        val padOnWhite = HarmonicContrastMath.padFor(rgb(128, 128, 128), rgb(255, 255, 255), minRatio = 4.5f)
        assertTrue(
            HarmonicContrastMath.contrastRatio(padOnWhite, rgb(255, 255, 255)) >= 4.5f - 0.05f
        )
    }

    @Test
    fun `padFor never leaves gamut`() {
        for (i in 0 until 40) {
            val source = rgb((i * 7) % 256, (i * 3 + 40) % 256, (255 - i * 5).mod(256))
            val padded = HarmonicContrastMath.padFor(source, rgb(252, 250, 244), minRatio = 4.5f)
            assertTrue(HarmonicContrastMath.inGamut(padded))
        }
    }

    // ---- gamut / delta helpers --------------------------------------------------------

    @Test
    fun `inGamut flags out-of-range channels`() {
        assertTrue(HarmonicContrastMath.inGamut(rgb(0, 0, 0)))
        assertTrue(HarmonicContrastMath.inGamut(rgb(255, 255, 255)))
        assertTrue(!HarmonicContrastMath.inGamut(ColorHarmonyHelper.Rgb(-1f, 0f, 0f)))
        assertTrue(!HarmonicContrastMath.inGamut(ColorHarmonyHelper.Rgb(0f, 300f, 0f)))
    }

    @Test
    fun `hueDeltaDeg is symmetric and bounded by 180`() {
        assertEquals(0f, HarmonicContrastMath.hueDeltaDeg(10f, 10f), 1e-4f)
        assertEquals(180f, HarmonicContrastMath.hueDeltaDeg(0f, 180f), 1e-4f)
        assertEquals(10f, HarmonicContrastMath.hueDeltaDeg(350f, 0f), 1e-4f)
        val a = HarmonicContrastMath.hueDeltaDeg(100f, 250f)
        assertEquals(a, HarmonicContrastMath.hueDeltaDeg(250f, 100f), 1e-4f)
        assertTrue(a <= 180f)
    }
}