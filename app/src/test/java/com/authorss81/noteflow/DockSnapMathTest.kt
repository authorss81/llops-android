package com.authorss81.noteflow

import com.authorss81.noteflow.services.DockSnapMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the Phase 35 floating tool dock edge-snap math.
 * Verifies nearest-edge classification, docking anchors stay on-screen, and
 * free-drag constraints keep the dock fully visible.
 */
class DockSnapMathTest {

    // ---- nearest-edge classification ----------------------------------------

    @Test
    fun `centre of screen picks start edge deterministically`() {
        assertEquals(
            DockSnapMath.DockEdge.START,
            DockSnapMath.nearestEdge(500f, 500f, 1000f, 1000f)
        )
    }

    @Test
    fun `bottom half maps to bottom`() {
        assertEquals(
            DockSnapMath.DockEdge.BOTTOM,
            DockSnapMath.nearestEdge(500f, 900f, 1000f, 1000f)
        )
    }

    @Test
    fun `right half maps to end`() {
        assertEquals(
            DockSnapMath.DockEdge.END,
            DockSnapMath.nearestEdge(900f, 100f, 1000f, 1000f)
        )
    }

    @Test
    fun `top-left corner maps to start`() {
        assertEquals(
            DockSnapMath.DockEdge.START,
            DockSnapMath.nearestEdge(50f, 50f, 1000f, 1000f)
        )
    }

    // ---- snap anchors ---------------------------------------------------------

    @Test
    fun `start anchor sits at the margin with full vertical range`() {
        val a = DockSnapMath.snap(DockSnapMath.Offset(100f, 300f), 1000f, 1000f, 16f, 156f, 48f)
        assertEquals(DockSnapMath.DockEdge.START, a.edge)
        assertEquals(16f, a.x, 1e-3f)
        assertTrue("yTop must respect 0..1 fraction range",
            a.y in 16f..(1000f - 48f - 16f + 1e-3f))
        assertTrue(a.fraction in 0f..1f)
    }

    @Test
    fun `end anchor hugs the right edge`() {
        val a = DockSnapMath.snap(DockSnapMath.Offset(900f, 400f), 1000f, 1000f, 16f, 156f, 48f)
        assertEquals(DockSnapMath.DockEdge.END, a.edge)
        assertEquals(1000f - 156f - 16f, a.x, 1e-3f)
    }

    @Test
    fun `bottom anchor stays inside and recentres vertically`() {
        val a = DockSnapMath.snap(DockSnapMath.Offset(400f, 900f), 1000f, 1000f, 16f, 156f, 48f)
        assertEquals(DockSnapMath.DockEdge.BOTTOM, a.edge)
        assertEquals(1000f - 48f - 16f, a.y, 1e-3f)
        assertTrue(a.x in 16f..(1000f - 156f - 16f + 1e-3f))
        val expectedX = 400f.coerceIn(16f, 1000f - 156f - 16f)
        assertEquals(expectedX, a.x, 1e-3f)
    }

    @Test
    fun `yTop is clamped so the whole dock remains on screen even near the top`() {
        val a = DockSnapMath.snap(DockSnapMath.Offset(10f, -50f), 800f, 1200f, 24f, 200f, 60f)
        assertEquals(24f, a.y, 1e-3f)
    }

    @Test
    fun `yTop is clamped near the bottom edge`() {
        val maxY = 1200f - 60f - 24f
        val a = DockSnapMath.snap(DockSnapMath.Offset(10f, 5000f), 800f, 1200f, 24f, 200f, 60f)
        assertEquals(maxY, a.y, 1e-3f)
    }

    @Test
    fun `fraction scales linearly with yTop`() {
        val a = DockSnapMath.snap(DockSnapMath.Offset(10f, 600f), 800f, 1000f, 20f, 160f, 40f)
        val maxY = 1000f - 40f - 20f
        val expectedFraction = ((600f - 20f) / (maxY - 20f)).coerceIn(0f, 1f)
        assertEquals(expectedFraction, a.fraction, 1e-3f)
        assertEquals(600f.coerceIn(20f, maxY), a.y, 1e-3f)
    }

    @Test
    fun `degenerate tiny screens still produce on-screen anchors`() {
        val a = DockSnapMath.snap(DockSnapMath.Offset(10f, 10f), 50f, 50f, 8f, 156f, 48f)
        assertTrue(a.x >= 0f && a.x <= 50f)
        assertTrue(a.y >= 0f && a.y <= 50f)
    }

    // ---- constrainInside -------------------------------------------------------

    @Test
    fun `constrainInside keeps the dock fully visible while dragging`() {
        val c = DockSnapMath.constrainInside(950f, 970f, 1000f, 1000f, 156f, 48f)
        assertEquals(1000f - 156f, c.x, 1e-3f)
        assertEquals(1000f - 48f, c.y, 1e-3f)
    }

    @Test
    fun `constrainInside is identity for in-bounds positions`() {
        val c = DockSnapMath.constrainInside(100f, 200f, 1000f, 1000f, 156f, 48f)
        assertEquals(100f, c.x, 1e-3f)
        assertEquals(200f, c.y, 1e-3f)
    }

    @Test
    fun `constrainInside never leaves the origin side`() {
        val c = DockSnapMath.constrainInside(-300f, -300f, 1000f, 1000f, 156f, 48f)
        assertEquals(0f, c.x, 1e-3f)
        assertEquals(0f, c.y, 1e-3f)
    }

    @Test
    fun `constrainInside tolerates a dock larger than the screen`() {
        val c = DockSnapMath.constrainInside(10f, 10f, 100f, 100f, 300f, 200f)
        assertEquals(0f, c.x, 1e-3f)
        assertEquals(0f, c.y, 1e-3f)
    }
}