package com.authorss81.noteflow

import com.authorss81.noteflow.services.TiltShadingPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 222 — TiltShadingPolicy: pure-math unit tests for tilt → width/alpha mapping.
 */
class TiltShadingPolicyTest {

    private val eps = 0.001f

    // ── widthFactor ──────────────────────────────────────────────────────

    @Test
    fun widthFactor_zeroTilt_returnsIdentity() {
        assertEquals(1f, TiltShadingPolicy.widthFactor(0f), eps)
    }

    @Test
    fun widthFactor_negativeTilt_returnsIdentity() {
        assertEquals(1f, TiltShadingPolicy.widthFactor(-10f), eps)
    }

    @Test
    fun widthFactor_45degrees_returnsIntermediate() {
        val w = TiltShadingPolicy.widthFactor(45f)
        // sin(45°) ≈ 0.7071 → 1 + 0.4*0.7071 ≈ 1.283
        assertEquals(1.283f, w, 0.01f)
    }

    @Test
    fun widthFactor_90degrees_returnsMax() {
        // sin(90°) = 1.0 → 1 + 0.4 = 1.4
        assertEquals(1.4f, TiltShadingPolicy.widthFactor(90f), eps)
    }

    @Test
    fun widthFactor_clampedAbove90() {
        assertEquals(TiltShadingPolicy.widthFactor(90f), TiltShadingPolicy.widthFactor(120f), eps)
    }

    @Test
    fun widthFactor_monotonicallyIncreasing() {
        val w30 = TiltShadingPolicy.widthFactor(30f)
        val w60 = TiltShadingPolicy.widthFactor(60f)
        val w90 = TiltShadingPolicy.widthFactor(90f)
        assert(w30 < w60) { "w30=$w30 should be < w60=$w60" }
        assert(w60 < w90) { "w60=$w60 should be < w90=$w90" }
    }

    // ── alphaFactor ──────────────────────────────────────────────────────

    @Test
    fun alphaFactor_zeroTilt_returnsIdentity() {
        assertEquals(1f, TiltShadingPolicy.alphaFactor(0f), eps)
    }

    @Test
    fun alphaFactor_negativeTilt_returnsIdentity() {
        assertEquals(1f, TiltShadingPolicy.alphaFactor(-5f), eps)
    }

    @Test
    fun alphaFactor_45degrees_returnsIntermediate() {
        val a = TiltShadingPolicy.alphaFactor(45f)
        // cos(45°) ≈ 0.7071 → 0.6 + 0.4*0.7071 ≈ 0.883
        assertEquals(0.883f, a, 0.01f)
    }

    @Test
    fun alphaFactor_90degrees_returnsMin() {
        // cos(90°) = 0 → 0.6
        assertEquals(0.6f, TiltShadingPolicy.alphaFactor(90f), eps)
    }

    @Test
    fun alphaFactor_clampedAbove90() {
        assertEquals(TiltShadingPolicy.alphaFactor(90f), TiltShadingPolicy.alphaFactor(150f), eps)
    }

    @Test
    fun alphaFactor_monotonicallyDecreasing() {
        val a30 = TiltShadingPolicy.alphaFactor(30f)
        val a60 = TiltShadingPolicy.alphaFactor(60f)
        val a90 = TiltShadingPolicy.alphaFactor(90f)
        assert(a30 > a60) { "a30=$a30 should be > a60=$a60" }
        assert(a60 > a90) { "a60=$a60 should be > a90=$a90" }
    }

    // ── combined invariant: width * alpha never exceeds 1.4 ──────────────

    @Test
    fun combinedProduct_boundedForAllTilts() {
        for (t in 0..90) {
            val w = TiltShadingPolicy.widthFactor(t.toFloat())
            val a = TiltShadingPolicy.alphaFactor(t.toFloat())
            val product = w * a
            // At 90°: 1.4 * 0.6 = 0.84 — well within 1.4 bound
            assert(product <= 1.4f) { "tilt=$t product=$product exceeds 1.4" }
        }
    }
}
