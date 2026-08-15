package com.authorss81.noteflow

import com.authorss81.noteflow.services.NibPreviewMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the Phase 35 live nib preview parameter math that drives
 * PenNibVisualPreview while the user drags the pressure/tilt/wetness sliders.
 */
class NibPreviewMathTest {

    // ---- pressure -> width ------------------------------------------------

    @Test
    fun `pressure zero still spreads the resting nib`() {
        assertTrue(NibPreviewMath.pressureToWidthScale(0f) >= 0.35f)
    }

    @Test
    fun `full pressure flattens to the max scale`() {
        assertEquals(1.15f, NibPreviewMath.pressureToWidthScale(1f), 1e-4f)
    }

    @Test
    fun `harder pressure never shrinks the stroke`() {
        val soft = NibPreviewMath.pressureToWidthScale(0.2f)
        val hard = NibPreviewMath.pressureToWidthScale(1f)
        assertTrue(hard >= soft)
        // monotone across a sweep
        var prev = 0f
        for (i in 0..20) {
            val p = i / 20f
            val s = NibPreviewMath.pressureToWidthScale(p)
            assertTrue("non-monotone at $p: $s", s >= prev - 1e-6f)
            prev = s
        }
    }

    @Test
    fun `pressure is clamped to the 0-1 range`() {
        assertTrue(NibPreviewMath.pressureToWidthScale(-5f) in 0.35f..1.15f)
        assertTrue(NibPreviewMath.pressureToWidthScale(5f) in 0.35f..1.15f)
    }

    @Test
    fun `custom min and max scales bound the output`() {
        val s = NibPreviewMath.pressureToWidthScale(0.7f, minScale = 0.4f, maxScale = 1.5f)
        assertTrue(s in 0.4f..1.5f)
    }

    @Test
    fun `widthForPressure scales the base width proportionally`() {
        assertEquals(20f * NibPreviewMath.pressureToWidthScale(1f), NibPreviewMath.widthForPressure(20f, 1f), 1e-3f)
    }

    @Test
    fun `widthForPressure never returns negative for bad base`() {
        assertEquals(0f, NibPreviewMath.widthForPressure(-10f, 1f), 1e-6f)
    }

    // ---- tilt -> opacity ----------------------------------------------------

    @Test
    fun `upright nib keeps full opacity`() {
        assertEquals(1f, NibPreviewMath.tiltToOpacityFactor(0f), 1e-6f)
    }

    @Test
    fun `flat nib fades toward the minimum opacity`() {
        assertEquals(0.30f, NibPreviewMath.tiltToOpacityFactor(90f), 1e-4f)
    }

    @Test
    fun `tilt is monotone toward the fade floor`() {
        var prev = 1f
        for (t in 0..90 step 5) {
            val f = NibPreviewMath.tiltToOpacityFactor(t.toFloat())
            assertTrue(f <= prev + 1e-6f)
            prev = f
        }
    }

    @Test
    fun `tilt outside range is clamped`() {
        assertTrue(NibPreviewMath.tiltToOpacityFactor(200f) in 0.30f..1f)
    }

    // ---- wetness -> coefficients --------------------------------------------

    @Test
    fun `dry brush is crisp with full pigment`() {
        val c = NibPreviewMath.wetnessToCoeffs(0f)
        assertEquals(0f, c.bleed, 1e-6f)
        assertEquals(0f, c.blurWeight, 1e-6f)
        assertEquals(1f, c.keepAlpha, 1e-6f)
    }

    @Test
    fun `soaking wet feathers about a third of the width`() {
        val c = NibPreviewMath.wetnessToCoeffs(1f)
        assertEquals(0.35f, c.bleed, 1e-4f)
        assertEquals(0.75f, c.blurWeight, 1e-4f)
        assertTrue("keepAlpha must stay positive", c.keepAlpha in 0f..1f)
    }

    @Test
    fun `wetness is clamped to the 0-1 range`() {
        val low = NibPreviewMath.wetnessToCoeffs(-1f)
        val high = NibPreviewMath.wetnessToCoeffs(2f)
        assertTrue(low.bleed == 0f)
        assertTrue(high.bleed <= 0.35f)
        assertTrue(high.keepAlpha >= 0f)
    }

    // ---- stroke alpha --------------------------------------------------------

    @Test
    fun `dry stroke is nearly opaque`() {
        assertEquals(0.92f, NibPreviewMath.strokeAlphaForWetness(0f), 1e-4f)
    }

    @Test
    fun `wet stroke alpha stays bounded`() {
        assertTrue(NibPreviewMath.strokeAlphaForWetness(1f) in 0.30f..0.92f)
    }

    @Test
    fun `alpha never exceeds the opaque bound for any wetness`() {
        for (w in 0..20) {
            val a = NibPreviewMath.strokeAlphaForWetness(w / 20f)
            assertTrue(a <= 0.92f + 1e-4f)
            assertTrue(a >= 0.30f - 1e-4f)
        }
    }

    // ---- feather radius ------------------------------------------------------

    @Test
    fun `dry brush feathers zero`() {
        assertEquals(0f, NibPreviewMath.featherRadius(20f, 0f), 1e-6f)
    }

    @Test
    fun `feather is a fixed fraction of the base width`() {
        assertEquals(20f * 0.35f, NibPreviewMath.featherRadius(20f, 1f), 1e-4f)
    }

    // ---- combined preview params ---------------------------------------------

    @Test
    fun `preview params composition stays within rendered bounds`() {
        val p = NibPreviewMath.previewParamsFor(baseWidth = 18f, pressure = 0.62f, tiltDeg = 18f, wetness = 0.25f)
        assertTrue(p.width > 0f)
        assertTrue(p.alpha in 0f..1f)
        assertTrue(p.feather >= 0f)
        assertTrue(p.tiltFade in 0.30f..1f)
    }

    @Test
    fun `preview params respect pressure clip`() {
        val p0 = NibPreviewMath.previewParamsFor(20f, 0.2f, 0f, 0f)
        val p1 = NibPreviewMath.previewParamsFor(20f, 1f, 0f, 0f)
        assertTrue(p1.width > p0.width)
    }

    // ---- display helpers ------------------------------------------------------

    @Test
    fun `percent maps the slider range to whole numbers`() {
        assertEquals(0, NibPreviewMath.percent(0.003f))
        assertEquals(62, NibPreviewMath.percent(0.62f))
        assertEquals(100, NibPreviewMath.percent(1.5f))
    }

    @Test
    fun `sqrtPress is the square root of the clamped pressure`() {
        assertEquals(1.0f, NibPreviewMath.sqrtPress(1f), 1e-4f)
        assertEquals(0.0f, NibPreviewMath.sqrtPress(0f), 1e-4f)
        assertEquals(0.5f, NibPreviewMath.sqrtPress(0.25f), 1e-4f)
    }
}