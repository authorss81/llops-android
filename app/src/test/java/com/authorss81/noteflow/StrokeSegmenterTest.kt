package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeTool
import com.authorss81.noteflow.services.EraserMode
import com.authorss81.noteflow.services.StrokeSegmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the Phase 19 partial-eraser segmenter. The canvas gates
 * non-polylines (text/shapes) before calling [StrokeSegmenter.segment], so here
 * we exercise the polyline splitting rules + the whole-stroke fallback.
 */
class StrokeSegmenterTest {

    private fun hLine(vararg xs: Float, width: Float = 10f): Stroke =
        Stroke(
            id = "s-${xs.joinToString("_")}",
            tool = StrokeTool.PEN,
            colorInt = 0xFF123456.toInt(),
            width = width,
            points = xs.map { PointF(it, 0f) },
            start = PointF(xs.first(), 0f),
            end = PointF(xs.last(), 0f)
        )

    private fun segment(
        stroke: Stroke,
        samples: List<Pair<Float, Float>>,
        extraRadius: Float = StrokeSegmenter.DEFAULT_EXTRA_RADIUS
    ): StrokeSegmenter.SegmentResult =
        StrokeSegmenter.segment(
            stroke = stroke,
            eraseSamples = samples.map { StrokeSegmenter.ErasePoint(it.first, it.second) },
            extraRadius = extraRadius
        )

    // ---- run-splitting ----------------------------------------------------

    @Test
    fun `uncovered stroke is returned untouched`() {
        val stroke = hLine(0f, 100f, 200f)
        val r = segment(stroke, listOf(999f to 999f))
        assertFalse("not affected", r.affected)
        assertEquals(listOf(stroke), r.surviving)
    }

    @Test
    fun `empty erase list keeps the original stroke`() {
        val stroke = hLine(0f, 100f, 200f)
        val r = segment(stroke, emptyList())
        assertFalse(r.affected)
        assertEquals(listOf(stroke), r.surviving)
    }

    @Test
    fun `middle point covered splits stroke into two segments`() {
        val stroke = hLine(0f, 100f, 200f)
        val r = segment(stroke, listOf(100f to 0f))
        assertTrue("must be affected", r.affected)
        assertEquals("two surviving runs", 2, r.surviving.size)
        assertEquals(listOf(0f), r.surviving[0].points.map { it.x })
        assertEquals(listOf(200f), r.surviving[1].points.map { it.x })
    }

    @Test
    fun `two crossings produce three segments`() {
        val stroke = hLine(0f, 60f, 120f, 180f, 240f)
        val r = segment(stroke, listOf(62f to 0f, 182f to 0f))
        assertEquals("three surviving runs", 3, r.surviving.size)
        assertEquals(listOf(0f), r.surviving[0].points.map { it.x })
        assertEquals(listOf(120f), r.surviving[1].points.map { it.x })
        assertEquals(listOf(240f), r.surviving[2].points.map { it.x })
    }

    @Test
    fun `start trim keeps only the tail`() {
        val stroke = hLine(0f, 50f, 100f)
        val r = segment(stroke, listOf(0f to 0f))
        assertEquals(1, r.surviving.size)
        assertEquals(listOf(50f, 100f), r.surviving[0].points.map { it.x })
    }

    @Test
    fun `end trim keeps only the head`() {
        val stroke = hLine(0f, 50f, 100f)
        val r = segment(stroke, listOf(100f to 0f))
        assertEquals(1, r.surviving.size)
        assertEquals(listOf(0f, 50f), r.surviving[0].points.map { it.x })
    }

    @Test
    fun `full coverage removes the whole stroke`() {
        val stroke = hLine(0f, 50f, 100f, 150f)
        val r = segment(stroke, listOf(0f to 0f, 50f to 0f, 100f to 0f, 150f to 0f))
        assertTrue(r.affected)
        assertTrue("fully erased => nothing survives", r.surviving.isEmpty())
    }

    @Test
    fun `accumulated samples from one drag trim both crossings`() {
        val stroke = hLine(0f, 50f, 100f, 150f, 200f)
        // One drag path crosses the stroke at x=48 and again at x=152; both
        // samples are passed together (the canvas accumulates across the drag).
        val r = segment(stroke, listOf(48f to 0f, 152f to 0f))
        assertEquals(3, r.surviving.size)
        assertEquals(listOf(0f), r.surviving[0].points.map { it.x })
        assertEquals(listOf(100f), r.surviving[1].points.map { it.x })
        assertEquals(listOf(200f), r.surviving[2].points.map { it.x })
    }

    // ---- radius / geometry ------------------------------------------------

    @Test
    fun `hit radius is width plus extra radius`() {
        val stroke = hLine(0f, 100f, 200f, width = 10f)
        // point at x=100 is within width + DEFAULT_EXTRA_RADIUS (28) of the sample
        val r = segment(stroke, listOf(100f to 20f), extraRadius = StrokeSegmenter.DEFAULT_EXTRA_RADIUS)
        assertTrue("20 < 28 must be a hit", r.affected)
    }

    @Test
    fun `point exactly on the radius boundary is covered`() {
        val stroke = hLine(0f, 100f, 200f, width = 10f)
        // dx=28 exactly equals width(10)+extra(18) => dx^2 <= r^2
        val r = segment(stroke, listOf(100f to 28f))
        assertTrue("boundary must count as covered", r.affected)
    }

    @Test
    fun `beyond the radius is not covered`() {
        val stroke = hLine(0f, 100f, 200f, width = 10f)
        val r = segment(stroke, listOf(100f to 40f))
        assertFalse("40 > 28 must miss", r.affected)
    }

    // ---- segment attribute fidelity ---------------------------------------

    @Test
    fun `segments copy tool color width layer and page`() {
        val stroke = Stroke(
            id = "orig",
            tool = StrokeTool.HIGHLIGHTER,
            colorInt = 0xFFABCDEF.toInt(),
            width = 22f,
            filled = true,
            points = listOf(PointF(0f, 0f), PointF(100f, 0f), PointF(200f, 0f)),
            start = PointF(0f, 0f),
            end = PointF(200f, 0f),
            pdfPage = 3,
            layerId = "layer-x"
        )
        val r = segment(stroke, listOf(100f to 0f))
        val seg = r.surviving.first()
        assertEquals(StrokeTool.HIGHLIGHTER, seg.tool)
        assertEquals(0xFFABCDEF.toInt(), seg.colorInt)
        assertEquals(22f, seg.width, 1e-6f)
        assertTrue(seg.filled)
        assertEquals(3, seg.pdfPage)
        assertEquals("layer-x", seg.layerId)
        assertEquals(seg.points.first(), seg.start)
        assertEquals(seg.points.last(), seg.end)
    }

    @Test
    fun `segments get fresh unique ids`() {
        val stroke = hLine(0f, 100f, 200f)
        val r = segment(stroke, listOf(100f to 0f))
        assertEquals(2, r.surviving.map { it.id }.toSet().size)
        assertNotEquals(stroke.id, r.surviving[0].id)
    }

    // ---- whole-stroke fallback (empty point list) -------------------------

    @Test
    fun `empty-point stroke is untouched when no sample hits its anchors`() {
        val stroke = Stroke(
            id = "shape",
            tool = StrokeTool.RECTANGLE,
            colorInt = 0xFF000000.toInt(),
            width = 10f,
            start = PointF(0f, 0f),
            end = PointF(100f, 100f)
        )
        val r = segment(stroke, listOf(999f to 999f))
        assertFalse(r.affected)
        assertEquals(listOf(stroke), r.surviving)
    }

    @Test
    fun `empty-point stroke touching a sample is removed whole`() {
        val stroke = Stroke(
            id = "shape",
            tool = StrokeTool.RECTANGLE,
            colorInt = 0xFF000000.toInt(),
            width = 10f,
            start = PointF(0f, 0f),
            end = PointF(100f, 100f)
        )
        val r = segment(stroke, listOf(10f to 10f))
        assertTrue(r.affected)
        assertTrue(r.surviving.isEmpty())
    }

    // ---- mode enum round-trip ----------------------------------------------

    @Test
    fun `eraser mode parses persisted keys and defaults to STROKE`() {
        assertEquals(EraserMode.STROKE, EraserMode.fromSettingKey("STROKE"))
        assertEquals(EraserMode.PARTIAL, EraserMode.fromSettingKey("partial"))
        assertEquals(EraserMode.STROKE, EraserMode.fromSettingKey("garbage"))
        assertEquals(EraserMode.STROKE, EraserMode.fromSettingKey(null))
    }
}
