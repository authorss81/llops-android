package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeTool
import com.authorss81.noteflow.services.EraserGeometryPolicy
import com.authorss81.noteflow.services.EraserMode
import com.authorss81.noteflow.services.StrokeSegmenter
import com.authorss81.noteflow.services.SymmetryHelper
import com.authorss81.noteflow.services.SymmetryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Phase 124: two eraser modes — whole-stroke delete (STROKE) & smooth
 * pressure-aware partial erase (PARTIAL).
 *
 * What this verifies:
 *  - [EraserGeometryPolicy]: the radius decision table is pure JVM and behaves
 *    monotonically under pressure, is bounded for extreme widths, and keeps the
 *    legacy fallback byte-compatible (null radius == `width + extraRadius`).
 *  - Round-mask cut geometry: after a PARTIAL carve over a synthetic erase
 *    stroke, every surviving point lies strictly OUTSIDE the round mask, and a
 *    heavier-pressure stamp carves a wider swath than a light one.
 *  - Hit-testing used by the STROKE mode + cursor highlight: [StrokeSegmenter]
 *    .hitStrokeAt returns the topmost touched stroke, honors the same symmetry
 *    mirror the canvas applies, and covers `start`/`end` anchors (shape strokes).
 */
class Phase124EraserTest {

    private fun vLine(id: String, ys: List<Float>, width: Float = 10f, x: Float = 0f): Stroke =
        Stroke(
            id = id,
            tool = StrokeTool.PEN,
            colorInt = 0xFF123456.toInt(),
            width = width,
            points = ys.map { PointF(x, it) },
            start = PointF(x, ys.first()),
            end = PointF(x, ys.last())
        )

    private fun segment(
        stroke: Stroke,
        samples: List<StrokeSegmenter.ErasePoint>,
        extraRadius: Float = StrokeSegmenter.DEFAULT_EXTRA_RADIUS
    ): StrokeSegmenter.SegmentResult =
        StrokeSegmenter.segment(stroke, samples, extraRadius)

    // ---------------------------------------------------------------- policy --

    @Test
    fun `stamp radius grows monotonically with pressure`() {
        var prev = -1f
        var p = 0f
        while (p <= 1f) {
            val r = EraserGeometryPolicy.stampRadius(12f, p)
            assertTrue("r($p)=$r must grow", r >= prev)
            prev = r
            p += 0.05f
        }
    }

    @Test
    fun `stamp radius is pressure aware and bounded`() {
        val light = EraserGeometryPolicy.stampRadius(12f, 0f)
        val heavy = EraserGeometryPolicy.stampRadius(12f, 1f)
        assertTrue("heavy press must carve wider", heavy > light)
        assertTrue("width below min is floored", EraserGeometryPolicy.stampRadius(1f, 1f) >= EraserGeometryPolicy.MIN_ERASE_WIDTH_PX)
        val wide = EraserGeometryPolicy.stampRadius(300f, 1f)
        assertTrue("extreme widths are capped", wide <= EraserGeometryPolicy.MAX_ERASE_WIDTH_PX + EraserGeometryPolicy.TAP_TOLERANCE_PX)
        // pressure is clamped, never negative growth
        val zero = EraserGeometryPolicy.stampRadius(12f, -5f)
        assertEquals(zero, EraserGeometryPolicy.stampRadius(12f, 0f), 1e-5f)
    }

    @Test
    fun `coverage radius swallows the nib half-width`() {
        val stamp = EraserGeometryPolicy.stampRadius(10f, 1f)
        assertEquals(stamp + 5f, EraserGeometryPolicy.coverageRadius(stamp, 10f), 1e-6f)
    }

    @Test
    fun `preview radius stays within the usable window`() {
        for (w in listOf(1f, 3f, 10f, 24f, 200f)) {
            val r = EraserGeometryPolicy.previewRadius(w, w)
            assertTrue("preview $r <= cap", r <= EraserGeometryPolicy.MAX_ERASE_WIDTH_PX * 2f)
            assertTrue("preview $r >= floor", r >= EraserGeometryPolicy.MIN_ERASE_WIDTH_PX)
        }
    }

    @Test
    fun `legacy radius keeps the classic rule`() {
        assertEquals(28f, EraserGeometryPolicy.legacyRadius(10f, 18f), 1e-6f)
        assertEquals(1f, EraserGeometryPolicy.legacyRadius(0f, 0f), 1e-6f)
    }

    @Test
    fun `null radius means legacy, explicit radius wins`() {
        val stroke = vLine("s", (0..10).map { it * 10f }) // y = 0..100
        // legacy coverage = width(10) + 18 = 28 -> y in [30,70] covered
        val legacy = segment(stroke, listOf(StrokeSegmenter.ErasePoint(0f, 50f, radius = null)))
        assertTrue(legacy.affected)
        assertEquals(listOf(0f, 80f), legacy.surviving.map { it.points.first().y })
        // explicit, wider stamp: coverage = 25 + width(10)/2 = 30 -> y in [20,80] covered
        val wide = segment(stroke, listOf(StrokeSegmenter.ErasePoint(0f, 50f, radius = 25f)))
        assertEquals(listOf(0f, 90f), wide.surviving.map { it.points.first().y })
    }

    // -------------------------------------------------- smooth / round cut --

    @Test
    fun `partial carve leaves only points outside the round mask`() {
        val stroke = vLine("s", (0..20).map { it * 5f }) // y = 0..100
        val stamp = StrokeSegmenter.ErasePoint(0f, 50f, radius = 20f)
        val coverage = EraserGeometryPolicy.coverageRadius(20f, stroke.width) // 25f
        val r = segment(stroke, listOf(stamp))
        assertTrue(r.affected)
        assertFalse("some points must survive", r.surviving.isEmpty())
        for (seg in r.surviving) {
            for (p in seg.points) {
                val dx = p.x - stamp.x
                val dy = p.y - stamp.y
                assertTrue(
                    "survivor (${p.x},${p.y}) must be outside the round mask (cv=$coverage)",
                    sqrt(dx * dx + dy * dy) > coverage
                )
            }
        }
    }

    @Test
    fun `heavy pressure carves a wider swath than light pressure`() {
        val baseWidth = 10f
        val lightStamp = EraserGeometryPolicy.stampRadius(baseWidth, EraserGeometryPolicy.LIGHT_PRESSURE_SCALE) // 9
        val heavyStamp = EraserGeometryPolicy.stampRadius(baseWidth, 1f) // 14
        val lightCv = EraserGeometryPolicy.coverageRadius(lightStamp, baseWidth) // 14
        val heavyCv = EraserGeometryPolicy.coverageRadius(heavyStamp, baseWidth) // 19
        // a point at a distance between the two coverages:
        val boundary = (lightCv + heavyCv) / 2f
        val stroke = vLine("s", listOf(50f - boundary, 50f, 50f + boundary))
        val light = segment(stroke, listOf(StrokeSegmenter.ErasePoint(0f, 50f, radius = lightStamp)))
        val heavy = segment(stroke, listOf(StrokeSegmenter.ErasePoint(0f, 50f, radius = heavyStamp)))
        // light: the boundary points stay -> the middle one split off -> 2 survivors
        assertEquals("light does not carve the boundary", 2, light.surviving.size)
        // heavy: all three are covered -> nothing survives
        assertTrue("heavy must swallow the boundary", heavy.surviving.isEmpty())
    }

    @Test
    fun `two radius-carrying samples merge into one smooth run split`() {
        val stroke = vLine("s", (0..20).map { it * 10f }) // y = 0..200
        val samples = listOf(
            StrokeSegmenter.ErasePoint(0f, 70f, radius = 15f),
            StrokeSegmenter.ErasePoint(0f, 90f, radius = 15f)
        )
        // coverage each = 15 + 5 = 20; stamps spaced 20 -> continuous gap y in [50,110]
        val r = segment(stroke, samples)
        assertEquals(2, r.surviving.size)
        assertEquals(40f, r.surviving[0].points.last().y)
        assertEquals(120f, r.surviving[1].points.first().y)
    }

    // --------------------------------------------------- hitStrokeAt / STROKE --

    @Test
    fun `hitStrokeAt returns topmost stroke`() {
        val bottom = vLine("b", listOf(0f, 100f), x = 0f)
        val top = vLine("t", listOf(0f, 100f), x = 0f)
        // sample at y=120: 20 from the y=100 point -> miss with a 5px margin,
        // hit with the classic extra radius (10 + 18 = 28)
        assertNull(StrokeSegmenter.hitStrokeAt(listOf(bottom, top), 0f, 120f, extraRadius = 5f))
        assertEquals("t", StrokeSegmenter.hitStrokeAt(listOf(bottom, top), 0f, 120f, extraRadius = 18f)?.id)
    }

    @Test
    fun `hitStrokeAt honors end anchors without points`() {
        val shape = Stroke(
            id = "shape",
            tool = StrokeTool.ARROW,
            width = 8f,
            start = PointF(0f, 0f),
            end = PointF(100f, 100f)
        )
        assertEquals(
            "shape",
            StrokeSegmenter.hitStrokeAt(listOf(shape), 104f, 104f)?.id
        )
        // just beyond width(8)+18 = 26 from the end anchor -> miss
        assertNull(StrokeSegmenter.hitStrokeAt(listOf(shape), 127f, 127f))
    }

    @Test
    fun `hitStrokeAt honors the symmetry mirror`() {
        val stroke = vLine("s", listOf(0f, 50f, 100f), x = 0f)
        // press on the mirrored side of a VERTICAL axis at x=40 puts the sample
        // at world x=80; the real stroke sits at x=0, so a plain hit misses
        assertNull(StrokeSegmenter.hitStrokeAt(listOf(stroke), 80f, 50f))
        assertEquals(
            "s",
            StrokeSegmenter.hitStrokeAt(
                listOf(stroke), 80f, 50f,
                symmetryMode = SymmetryMode.VERTICAL,
                symmetryCenterX = 40f, symmetryCenterY = 0f
            )?.id
        )
    }

    @Test
    fun `strokeTouchedBy covers start and end anchors`() {
        val shape = Stroke(
            id = "shape",
            tool = StrokeTool.RECTANGLE,
            width = 10f,
            start = PointF(0f, 0f),
            end = PointF(100f, 100f)
        )
        val nearStart = listOf(StrokeSegmenter.ErasePoint(5f, 5f, radius = null))
        assertTrue(StrokeSegmenter.strokeTouchedBy(shape, nearStart, StrokeSegmenter.DEFAULT_EXTRA_RADIUS))
        val nearEnd = listOf(StrokeSegmenter.ErasePoint(95f, 95f, radius = null))
        assertTrue(StrokeSegmenter.strokeTouchedBy(shape, nearEnd, StrokeSegmenter.DEFAULT_EXTRA_RADIUS))
        val far = listOf(StrokeSegmenter.ErasePoint(500f, 500f))
        assertFalse(StrokeSegmenter.strokeTouchedBy(shape, far, StrokeSegmenter.DEFAULT_EXTRA_RADIUS))
    }

    // ------------------------------------------ wiring that tests must pin --

    @Test
    fun `canvas pins radius onto erase samples`() {
        // The AnnotationCanvas now feeds stampRadius(currentWidth, pressure) into
        // each ErasePoint; make sure a radius-carrying point flows into the
        // coverage path the canvas invokes, not the legacy one.
        val stamp = EraserGeometryPolicy.stampRadius(12f, 0.8f)
        val stroke = vLine("s", (0..4).map { it * 25f }) // y = 0, 25, 50, 75, 100
        val r = segment(stroke, listOf(StrokeSegmenter.ErasePoint(0f, 50f, radius = stamp)))
        val coverage = EraserGeometryPolicy.coverageRadius(stamp, 12f)
        assertTrue("radius-carrying sample affects the stroke", r.affected)
        assertEquals(2, r.surviving.size)
        for (seg in r.surviving) {
            for (p in seg.points) {
                assertTrue(
                    "survivor must respect the pressure stamp (cv=$coverage)",
                    kotlin.math.abs(p.y - 50f) > coverage
                )
            }
        }
    }

    @Test
    fun `partial and stroke modes map to distinct counts`() {
        // STROKE hit-test selects whole strokes; PARTIAL carves the same input.
        val stroke = vLine("s", (0..10).map { it * 10f })
        // STROKE: one touch removes the whole stroke.
        assertEquals("s", StrokeSegmenter.hitStrokeAt(listOf(stroke), 0f, 50f)?.id)
        // PARTIAL: the same touch only trims the middle (coverage 15 + 5 = 20 -> y [30,70]).
        val partial = segment(
            stroke,
            listOf(StrokeSegmenter.ErasePoint(0f, 50f, radius = 15f))
        )
        assertTrue(partial.affected)
        assertTrue("partial must NOT delete the whole stroke", partial.surviving.isNotEmpty())
        assertNotEquals("s", partial.surviving[0].id)
        assertEquals(StrokeTool.PEN, partial.surviving[0].tool)
    }

    @Test
    fun `symmetry mirror double-apply round-trips`() {
        val m = SymmetryHelper.mirrorPoint(10f, 20f, SymmetryMode.VERTICAL, 40f, 0f)
        val back = SymmetryHelper.mirrorPoint(m.x, m.y, SymmetryMode.VERTICAL, 40f, 0f)
        assertEquals(10f, back.x, 1e-5f)
        assertEquals(20f, back.y, 1e-5f)
    }

    @Test
    fun `eraser mode enum still round-trips`() {
        assertEquals(EraserMode.STROKE, EraserMode.fromSettingKey("STROKE"))
        assertEquals(EraserMode.PARTIAL, EraserMode.fromSettingKey("partial"))
        assertEquals(EraserMode.STROKE, EraserMode.fromSettingKey(null))
    }
}