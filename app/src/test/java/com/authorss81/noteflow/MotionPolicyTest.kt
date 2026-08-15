package com.authorss81.noteflow

import com.authorss81.noteflow.services.MotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 36: pure-JVM tests for the motion/haptics policy — spring tuning selection,
 * the haptic-enable decision, slider-notch crossing detection, and the
 * shared-element reveal scale math. No android/Compose imports.
 */
class MotionPolicyTest {

    @Test
    fun `haptics allowed only when enabled and no reduce motion`() {
        assertTrue(MotionPolicy.hapticsAllowed(hapticsEnabled = true, reduceMotion = false))
        assertFalse(MotionPolicy.hapticsAllowed(hapticsEnabled = false, reduceMotion = false))
        assertFalse(MotionPolicy.hapticsAllowed(hapticsEnabled = true, reduceMotion = true))
        assertFalse(MotionPolicy.hapticsAllowed(hapticsEnabled = false, reduceMotion = true))
    }

    @Test
    fun `spring tuning per gesture kind is finite and positive`() {
        MotionPolicy.SpringKind.entries.forEach { kind ->
            val t = MotionPolicy.springFor(kind)
            assertTrue("dampingRatio>0 for $kind", t.dampingRatio > 0f && t.dampingRatio.isFinite())
            assertTrue("stiffness>0 for $kind", t.stiffness > 0f && t.stiffness.isFinite())
        }
    }

    @Test
    fun `gesture kinds have distinct tuned signatures`() {
        val sheet = MotionPolicy.springFor(MotionPolicy.SpringKind.SHEET)
        val dismiss = MotionPolicy.springFor(MotionPolicy.SpringKind.DISMISS)
        val pan = MotionPolicy.springFor(MotionPolicy.SpringKind.CANVAS_PAN)
        val reveal = MotionPolicy.springFor(MotionPolicy.SpringKind.REVEAL)

        // Canvas pan is overdamped (ratio > 1) so it never oscillates on overshoot.
        assertTrue("CANVAS_PAN overdamped", pan.dampingRatio >= 1f)
        // Dismiss settles without overshoot.
        assertEquals(1.0f, dismiss.dampingRatio, 1e-6f)
        // Sheet + reveal keep a gentle bounce (ratio < 1).
        assertTrue(sheet.dampingRatio < 1f)
        assertTrue(reveal.dampingRatio < 1f)
    }

    @Test
    fun `notch trigger fires only on quantized boundary crossing`() {
        assertTrue(MotionPolicy.sliderNotchTriggered(0.9f, 1.1f, granularity = 1f))
        assertTrue(MotionPolicy.sliderNotchTriggered(1.0f, 2.0f, granularity = 1f))
        assertFalse(MotionPolicy.sliderNotchTriggered(0.9f, 0.95f, granularity = 1f))
        assertFalse(MotionPolicy.sliderNotchTriggered(1.2f, 1.4f, granularity = 1f))
        assertFalse(MotionPolicy.sliderNotchTriggered(1.0f, 1.0f, granularity = 1f))
    }

    @Test
    fun `reveal start scale maps card width share to scale`() {
        // Half-width card: clamped up to the minimum reveal scale.
        assertEquals(0.55f, MotionPolicy.revealStartScale(cardWidth = 200f, containerWidth = 400f), 1e-6f)
        // Clamped to the minimum so tiny cards don't ignite sub-pixel.
        assertEquals(0.55f, MotionPolicy.revealStartScale(cardWidth = 10f, containerWidth = 400f), 1e-6f)
        // Three-quarter width card sits within the clamp band.
        assertEquals(0.75f, MotionPolicy.revealStartScale(cardWidth = 300f, containerWidth = 400f), 1e-6f)
        // Full-width card (or degenerate inputs) → no reveal.
        assertEquals(1f, MotionPolicy.revealStartScale(cardWidth = 400f, containerWidth = 400f), 1e-6f)
        assertEquals(1f, MotionPolicy.revealStartScale(cardWidth = 0f, containerWidth = 400f), 1e-6f)
        assertEquals(1f, MotionPolicy.revealStartScale(cardWidth = 400f, containerWidth = 0f), 1e-6f)
        // Custom min-scale honored (value already above it → unchanged).
        assertEquals(0.5f, MotionPolicy.revealStartScale(cardWidth = 200f, containerWidth = 400f, minScale = 0.2f), 1e-6f)
        // Custom min-scale raises a sub-threshold share up to the floor.
        assertEquals(0.2f, MotionPolicy.revealStartScale(cardWidth = 10f, containerWidth = 400f, minScale = 0.2f), 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `notch granularity must be positive`() {
        MotionPolicy.sliderNotchTriggered(0f, 1f, granularity = 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `spring tuning rejects non-positive damping`() {
        MotionPolicy.SpringTuning(dampingRatio = 0f, stiffness = 100f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `spring tuning rejects non-positive stiffness`() {
        MotionPolicy.SpringTuning(dampingRatio = 0.8f, stiffness = -1f)
    }
}
