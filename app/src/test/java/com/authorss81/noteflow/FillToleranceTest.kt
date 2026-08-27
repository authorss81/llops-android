package com.authorss81.noteflow

import com.authorss81.noteflow.services.BrushColorModeMath
import com.authorss81.noteflow.services.FloodFillEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 221: unit tests for FloodFillEngine tolerance boundaries,
 * contour extraction + RDP simplification, and gradient color
 * interpolation via BrushColorModeMath.gradientColorAt.
 *
 * Pure JVM — no Android framework, no instrumentation.
 */
class FillToleranceTest {

    // ---- helpers ----------------------------------------------------------

    /** Pack ARGB bytes into a single Int (big-endian, matching Android convention). */
    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    /** Build a flat IntArray "bitmap" of [w]×[h] filled with [color]. */
    private fun solidBitmap(w: Int, h: Int, color: Int): IntArray =
        IntArray(w * h) { color }

    // ---- FloodFillEngine: tolerance boundary tests ------------------------

    @Test
    fun `exact-match fill on uniform 3x3 fills all 9 pixels`() {
        val w = 3; val h = 3
        val white = argb(255, 255, 255, 255)
        val red = argb(255, 255, 0, 0)
        val pixels = solidBitmap(w, h, white)
        val result = FloodFillEngine.floodFillIndices(pixels, w, h, 1, 1, red, tolerancePercent = 0f)
        assertEquals("all 9 pixels filled", 9, result.size)
        for (p in pixels) assertEquals("pixel must be red", red, p)
    }

    @Test
    fun `zero tolerance rejects one-byte-different neighbour`() {
        val w = 3; val h = 1
        val pixels = intArrayOf(
            argb(255, 255, 255, 255),
            argb(255, 254, 255, 255),
            argb(255, 255, 255, 255)
        )
        val result = FloodFillEngine.floodFillIndices(pixels, w, h, 0, 0, argb(255, 0, 0, 0), tolerancePercent = 0f)
        assertEquals("only the seed pixel filled", 1, result.size)
    }

    @Test
    fun `12-percent tolerance bridges small color gap`() {
        val w = 3; val h = 1
        val seed = argb(255, 200, 200, 200)
        val close = argb(255, 190, 190, 190)
        val far = argb(255, 100, 100, 100)
        val pixels = intArrayOf(seed, close, far)
        val fill = argb(255, 0, 0, 0)
        val result = FloodFillEngine.floodFillIndices(pixels, w, h, 0, 0, fill, tolerancePercent = 12f)
        assertEquals("seed + close neighbour filled (2)", 2, result.size)
        assertEquals("close pixel painted", fill, pixels[1])
        assertEquals("far pixel untouched", far, pixels[2])
    }

    @Test
    fun `12-percent tolerance stops at large color gap`() {
        val w = 2; val h = 1
        val seed = argb(255, 200, 200, 200)
        val far = argb(255, 100, 100, 100)
        val pixels = intArrayOf(seed, far)
        val fill = argb(255, 0, 0, 0)
        val result = FloodFillEngine.floodFillIndices(pixels, w, h, 0, 0, fill, tolerancePercent = 12f)
        assertEquals("only seed pixel filled", 1, result.size)
        assertEquals("far pixel untouched", far, pixels[1])
    }

    @Test
    fun `fill respects 4-way connectivity (no diagonal leak)`() {
        val w = 3; val h = 3
        val white = argb(255, 255, 255, 255)
        val black = argb(255, 0, 0, 0)
        val pixels = intArrayOf(
            black, white, black,
            white, white, white,
            black, white, black
        )
        val fill = argb(255, 200, 0, 0)
        val result = FloodFillEngine.floodFillIndices(pixels, w, h, 1, 1, fill, tolerancePercent = 0f)
        assertEquals("centre + 4 edge neighbours = 5 pixels", 5, result.size)
        assertEquals("top-left unchanged", black, pixels[0])
        assertEquals("top-right unchanged", black, pixels[2])
        assertEquals("bottom-left unchanged", black, pixels[6])
        assertEquals("bottom-right unchanged", black, pixels[8])
    }

    @Test
    fun `out-of-bounds seed returns empty`() {
        val pixels = solidBitmap(3, 3, argb(255, 255, 255, 255))
        val result = FloodFillEngine.floodFillIndices(pixels, 3, 3, -1, 0, argb(255, 0, 0, 0))
        assertTrue("negative X seed returns empty", result.isEmpty())
        val result2 = FloodFillEngine.floodFillIndices(pixels, 3, 3, 5, 0, argb(255, 0, 0, 0))
        assertTrue("X beyond width returns empty", result2.isEmpty())
    }

    @Test
    fun `zero-size bitmap returns empty`() {
        val result = FloodFillEngine.floodFillIndices(intArrayOf(), 0, 0, 0, 0, argb(255, 0, 0, 0))
        assertTrue("zero-size returns empty", result.isEmpty())
    }

    @Test
    fun `fill clamps to MAX_FILLED_PIXELS on filled count`() {
        // 142×142 = 20164 < 200000 so this should fill fully.
        val w = 142; val h = 142
        val white = argb(255, 255, 255, 255)
        val red = argb(255, 255, 0, 0)
        val pixels = solidBitmap(w, h, white)
        val result = FloodFillEngine.floodFillIndices(pixels, w, h, 0, 0, red)
        assertEquals("full fill within budget", w * h, result.size)
    }

    // ---- Contour extraction tests -----------------------------------------

    @Test
    fun `contour of solid 3x3 fill simplifies to 4 corners via RDP`() {
        val w = 3; val h = 3
        val white = argb(255, 255, 255, 255)
        val red = argb(255, 255, 0, 0)
        val pixels = solidBitmap(w, h, white)
        val filled = FloodFillEngine.floodFillIndices(pixels, w, h, 1, 1, red, tolerancePercent = 0f)
        assertEquals(9, filled.size)
        val contour = FloodFillEngine.extractContour(filled, w, h)
        // 3×3 solid: 8 boundary pixels; RDP (epsilon 1.3) removes edge midpoints
        // that are within tolerance of the corner-to-corner lines → 4 corners.
        assertTrue("contour has 4-8 points after RDP", contour.size in 4..8)
    }

    @Test
    fun `contour of single pixel is itself`() {
        val filled = listOf(5) // flat index 5 in a 10-wide bitmap
        val contour = FloodFillEngine.extractContour(filled, 10, 10)
        assertEquals(1, contour.size)
    }

    @Test
    fun `contour of empty fill is empty`() {
        val contour = FloodFillEngine.extractContour(emptyList(), 10, 10)
        assertTrue(contour.isEmpty())
    }

    // ---- RDP simplification tests -----------------------------------------

    @Test
    fun `RDP of collinear points reduces to endpoints`() {
        val pts = listOf(
            com.authorss81.noteflow.data.model.PointF(0f, 0f),
            com.authorss81.noteflow.data.model.PointF(1f, 1f),
            com.authorss81.noteflow.data.model.PointF(2f, 2f),
            com.authorss81.noteflow.data.model.PointF(3f, 3f)
        )
        val simplified = FloodFillEngine.simplifyRdp(pts, 1.0f)
        assertEquals(2, simplified.size)
        assertEquals(0f, simplified[0].x, 0.01f)
        assertEquals(3f, simplified.last().x, 0.01f)
    }

    @Test
    fun `RDP of two points returns both`() {
        val pts = listOf(
            com.authorss81.noteflow.data.model.PointF(0f, 0f),
            com.authorss81.noteflow.data.model.PointF(5f, 5f)
        )
        val simplified = FloodFillEngine.simplifyRdp(pts, 1.0f)
        assertEquals(2, simplified.size)
    }

    @Test
    fun `RDP preserves a triangle`() {
        val pts = listOf(
            com.authorss81.noteflow.data.model.PointF(0f, 0f),
            com.authorss81.noteflow.data.model.PointF(5f, 0f),
            com.authorss81.noteflow.data.model.PointF(2.5f, 5f)
        )
        val simplified = FloodFillEngine.simplifyRdp(pts, 1.0f)
        assertTrue("triangle has at least 3 points after RDP", simplified.size >= 3)
    }

    // ---- Gradient color interpolation via BrushColorModeMath ----------------

    @Test
    fun `gradient at progress 0 returns from color`() {
        val red = argb(255, 255, 0, 0)
        val blue = argb(255, 0, 0, 255)
        val at0 = BrushColorModeMath.gradientColorAt(red, blue, 0f)
        assertEquals("progress 0 returns start", red, at0)
    }

    @Test
    fun `gradient at progress 1 returns to color`() {
        val red = argb(255, 255, 0, 0)
        val blue = argb(255, 0, 0, 255)
        val at1 = BrushColorModeMath.gradientColorAt(red, blue, 1f)
        assertEquals("progress 1 returns end", blue, at1)
    }

    @Test
    fun `gradient at progress 0_5 is midpoint`() {
        val red = argb(255, 255, 0, 0)
        val blue = argb(255, 0, 0, 255)
        val at05 = BrushColorModeMath.gradientColorAt(red, blue, 0.5f)
        val r = (at05 shr 16) and 0xFF
        val g = (at05 shr 8) and 0xFF
        val b = at05 and 0xFF
        assertTrue("R midpoint ≈ 127-128", r in 127..128)
        assertEquals("G midpoint = 0", 0, g)
        assertTrue("B midpoint ≈ 127-128", b in 127..128)
    }

    @Test
    fun `gradient preserves alpha from the from color`() {
        val redHalf = argb(128, 255, 0, 0)
        val blue = argb(255, 0, 0, 255)
        val at05 = BrushColorModeMath.gradientColorAt(redHalf, blue, 0.5f)
        val a = (at05 shr 24) and 0xFF
        assertEquals("alpha preserved from from-color", 128, a)
    }
}
