package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.services.EyedropperSamplingMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EyedropperSamplingMathTest {

    // ---- screen <-> canvas mapping -----------------------------------------

    @Test
    fun `screenToCanvas is the exact inverse of the render transform`() {
        val zoom = 2.5f
        val panX = 120f
        val panY = -40f
        val screenX = 500f
        val screenY = 310f
        val (cx, cy) = EyedropperSamplingMath.screenToCanvas(screenX, screenY, panX, panY, zoom)
        // forward transform (graphicsLayer): screen = canvas * zoom + pan
        assertEquals(screenX, cx * zoom + panX, 1e-3f)
        assertEquals(screenY, cy * zoom + panY, 1e-3f)
    }

    @Test
    fun `screenToCanvas divides by zoom not multiplies`() {
        val zoom = 3f
        val (cx, _) = EyedropperSamplingMath.screenToCanvas(300f, 0f, 0f, 0f, zoom)
        assertEquals(100f, cx, 1e-4f) // 300 / 3 = 100
    }

    @Test
    fun `zoom zero falls back to identity mapping`() {
        val (cx, cy) = EyedropperSamplingMath.screenToCanvas(50f, 25f, 10f, 5f, 0f)
        assertEquals(40f, cx, 1e-4f)
        assertEquals(20f, cy, 1e-4f)
    }

    // ---- canvas -> page pixel ---------------------------------------------

    @Test
    fun `canvasToPagePixel maps into the page bitmap at the right scale`() {
        val bmpW = 500
        val bmpH = 700
        val pageWidthPx = 1000f
        val pageHeightPx = 1400f
        val pageTopY = 300f
        val px = EyedropperSamplingMath.canvasToPagePixel(
            250f, 300f + 350f, pageTopY, pageWidthPx, pageHeightPx, bmpW, bmpH
        )
        assertEquals(125, px!!.first) // 250 / (1000/500)
        assertEquals(175, px.second) // 350 / (1400/700)
    }

    @Test
    fun `canvasToPagePixel rejects points outside the page`() {
        val px = EyedropperSamplingMath.canvasToPagePixel(
            100f, 100f, pageTopY = 300f, pageWidthPx = 1000f,
            pageHeightPx = 1400f, bitmapWidth = 500, bitmapHeight = 700
        )
        assertNull(px)
    }

    // ---- stroke hit distance -----------------------------------------------

    @Test
    fun `distanceToPolyline is zero exactly on the polyline`() {
        val pts = listOf(PointF(0f, 0f), PointF(10f, 0f))
        assertEquals(0f, EyedropperSamplingMath.distanceToPolyline(pts, 5f, 0f), 1e-4f)
    }

    @Test
    fun `distanceToPolyline returns perpendicular distance for a segment`() {
        val pts = listOf(PointF(0f, 0f), PointF(10f, 0f))
        assertEquals(3f, EyedropperSamplingMath.distanceToPolyline(pts, 4f, 3f), 1e-4f)
        assertEquals(5f, EyedropperSamplingMath.distanceToPolyline(pts, 0f, 5f), 1e-4f)
    }

    @Test
    fun `distanceToPolyline picks the closest of several segments`() {
        val pts = listOf(PointF(0f, 0f), PointF(0f, 10f), PointF(10f, 10f))
        // nearest segment is the bottom edge y=10; the perpendicular distance from (8,8) is 2
        assertEquals(2f, EyedropperSamplingMath.distanceToPolyline(pts, 8f, 8f), 1e-4f)
    }

    @Test
    fun `nearestIndex finds the closest vertex`() {
        val pts = listOf(PointF(0f, 0f), PointF(5f, 0f), PointF(10f, 0f))
        assertEquals(1, EyedropperSamplingMath.nearestIndex(pts, 5.4f, 0.5f))
        assertEquals(2, EyedropperSamplingMath.nearestIndex(pts, 9f, 0f))
    }

    // ---- source-over compositing ------------------------------------------

    @Test
    fun `opaque ink fully replaces the background`() {
        val bg = 0xFF123456.toInt()
        val ink = 0xFFFF0000.toInt()
        assertEquals(ink, EyedropperSamplingMath.composite(bg, ink))
    }

    @Test
    fun `fully transparent ink leaves the background untouched`() {
        val bg = 0xFF123456.toInt()
        val transparent = 0x00000000
        assertEquals(bg, EyedropperSamplingMath.composite(bg, transparent))
    }

    @Test
    fun `50 percent ink blends background and ink`() {
        val bg = 0xFFFF0000.toInt()
        val ink = 0x800000FF.toInt() // 50% alpha blue over opaque red background
        val blended = EyedropperSamplingMath.composite(bg, ink)
        // outA = 0.5 + 1 * (1 - 0.5) = 1 => fully opaque once ink lands on paper
        assertEquals(0xFF, EyedropperSamplingMathCompanion.alpha(blended))
        assertEquals(0x7F, EyedropperSamplingMathCompanion.red(blended))   // 255 * (1 - 128/255)
        assertEquals(0x00, EyedropperSamplingMathCompanion.green(blended))
        assertEquals(0x80, EyedropperSamplingMathCompanion.blue(blended))  // 255 * 128/255
    }

    @Test
    fun `approximate stroke alpha applies the tool multiplier`() {
        assertEquals(0.35f, EyedropperSamplingMath.approximateStrokeAlpha("HIGHLIGHTER", 1f), 1e-5f)
        assertEquals(0.82f, EyedropperSamplingMath.approximateStrokeAlpha("PENCIL", 1f), 1e-5f)
        assertEquals(1f, EyedropperSamplingMath.approximateStrokeAlpha("PEN", 1f), 1e-5f)
        assertEquals(0.21f, EyedropperSamplingMath.approximateStrokeAlpha("MARKER", 0.5f), 1e-5f)
    }
}

/** Small shim to reach the private channel helpers of EyedropperSamplingMath for assertions. */
private object EyedropperSamplingMathCompanion {
    fun alpha(argb: Int): Int = (argb ushr 24) and 0xFF
    fun red(argb: Int): Int = (argb ushr 16) and 0xFF
    fun green(argb: Int): Int = (argb ushr 8) and 0xFF
    fun blue(argb: Int): Int = argb and 0xFF
}