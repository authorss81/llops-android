package com.authorss81.noteflow

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
}