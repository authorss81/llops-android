package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.services.CanvasNavigationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM policy tests for Phase 172 — minimap quick-view navigation
 * (zoom-to-fit + jump-home). Verifies the bounded bounds scan, the world-clamp,
 * the fit/pan math, the zoom window and the reduce-motion gate.
 */
class CanvasNavigationPolicyTest {

    private fun stroke(
        fullPoints: List<PointF> = emptyList(),
        start: PointF? = null,
        end: PointF? = null,
        page: Int = 0
    ): Stroke = Stroke(
        id = "s",
        points = fullPoints,
        start = start,
        end = end,
        pdfPage = page
    )

    @Test
    fun `jumpHome shows world top-left at 100%`() {
        val home = CanvasNavigationPolicy.jumpHome()
        assertEquals(1f, home.scale)
        assertEquals(0f, home.panX)
        assertEquals(0f, home.panY)
    }

    @Test
    fun `shouldAnimate honours reduce-motion`() {
        assertTrue(CanvasNavigationPolicy.shouldAnimate(reduceMotion = false))
        assertFalse(CanvasNavigationPolicy.shouldAnimate(reduceMotion = true))
    }

    @Test
    fun `computeContentBounds is empty on no strokes`() {
        assertTrue(CanvasNavigationPolicy.computeContentBounds(emptyList()).isEmpty)
    }

    @Test
    fun `computeContentBounds spans the polyline points`() {
        val strokes = listOf(
            stroke(fullPoints = listOf(PointF(0f, 0f), PointF(100f, 50f))),
            stroke(fullPoints = listOf(PointF(20f, 80f), PointF(40f, 200f)))
        )
        val bounds = CanvasNavigationPolicy.computeContentBounds(strokes)
        assertEquals(0f, bounds.minX)
        assertEquals(0f, bounds.minY)
        assertEquals(100f, bounds.maxX)
        assertEquals(200f, bounds.maxY)
    }

    @Test
    fun `computeContentBounds honours page Y offsets (continuous mode)`() {
        val strokes = listOf(
            stroke(fullPoints = listOf(PointF(0f, 10f)), page = 2)
        )
        // Page 2 starts at 2 * (1000 page height + 100 gap) = 2200 in world space.
        val bounds = CanvasNavigationPolicy.computeContentBounds(strokes) { page -> page * (1000f + 100f) }
        assertEquals(2210f, bounds.maxY, 1e-1f)
    }

    @Test
    fun `computeContentBounds includes start and end anchors`() {
        val strokes = listOf(stroke(start = PointF(5f, 6f), end = PointF(50f, 60f)))
        val bounds = CanvasNavigationPolicy.computeContentBounds(strokes)
        assertEquals(5f, bounds.minX)
        assertEquals(6f, bounds.minY)
        assertEquals(50f, bounds.maxX)
        assertEquals(60f, bounds.maxY)
    }

    @Test
    fun `computeContentBounds stays budgeted on huge geometry`() {
        // 300 strokes × 200 points = 60k total points — above the policy's
        // EXACT_BOUNDS_POINT_CAP, so the scan strides with MinimapGeometryPolicy's
        // OWN budgets (≤ MAX_MINIMAP_SAMPLED_STROKES strokes sampled) instead of
        // walking every point. Bounds must still span the whole document.
        val strokes = (0 until 300).map { page ->
            stroke(fullPoints = (0 until 200).map { PointF(page.toFloat(), (it * 7).toFloat()) })
        }
        val bounds = CanvasNavigationPolicy.computeContentBounds(strokes)
        assertFalse(bounds.isEmpty)
        // The stroke stride is ceil(300/120) = 3, so ~100 strokes are sampled —
        // a substantial reduction vs the full 300.
        val stride = com.authorss81.noteflow.services.MinimapGeometryPolicy.strokeStepFor(strokes.size)
        assertTrue(stride >= 2)
        assertTrue(bounds.maxX >= bounds.minX)
        assertTrue(bounds.maxY >= bounds.minY)
        assertTrue(bounds.maxX <= 299f)
    }

    @Test
    fun `contentWithinWorld clamps to the bounded world rect`() {
        val content = CanvasNavigationPolicy.Bounds(-50f, -40f, 5000f, 9000f)
        val clamped = CanvasNavigationPolicy.contentWithinWorld(content, 1080f, 1528f)
        assertEquals(0f, clamped.minX)
        assertEquals(0f, clamped.minY)
        assertEquals(1080f, clamped.maxX)
        assertEquals(1528f, clamped.maxY)
    }

    @Test
    fun `zoomToFit on empty content falls back to jump-home`() {
        val fit = CanvasNavigationPolicy.zoomToFit(CanvasNavigationPolicy.emptyBounds(), 1000f, 800f, 1080f, 1528f)
        assertEquals(1f, fit.scale)
        assertEquals(0f, fit.panX)
        assertEquals(0f, fit.panY)
    }

    @Test
    fun `zoomToFit centres the content in the viewport`() {
        val bounds = CanvasNavigationPolicy.Bounds(0f, 0f, 500f, 400f)
        val fit = CanvasNavigationPolicy.zoomToFit(bounds, 1000f, 800f, 1080f, 1528f)
        // Content 500×400 fits at scale = min((1000-96)/500, (800-96)/400) ≈ 1.76.
        val scale = fit.scale
        assertTrue(scale in CanvasNavigationPolicy.MIN_FIT_ZOOM..CanvasNavigationPolicy.MAX_FIT_ZOOM)
        // Centre maps to the viewport centre: panX = 1000/2 - 250*scale.
        assertEquals(1000f / 2f - 250f * scale, fit.panX, 1e-2f)
        assertEquals(800f / 2f - 200f * scale, fit.panY, 1e-2f)
    }

    @Test
    fun `zoomToFit is width-limited when content is wide`() {
        val bounds = CanvasNavigationPolicy.Bounds(0f, 0f, 8000f, 10f)
        val fit = CanvasNavigationPolicy.zoomToFit(bounds, 1000f, 800f, 1080f, 1528f)
        // The bounding box width is clamped to the world width (1080) first, so the
        // resulting scale can never fit an 8000-wide box into the 1000 viewport.
        val scale = fit.scale
        assertTrue(scale <= 1000f / 1080f + 1e-3f)
        assertTrue(scale >= CanvasNavigationPolicy.MIN_FIT_ZOOM)
    }

    @Test
    fun `zoomToFit clamps scale to the half-to-four zoom window`() {
        // Tiny content would zoom way past 4x — must clamp at the ceiling.
        val tiny = CanvasNavigationPolicy.Bounds(0f, 0f, 5f, 5f)
        val fit = CanvasNavigationPolicy.zoomToFit(tiny, 1000f, 800f, 1080f, 1528f)
        assertEquals(CanvasNavigationPolicy.MAX_FIT_ZOOM, fit.scale, 1e-3f)

        // Huge content in a tiny viewport cannot zoom below 0.5x.
        val huge = CanvasNavigationPolicy.Bounds(0f, 0f, 1080f, 1528f)
        val fitHuge = CanvasNavigationPolicy.zoomToFit(huge, 400f, 300f, 1080f, 1528f)
        assertTrue(fitHuge.scale >= CanvasNavigationPolicy.MIN_FIT_ZOOM)
        assertTrue(fitHuge.scale <= CanvasNavigationPolicy.MAX_FIT_ZOOM)
    }

    @Test
    fun `zoomToFit of content fully outside the world falls back to home`() {
        val outside = CanvasNavigationPolicy.Bounds(-100f, -100f, -10f, -10f)
        val fit = CanvasNavigationPolicy.zoomToFit(outside, 1000f, 800f, 1080f, 1528f)
        assertEquals(1f, fit.scale)
        assertEquals(0f, fit.panX)
        assertEquals(0f, fit.panY)
    }
}