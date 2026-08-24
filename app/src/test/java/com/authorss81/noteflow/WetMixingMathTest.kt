package com.authorss81.noteflow

import androidx.compose.ui.graphics.colorspace.ColorSpaces
import com.authorss81.noteflow.services.WetMixingMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WetMixingMathTest {

    @Test
    fun `overlapping washes darken monotonically`() {
        // base + first wash
        val first = WetMixingMath.sourceOverAlpha(0f, 0.4f)
        assertEquals("single wash alpha wrong", 0.4f, first, 1e-6f)
        // second overlapping wash must increase, never block accumulation
        val second = WetMixingMath.sourceOverAlpha(first, 0.4f)
        assertTrue("wet-on-wet must darken: $second <= $first", second > first)
        assertEquals("source-over formula", 0.4f + 0.4f * 0.6f, second, 1e-6f)
        // stays bounded below 1
        val saturated = WetMixingMath.sourceOverAlpha(0.9f, 0.9f)
        assertEquals("source-over saturates toward 1", 0.99f, saturated, 1e-6f)
        assertTrue(saturated < 1f)
    }

    @Test
    fun `complementary colors stay clean instead of muddy`() {
        // cyan-ish base vs red-ish brush, full deposit
        val baseR = 0f; val baseG = 0.55f; val baseB = 0.55f
        val brushR = 0.9f; val brushG = 0.25f; val brushB = 0.25f
        val mud = WetMixingMath.pigmentMixRgb(baseR, baseG, baseB, brushR, brushG, brushB, 1f)
        // linear-RGB lerp would collapse all channels toward ~(0.45,0.4,0.4) = gray/muddy
        assertEquals("R must stay high in pigment space", 0.9f, mud[0], 1e-6f)
        assertTrue("G must stay well above muddy 0.4", mud[1] > 0.5f)
        assertTrue("B must stay well above muddy 0.4", mud[2] > 0.5f)
    }

    @Test
    fun `pigment deposit of same color deepens toward the brush color`() {
        val base = 0.5f; val brush = 0.3f
        val out = WetMixingMath.pigmentMix(base, brush, 1f)
        // 1-(1-0.5)(1-0.3)=0.65, a deeper deposit
        assertEquals("combined absorbance mistmatch", 0.65f, out, 1e-6f)
        // factor 0 must keep the base untouched, 1 fully deposits
        assertEquals("factor 0 must keep base", base, WetMixingMath.pigmentMix(base, brush, 0f), 1e-6f)
        assertEquals("factor 1 must fully deposit", 0.65f, WetMixingMath.pigmentMix(base, brush, 1f), 1e-6f)
    }

    // ---- Phase 200 (PERF 3.2): linear-light wet mixing ----------------------

    @Test
    fun `sRGB transfer functions are the standard piecewise EOTF`() {
        assertEquals(0f, WetMixingMath.srgbToLinear(0f), 1e-7f)
        assertEquals(1f, WetMixingMath.srgbToLinear(1f), 1e-7f)
        assertEquals(0f, WetMixingMath.linearToSrgb(0f), 1e-7f)
        assertEquals(1f, WetMixingMath.linearToSrgb(1f), 1e-7f)
        // canonical mid-gray value of the sRGB EOTF
        assertEquals(0.21404114f, WetMixingMath.srgbToLinear(0.5f), 1e-6f)
        // linear segment below the knee
        assertEquals(0.03f / 12.92f, WetMixingMath.srgbToLinear(0.03f), 1e-7f)
        assertEquals(0.001f * 12.92f, WetMixingMath.linearToSrgb(0.001f), 1e-7f)
        // inverse round trip across the whole range
        var v = 0f
        while (v <= 1f) {
            assertEquals("round trip failed at $v", v, WetMixingMath.linearToSrgb(WetMixingMath.srgbToLinear(v)), 1e-5f)
            v += 0.01f
        }
        // out-of-range inputs fail safe into [0,1]
        assertEquals(0f, WetMixingMath.srgbToLinear(-3f), 1e-7f)
        assertEquals(1f, WetMixingMath.linearToSrgb(9f), 1e-7f)
    }

    @Test
    fun `default mix space is linear light`() {
        val baseR = 0.2f; val baseG = 0.55f; val baseB = 0.8f
        val brushR = 0.9f; val brushG = 0.25f; val brushB = 0.4f
        val def = WetMixingMath.pigmentMixRgb(baseR, baseG, baseB, brushR, brushG, brushB, 0.7f)
        val linear = WetMixingMath.pigmentMixRgb(baseR, baseG, baseB, brushR, brushG, brushB, 0.7f, ColorSpaces.LinearSrgb)
        val gamma = WetMixingMath.pigmentMixRgb(baseR, baseG, baseB, brushR, brushG, brushB, 0.7f, ColorSpaces.Srgb)
        for (i in 0..2) {
            assertEquals("default must be LinearSrgb", linear[i], def[i], 1e-7f)
        }
        // and the two spaces genuinely differ on mid-tones (otherwise the fix is a no-op)
        var differs = false
        for (i in 0..2) if (Math.abs(linear[i] - gamma[i]) > 1e-3f) differs = true
        assertTrue("linear and gamma mixes must differ on mid-tones", differs)
    }

    @Test
    fun `SRGB mix space reproduces the pre-200 gamma-space formula bit-for-bit`() {
        val baseR = 0.2f; val baseG = 0.55f; val baseB = 0.8f
        val brushR = 0.9f; val brushG = 0.25f; val brushB = 0.4f
        val legacy = WetMixingMath.pigmentMixRgb(baseR, baseG, baseB, brushR, brushG, brushB, 0.7f, ColorSpaces.Srgb)
        for (i in 0..2) {
            val b = arrayOf(baseR, baseG, baseB)[i]
            val c = arrayOf(brushR, brushG, brushB)[i]
            val expected = b + (1f - (1f - b) * (1f - c) - b) * 0.7f
            assertEquals("legacy parity broken on channel $i", expected, legacy[i], 1e-6f)
        }
    }

    @Test
    fun `mix endpoints are exact identities in any space`() {
        val bases = listOf(floatArrayOf(0.2f, 0.55f, 0.8f), floatArrayOf(0f, 0f, 0f))
        val brushes = listOf(floatArrayOf(0.9f, 0.25f, 0.4f), floatArrayOf(1f, 1f, 1f))
        for (space in listOf(ColorSpaces.LinearSrgb, ColorSpaces.Srgb)) {
            for (base in bases) {
                for (brush in brushes) {
                    // factor 0 keeps the base untouched
                    val kept = WetMixingMath.pigmentMixRgb(base[0], base[1], base[2], brush[0], brush[1], brush[2], 0f, space)
                    for (i in 0..2) assertEquals("factor 0 must be identity", base[i], kept[i], 1e-6f)
                    // a full deposit onto pure black returns the brush color exactly
                    val deposited = WetMixingMath.pigmentMixRgb(0f, 0f, 0f, brush[0], brush[1], brush[2], 1f, space)
                    for (i in 0..2) assertEquals("full deposit on black must return brush", brush[i], deposited[i], 1e-6f)
                }
            }
        }
    }

    @Test
    fun `linear mixing preserves more chroma than gamma mixing (anti-mud)`() {
        // Bright cyan wash under a bright red deposit — the classic mud case.
        val baseR = 0f; val baseG = 0.55f; val baseB = 0.55f
        val brushR = 0.9f; val brushG = 0.25f; val brushB = 0.25f
        val linear = WetMixingMath.pigmentMixRgb(baseR, baseG, baseB, brushR, brushG, brushB, 1f, ColorSpaces.LinearSrgb)
        val gamma = WetMixingMath.pigmentMixRgb(baseR, baseG, baseB, brushR, brushG, brushB, 1f, ColorSpaces.Srgb)
        fun chroma(c: FloatArray) = c.max() - c.min()
        assertTrue(
            "linear result must stay more saturated (less muddy): ${chroma(linear)} vs ${chroma(gamma)}",
            chroma(linear) >= chroma(gamma)
        )
        // golden values pinned from the piecewise EOTF math
        assertEquals(0.9f, linear[0], 1e-5f)
        assertEquals(0.58450f, linear[1], 2e-4f)
        assertEquals(0.58450f, linear[2], 2e-4f)
    }

    @Test
    fun `unknown custom spaces fail safe to linear light`() {
        val baseR = 0.2f; val baseG = 0.55f; val baseB = 0.8f
        val brushR = 0.9f; val brushG = 0.25f; val brushB = 0.4f
        val fallback = WetMixingMath.pigmentMixRgb(baseR, baseG, baseB, brushR, brushG, brushB, 0.7f, ColorSpaces.CieLab)
        val linear = WetMixingMath.pigmentMixRgb(baseR, baseG, baseB, brushR, brushG, brushB, 0.7f, ColorSpaces.LinearSrgb)
        for (i in 0..2) assertEquals("unknown spaces must route through the linear mirror", linear[i], fallback[i], 1e-7f)
    }
}
