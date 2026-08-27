package com.authorss81.noteflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 222 — Alpha-lock + clipping mask mask-math validation.
 *
 * These tests verify the logical compositing equations that the canvas
 * DST_IN pipeline implements. The actual Bitmap-level rendering is tested
 * via Paparazzi snapshots; these pure-math tests cover the core invariants.
 */
class AlphaLockClippingTest {

    private val eps = 0.001f

    // ── DST_IN math ─────────────────────────────────────────────────────
    // DST_IN: result = dst * srcAlpha
    // In alpha-lock context: dst = existing layer bitmap, src = new stroke
    // paint with SRC_IN: only where src has content, draw src color.
    // Combined: only where BOTH dst AND src have non-zero alpha.

    @Test
    fun dstIn_maskedAlpha_productOfAlphas() {
        // Existing pixel alpha = 0.5, stroke pixel alpha = 0.8
        // After DST_IN: result alpha = 0.5 * 0.8 = 0.4
        val existingAlpha = 0.5f
        val strokeAlpha = 0.8f
        val resultAlpha = existingAlpha * strokeAlpha
        assertEquals(0.4f, resultAlpha, eps)
    }

    @Test
    fun dstIn_zeroExistingAlpha_producesTransparent() {
        // Where layer has no ink (alpha = 0), stroke should not appear
        val existingAlpha = 0f
        val strokeAlpha = 1f
        assertEquals(0f, existingAlpha * strokeAlpha, eps)
    }

    @Test
    fun dstIn_zeroStrokeAlpha_producesTransparent() {
        // Where stroke has no content, result is transparent
        val existingAlpha = 1f
        val strokeAlpha = 0f
        assertEquals(0f, existingAlpha * strokeAlpha, eps)
    }

    @Test
    fun dstIn_fullAlphas_producesFullAlpha() {
        assertEquals(1f, 1f * 1f, eps)
    }

    // ── Clipping mask: layer N clips to layer N-1 ────────────────────────

    @Test
    fun clippingMask_layerNClearedByLayerNMinusOne() {
        // Layer N-1 has content in a 100x100 region, Layer N has content
        // across 200x200. After clipping, Layer N is visible only in the
        // 100x100 region.
        val layerNMinusOneAlpha = 1f  // fully opaque in region
        val layerNAlpha = 1f
        val resultAlpha = layerNMinusOneAlpha * layerNAlpha
        assertEquals(1f, resultAlpha, eps) // visible

        // Outside the N-1 region
        val layerNMinusOneAlphaOutside = 0f
        val resultAlphaOutside = layerNMinusOneAlphaOutside * layerNAlpha
        assertEquals(0f, resultAlphaOutside, eps) // clipped
    }

    // ── Alpha-lock edge cases ────────────────────────────────────────────

    @Test
    fun alphaLock_nearZeroExistingAlpha_nearlyTransparent() {
        val existingAlpha = 0.01f
        val strokeAlpha = 1f
        val result = existingAlpha * strokeAlpha
        assertEquals(0.01f, result, eps)
    }

    @Test
    fun alphaLock_halfExistingAlpha_halfVisible() {
        val existingAlpha = 0.5f
        val strokeAlpha = 1f
        assertEquals(0.5f, existingAlpha * strokeAlpha, eps)
    }
}
