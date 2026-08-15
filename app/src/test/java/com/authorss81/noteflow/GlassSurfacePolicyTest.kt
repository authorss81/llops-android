package com.authorss81.noteflow

import com.authorss81.noteflow.theme.GlassBlurGate
import com.authorss81.noteflow.theme.GlassSurfaceMath
import com.authorss81.noteflow.theme.GlassSurfaceStyle
import com.authorss81.noteflow.utils.DeviceTier
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 34: glass fallback policy. The whole point of the gate is that blur is
 * only claimed where it renders (API 31+), LOW_END never pays for blur unless
 * the user explicitly asks, and the no-blur fallback is a deliberate
 * tonal/solid panel — never a silent visual hole.
 */
class GlassSurfacePolicyTest {

    @Test
    fun `blur is only renderable on API 31 and newer`() {
        assertFalse(GlassBlurGate.isBlurRenderable(26))
        assertFalse(GlassBlurGate.isBlurRenderable(30))
        assertTrue(GlassBlurGate.isBlurRenderable(31))
        assertTrue(GlassBlurGate.isBlurRenderable(36))
    }

    @Test
    fun `user disabling blur always wins`() {
        for (sdk in listOf(26, 31, 36)) {
            for (tier in DeviceTier.entries) {
                assertFalse(GlassBlurGate.shouldApplyBlur(sdk, tier, glassBlurEnabled = false))
            }
        }
    }

    @Test
    fun `low end devices never blur unless explicitly opted in is not possible - they never blur`() {
        // LOW_END is excluded in the gate regardless of the setting flag.
        assertFalse(GlassBlurGate.shouldApplyBlur(36, DeviceTier.LOW_END, glassBlurEnabled = true))
        assertTrue(GlassBlurGate.shouldApplyBlur(36, DeviceTier.MID_RANGE, glassBlurEnabled = true))
        assertTrue(GlassBlurGate.shouldApplyBlur(36, DeviceTier.FLAGSHIP, glassBlurEnabled = true))
    }

    @Test
    fun `old sdk never blurs even when everything else permits it`() {
        assertFalse(GlassBlurGate.shouldApplyBlur(30, DeviceTier.FLAGSHIP, glassBlurEnabled = true))
        assertFalse(GlassBlurGate.shouldApplyBlur(26, DeviceTier.FLAGSHIP, glassBlurEnabled = true))
    }

    @Test
    fun `blurring devices resolve to blurred frost`() {
        for (tier in listOf(DeviceTier.MID_RANGE, DeviceTier.FLAGSHIP)) {
            assertEquals(
                GlassSurfaceStyle.BLURRED_FROST,
                GlassSurfaceMath.resolveStyle(applyBlur = true, tier = tier, tonalTint = true)
            )
        }
    }

    @Test
    fun `low end fallback is a solid tonal panel - never translucent frosted`() {
        assertEquals(
            GlassSurfaceStyle.TONAL_SOLID,
            GlassSurfaceMath.resolveStyle(applyBlur = false, tier = DeviceTier.LOW_END, tonalTint = true)
        )
        // Tonal tint off also forces the solid panel.
        assertEquals(
            GlassSurfaceStyle.TONAL_SOLID,
            GlassSurfaceMath.resolveStyle(applyBlur = false, tier = DeviceTier.FLAGSHIP, tonalTint = false)
        )
    }

    @Test
    fun `capable devices without blur keep the translucent frost`() {
        assertEquals(
            GlassSurfaceStyle.TONAL_FROST,
            GlassSurfaceMath.resolveStyle(applyBlur = false, tier = DeviceTier.FLAGSHIP, tonalTint = true)
        )
        assertEquals(
            GlassSurfaceStyle.TONAL_FROST,
            GlassSurfaceMath.resolveStyle(applyBlur = false, tier = DeviceTier.MID_RANGE, tonalTint = true)
        )
    }

    @Test
    fun `edge glow is brighter in light themes for a luminescent rim`() {
        val lightGlow = GlassSurfaceMath.edgeGlowColor(Color(0xFFF6F2EB))
        val darkGlow = GlassSurfaceMath.edgeGlowColor(Color(0xFF0F172A))
        assertTrue(lightGlow.alpha > 0f)
        assertTrue(darkGlow.alpha > 0f)
        // Light themes get a stronger near-white rim; dark themes a subtle one,
        // so the light-surface rim is the "brighter" of the two.
        assertTrue(lightGlow.alpha > darkGlow.alpha)
        assertTrue(lightGlow.copy(alpha = 1f) == Color.White)
        assertTrue(darkGlow.copy(alpha = 1f) == Color.White)
    }

    @Test
    fun `depth gradient always includes a transparent end`() {
        val light = GlassSurfaceMath.depthGradient(Color(0xFFF6F2EB))
        val dark = GlassSurfaceMath.depthGradient(Color(0xFF0F172A))
        assertTrue(light.last().alpha == 0f)
        assertTrue(dark.last().alpha == 0f)
    }
}