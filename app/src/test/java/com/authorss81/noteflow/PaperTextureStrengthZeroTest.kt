package com.authorss81.noteflow

import com.authorss81.noteflow.services.PaperTextureStrengthPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 247 — TRUE ZERO at paper-texture strength 0. User report: "paper
 * texture dots never fully disappear" — `grainDrawAlpha` had a hardcoded
 * `MIN_ALPHA = 0.02f` lerp floor, so strength 0 still drew ~44% of the default
 * grain. Every grain path must return exactly `0f` at strength 0 while the
 * [PaperTextureStrengthPolicy.DEFAULT] (50) anchoring stays byte-identical to
 * the pre-227 rendering (untouched installs look unchanged).
 */
class PaperTextureStrengthZeroTest {

    @Test
    fun `grain draw alpha is exactly zero at strength zero`() {
        assertEquals(0f, PaperTextureStrengthPolicy.grainDrawAlpha(0), 0f)
    }

    @Test
    fun `grain scale is exactly zero at strength zero`() {
        assertEquals(0f, PaperTextureStrengthPolicy.grainScale(0), 0f)
    }

    @Test
    fun `shader gain is exactly zero at strength zero`() {
        assertEquals(0f, PaperTextureStrengthPolicy.shaderGain(0), 0f)
    }

    @Test
    fun `shader strength is exactly zero at strength zero`() {
        assertEquals(0f, PaperTextureStrengthPolicy.shaderStrength(0), 0f)
    }

    @Test
    fun `default 50 anchor is unchanged pre-227`() {
        assertEquals(0.045f, PaperTextureStrengthPolicy.grainDrawAlpha(50), 0f)
    }

    @Test
    fun `grain scale anchors at unity for the default 50`() {
        assertEquals(1f, PaperTextureStrengthPolicy.grainScale(50), 0f)
    }

    @Test
    fun `shader gain anchors at unity for the default 50`() {
        assertEquals(1f, PaperTextureStrengthPolicy.shaderGain(50), 0f)
    }

    @Test
    fun `ceiling is preserved at strength 100`() {
        assertEquals(0.07f, PaperTextureStrengthPolicy.grainDrawAlpha(100), 0f)
    }

    @Test
    fun `draw alpha rises monotonically from true zero`() {
        assertTrue(PaperTextureStrengthPolicy.grainDrawAlpha(0) < PaperTextureStrengthPolicy.grainDrawAlpha(50))
        assertTrue(PaperTextureStrengthPolicy.grainDrawAlpha(50) < PaperTextureStrengthPolicy.grainDrawAlpha(100))
    }

    @Test
    fun `clamp keeps zero and ceiling across corrupt storage`() {
        assertEquals(0f, PaperTextureStrengthPolicy.grainDrawAlpha(-5), 0f)
        assertEquals(0.07f, PaperTextureStrengthPolicy.grainDrawAlpha(999), 0f)
    }
}