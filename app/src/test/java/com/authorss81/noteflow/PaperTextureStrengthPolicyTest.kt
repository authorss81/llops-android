package com.authorss81.noteflow

import com.authorss81.noteflow.services.PaperTextureStrengthPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 227 — the paper-texture ("tooth") strength dial: clamp, the prompt's
 * `0.02 + strength/100 * 0.05` alpha lerp, and the AGSL wet-shader mapping.
 * Corruption-resistant and anchored at the pre-227 look for 50 (grainScale and
 * shaderGain are exactly 1.0 at the default — untouched installs render the
 * byte-identical pre-227 grain).
 */
class PaperTextureStrengthPolicyTest {

    @Test
    fun `dial clamps to the zero-100 dial and corrupt storage cannot escape`() {
        assertEquals(0, PaperTextureStrengthPolicy.clamp(-5))
        assertEquals(0, PaperTextureStrengthPolicy.clamp(0))
        assertEquals(50, PaperTextureStrengthPolicy.clamp(50))
        assertEquals(100, PaperTextureStrengthPolicy.clamp(100))
        assertEquals(100, PaperTextureStrengthPolicy.clamp(500))
        assertEquals(100, PaperTextureStrengthPolicy.clamp(Int.MAX_VALUE))
        assertEquals(0, PaperTextureStrengthPolicy.clamp(Int.MIN_VALUE))
        assertEquals(50, PaperTextureStrengthPolicy.DEFAULT)
    }

    @Test
    fun `fraction maps the dial onto the zero-to-one range`() {
        assertEquals(0f, PaperTextureStrengthPolicy.fraction(0), 0f)
        assertEquals(0.5f, PaperTextureStrengthPolicy.fraction(50), 0f)
        assertEquals(1f, PaperTextureStrengthPolicy.fraction(100), 0f)
        assertEquals(0f, PaperTextureStrengthPolicy.fraction(-20), 0f)
        assertEquals(1f, PaperTextureStrengthPolicy.fraction(200), 0f)
    }

    @Test
    fun `draw alpha follows the prompt lerp over the full dial with a true zero`() {
        // Phase 247: strength 0 is a TRUE ZERO (the "dots never disappear" fix),
        // not the old MIN_ALPHA lerp floor.
        assertEquals(0f, PaperTextureStrengthPolicy.grainDrawAlpha(0), 0f)
        assertEquals(0.07f, PaperTextureStrengthPolicy.grainDrawAlpha(100), 1e-6f)
        val mid = PaperTextureStrengthPolicy.grainDrawAlpha(50)
        assertEquals(0.045f, mid, 1e-6f)
        // strict monotone rise across the dial (0 is the floor, then the lerp
        // resumes from its MIN_ALPHA base so the linear dial is unchanged)
        var prev = -1f
        for (s in 0..100) {
            val a = PaperTextureStrengthPolicy.grainDrawAlpha(s)
            assertTrue("alpha must rise with strength: $s", a >= prev - 1e-6f)
            prev = a
        }
    }

    @Test
    fun `grain scale is anchored at unity for the default 50`() {
        assertEquals(1f, PaperTextureStrengthPolicy.grainScale(50), 0f)
    }

    @Test
    fun `grain scale renders the requested envelope with a true zero`() {
        // 0 / 0.045 and 0.07 / 0.045 (the old lerp floor vs the lerp ceiling).
        // Phase 247: strength 0 maps to EXACTLY 0 — no residual tooth.
        assertEquals(0f, PaperTextureStrengthPolicy.grainScale(0), 0f)
        assertEquals(0.07f / 0.045f, PaperTextureStrengthPolicy.grainScale(100), 1e-5f)
        // as the draw ALPHA was never more than the paper's speckle cap (0.05-0.07),
        // even the max dial keeps the apparent fleck inside the alpha envelope.
        assertTrue(PaperTextureStrengthPolicy.grainScale(100) < 1.6f)
    }

    @Test
    fun `shader strength is the dial as a zero-to-one uniform`() {
        assertEquals(0f, PaperTextureStrengthPolicy.shaderStrength(0), 0f)
        assertEquals(0.5f, PaperTextureStrengthPolicy.shaderStrength(50), 0f)
        assertEquals(1f, PaperTextureStrengthPolicy.shaderStrength(100), 0f)
    }

    @Test
    fun `shader gain anchors at unity and never exceeds the times-two table`() {
        assertEquals(1f, PaperTextureStrengthPolicy.shaderGain(50), 0f)
        assertEquals(0f, PaperTextureStrengthPolicy.shaderGain(0), 0f)
        assertEquals(2f, PaperTextureStrengthPolicy.shaderGain(100), 1e-6f)
        assertTrue("gain at 25 is half the base", abs(PaperTextureStrengthPolicy.shaderGain(25) - 0.5f) < 1e-6f)
    }

    private fun abs(v: Float): Float = if (v < 0f) -v else v
}
