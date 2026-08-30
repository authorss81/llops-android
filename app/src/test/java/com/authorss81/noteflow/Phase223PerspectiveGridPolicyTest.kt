package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeTool
import com.authorss81.noteflow.services.PerspectiveGridPolicy
import com.authorss81.noteflow.services.ShapeRecognitionHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 223 — drafting-grid geometry + canvas-rotation math.
 */
class Phase223PerspectiveGridPolicyTest {

    // --- One-point perspective vanishing math ---
    @Test
    fun onePoint_horizonAt35PercentAndVanishingAtCenter() {
        val w = 1000f
        val h = 800f
        val g = PerspectiveGridPolicy.onePoint(w, h)
        assertEquals(800f * 0.35f, g.horizonY, 0.01f)
        assertEquals(w / 2f, g.vanishingX, 0.01f)
    }

    @Test
    fun onePoint_raysAllConvergeOnVanishingPoint() {
        val g = PerspectiveGridPolicy.onePoint(1000f, 800f)
        val rays = PerspectiveGridPolicy.onePointRays(g)
        assertTrue(rays.isNotEmpty())
        for (ray in rays) {
            // Every receding ray terminates exactly at the vanishing point.
            assertEquals(g.vanishingX, ray.second.first, 0.01f)
            assertEquals(g.horizonY, ray.second.second, 0.01f)
        }
    }

    @Test
    fun onePoint_oneFromPageEdgesWithinRect() {
        val g = PerspectiveGridPolicy.onePoint(1000f, 800f)
        for (ray in PerspectiveGridPolicy.onePointRays(g)) {
            val (sx, sy) = ray.first
            assertTrue("start x $sx in [0,1000]", sx in 0f..1000f)
            assertTrue("start y $sy in [0,800]", sy in 0f..800f)
        }
    }

    // --- Two-point perspective vanishing math ---
    @Test
    fun twoPoint_vanishingPointsFlankPageHorizon() {
        val w = 1000f
        val h = 800f
        val g = PerspectiveGridPolicy.twoPoint(w, h)
        assertEquals(h * 0.35f, g.horizonY, 0.01f)
        assertTrue(g.vpLeftX < 0f)
        assertTrue(g.vpRightX > w)
        // Symmetric about the page centre.
        assertEquals(w / 2f, (g.vpLeftX + g.vpRightX) / 2f, 0.01f)
    }

    @Test
    fun clipRay_bothInside_returnsUnchanged() {
        val (a, b) = PerspectiveGridPolicy.clipRay(100f, 100f, 200f, 200f, 400f, 400f)
        assertEquals(100f, a.first, 0.01f)
        assertEquals(100f, a.second, 0.01f)
        assertEquals(200f, b.first, 0.01f)
        assertEquals(200f, b.second, 0.01f)
    }

    @Test
    fun clipRay_offPageTarget_clipsToBoundary() {
        // From (0, horizon) toward an OFF-PAGE left VP far away; the clipped
        // segment must not extend beyond the page rect.
        val g = PerspectiveGridPolicy.twoPoint(1000f, 800f)
        val ray = PerspectiveGridPolicy.clipRay(0f, g.horizonY, g.vpLeftX, g.horizonY, 1000f, 800f)
        val (bx, by) = ray.second
        assertTrue("clipped x $bx in [0,1000]", bx in 0f..1000f)
        assertTrue("clipped y $by in [0,800]", by in 0f..800f)
        // The start is on the page.
        assertEquals(0f, ray.first.first, 0.01f)
        assertEquals(g.horizonY, ray.first.second, 0.01f)
    }

    @Test
    fun twoPoint_raysStayWithinPageRect() {
        val w = 1000f
        val h = 800f
        val g = PerspectiveGridPolicy.twoPoint(w, h)
        for (ray in PerspectiveGridPolicy.twoPointRays(g)) {
            val inRect = (ray.first.first in 0f..w && ray.first.second in 0f..h) ||
                (ray.second.first in 0f..w && ray.second.second in 0f..h)
            assertTrue("ray endpoints within page rect", inRect)
        }
    }

    @Test
    fun twoPoint_raysRecedeFromBottomAndConvergeOnTheVanishingPoints() {
        val w = 1000f
        val h = 800f
        val g = PerspectiveGridPolicy.twoPoint(w, h)
        val rays = PerspectiveGridPolicy.twoPointRays(g)
        assertTrue("fan is populated", rays.isNotEmpty())
        var receding = 0
        for (ray in rays) {
            val (a, b) = ray
            val dx = b.first - a.first
            val dy = b.second - a.second
            // Phase 223 review fix: the rays must NOT be degenerate horizon-on-
            // horizon segments — every fan ray starts on the page bottom edge and
            // recedes upward toward an off-page vanishing point.
            if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 1f) {
                receding++
                assertEquals("starts on the bottom edge", h, a.second, 0.01f)
                assertTrue("recedes upward toward the horizon", dy < 0f)
                // The (clipped) segment stays collinear with one of the two VPs.
                fun cross(vpx: Float, vpy: Float): Float {
                    val e1x = b.first - a.first
                    val e1y = b.second - a.second
                    val e2x = vpx - a.first
                    val e2y = vpy - a.second
                    return e1x * e2y - e1y * e2x
                }
                val crossLeft = cross(g.vpLeftX, g.horizonY)
                val crossRight = cross(g.vpRightX, g.horizonY)
                assertTrue(
                    "ray lies on a line through a VP (left=$crossLeft right=$crossRight)",
                    kotlin.math.abs(crossLeft) < 0.5f || kotlin.math.abs(crossRight) < 0.5f
                )
            }
        }
        assertTrue("fan contains genuinely receding rays", receding > 0)
    }

    // --- Isometric math ---
    @Test
    fun isometric_default30Degrees() {
        val g = PerspectiveGridPolicy.isometric(1000f, 800f)
        assertEquals(30f, g.isoAngleDeg, 0.01f)
        assertTrue(PerspectiveGridPolicy.isometricDiagonals(g).isNotEmpty())
    }

    @Test
    fun isometric_diagonalSlopesMatch30Degrees() {
        // A ±30° diagonal has |slope| = tan(30°) ≈ 0.57735.
        val expectedSlope = kotlin.math.tan(java.lang.Math.toRadians(30.0))
        val g = PerspectiveGridPolicy.isometric(1000f, 800f)
        var verified = false
        for (l in PerspectiveGridPolicy.isometricDiagonals(g)) {
            val (a, b) = l
            val dx = b.first - a.first
            val dy = b.second - a.second
            if (kotlin.math.abs(dx) > 5f) {
                val slope = dy / dx
                if (kotlin.math.abs(slope) in 0.4f..0.8f) {
                    verified = true
                    assertTrue("slope $slope near ±tan(30°)", kotlin.math.abs(kotlin.math.abs(slope) - expectedSlope) < 0.15f)
                }
            }
        }
        assertTrue("found a diagonal with the isometric slope", verified)
    }

    // --- Depth lines are horizon-parallel ---
    @Test
    fun depthLines_areHorizontal() {
        val w = 1000f
        val h = 800f
        val g = PerspectiveGridPolicy.onePoint(w, h)
        var nonHorizontal = false
        for ((a, b) in PerspectiveGridPolicy.depthLines(w, h, g.horizonY)) {
            if (a.second != b.second) nonHorizontal = true
        }
        assertFalse("all depth lines are horizontal", nonHorizontal)
    }

    // --- Rotation matrix ---
    // (Phase 243: two-finger canvas twist removed; the rotation matrix, sanitize
    // and accumulate tests were deleted with CanvasRotationPolicy.)

    @Test
    fun stepFactor_scalesLineFamiliesWithoutBreakingGeometry() {
        val w = 1000f
        val h = 800f
        val oneG = PerspectiveGridPolicy.onePoint(w, h)
        val twoG = PerspectiveGridPolicy.twoPoint(w, h)
        // Halving/doubling the step factor roughly doubles/halves depth lines.
        val dense = PerspectiveGridPolicy.depthLines(w, h, oneG.horizonY, stepFactor = 0.5f)
        val default = PerspectiveGridPolicy.depthLines(w, h, oneG.horizonY)
        val sparse = PerspectiveGridPolicy.depthLines(w, h, oneG.horizonY, stepFactor = 2f)
        assertTrue("denser factor -> more lines", dense.size > default.size)
        assertTrue("sparser factor -> fewer lines", sparse.size < default.size)
        // Rays still converge on the vanishing point regardless of density.
        for (ray in PerspectiveGridPolicy.onePointRays(oneG, stepFactor = 2f)) {
            assertEquals(oneG.vanishingX, ray.second.first, 0.01f)
            assertEquals(oneG.horizonY, ray.second.second, 0.01f)
        }
        assertTrue(PerspectiveGridPolicy.isometricDiagonals(PerspectiveGridPolicy.isometric(w, h), stepFactor = 1.5f).isNotEmpty())
        assertTrue("two-point fan honours step factor", PerspectiveGridPolicy.twoPointRays(twoG, stepFactor = 1.5f).isNotEmpty())
    }

    // --- Ruler straight-line snap (Phase 223 Task 3) ---
    @Test
    fun forceLineSnap_collapsesWavyStrokeToExactLine() {
        val wavy = Stroke(
            id = "s1",
            tool = StrokeTool.PEN,
            points = listOf(
                PointF(10f, 90f), PointF(15f, 70f), PointF(9f, 55f),
                PointF(14f, 30f), PointF(11f, 10f)
            )
        )
        val out = ShapeRecognitionHelper.forceLineSnap(wavy)
        // No perpendicularDeviation gate: ANY freehand drag becomes a LINE.
        assertEquals(StrokeTool.LINE, out.tool)
        assertEquals(2, out.points.size)
        assertEquals(10f, out.points[0].x, 1e-3f)
        assertEquals(90f, out.points[0].y, 1e-3f)
        assertEquals(11f, out.points[1].x, 1e-3f)
        assertEquals(10f, out.points[1].y, 1e-3f)
        assertEquals(10f, out.start!!.x, 1e-3f)
        assertEquals(11f, out.end!!.x, 1e-3f)
    }

    @Test
    fun forceLineSnap_singlePointFallbackUsesStart() {
        val single = Stroke(
            id = "s2",
            tool = StrokeTool.PEN,
            start = PointF(5f, 6f),
            points = listOf(PointF(5f, 6f))
        )
        val out = ShapeRecognitionHelper.forceLineSnap(single)
        assertEquals(StrokeTool.LINE, out.tool)
        // The snap always emits the exact start+end pair; a single-point stroke
        // degenerates to two coincident points.
        assertEquals(2, out.points.size)
        assertEquals(5f, out.points[0].x, 1e-3f)
        assertEquals(6f, out.points[0].y, 1e-3f)
        assertEquals(5f, out.points[1].x, 1e-3f)
        assertEquals(6f, out.points[1].y, 1e-3f)
    }

    @Test
    fun rulerLineEligible_acceptsRealDragsRejectsTapsAndHairlines() {
        val longDrag = Stroke(
            id = "s3",
            tool = StrokeTool.PEN,
            start = PointF(10f, 10f),
            points = listOf(PointF(10f, 10f), PointF(50f, 60f))
        )
        assertTrue("a real drag is ruler-eligible", ShapeRecognitionHelper.rulerLineEligible(longDrag))
        val tap = Stroke(
            id = "s4",
            tool = StrokeTool.PEN,
            start = PointF(5f, 6f),
            points = listOf(PointF(5f, 6f))
        )
        assertFalse(
            "a tap below the min distance is NOT ruler-eligible",
            ShapeRecognitionHelper.rulerLineEligible(tap)
        )
        val hairline = Stroke(
            id = "s5",
            tool = StrokeTool.PEN,
            start = PointF(4f, 4f),
            points = listOf(PointF(4f, 4f), PointF(4.5f, 4.5f))
        )
        assertFalse(
            "a hairline jiggle is NOT ruler-eligible",
            ShapeRecognitionHelper.rulerLineEligible(hairline)
        )
    }
}
