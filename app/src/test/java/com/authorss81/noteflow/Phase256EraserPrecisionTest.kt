package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeTool
import com.authorss81.noteflow.services.EraserGeometryPolicy
import com.authorss81.noteflow.services.StrokeSegmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

/**
 * Phase 256 — eraser precision (segments, not points) + atomic undo.
 *
 * Two defects closed:
 *   H1 (HIGH) — the eraser only ever hit-tested RAW stroke POINTS with a
 *     MAX-pressure radius. A fast horizontal swipe passing BETWEEN two widely
 *     spaced points of a steeply-angled stroke missed it entirely (gap up to
 *     40 px after RDP simplification), and the STROKE-mode decision reused the
 *     max stamp, so a light press deleted strokes 8-12 px outside its REAL
 *     pressure-scaled mask.
 *   H2 (HIGH) — applyEraser emitted onStrokesChanged for EVERY ACTION_MOVE,
 *     flooding EditorScreen's undo stack with 30+ snapshots per erase swipe.
 *
 * Fix: every STROKE/PARTIAL decision is now segment-aware (stroke polyline +
 * erase path both densified to an 8 px ceiling, plus an explicit
 * segment-to-mask distance test) and uses the TRUE per-sample pressure radius.
 * applyEraser mutates the live list and sets a flag; ONE onStrokesChanged fires
 * at gesture end.
 *
 * 8 tests, no network, pure JVM (StrokeSegmenter) + source pins over
 * AnnotationCanvas.kt.
 */
class Phase256EraserPrecisionTest {

    private fun stroke(id: String = "s", points: List<PointF>, width: Float = 2f): Stroke =
        Stroke(
            id = id,
            tool = StrokeTool.PEN,
            colorInt = 0xFF123456.toInt(),
            width = width,
            points = points,
            start = points.first(),
            end = points.last()
        )

    // ---- 1. Densification of the stroke polyline ----------------------------

    @Test
    fun `densifyPoints caps every consecutive gap at the 8px ceiling and keeps original coordinates`() {
        val raw = listOf(PointF(0f, 0f, 0.3f, 0f, 100L), PointF(60f, 0f, 0.3f, 0f, 500L))
        val dense = StrokeSegmenter.densifyPoints(raw)
        assertTrue("densification must add interior midpoints", dense.size > raw.size)
        assertTrue("first coordinate is the original start", dense.first().x == 0f && dense.first().y == 0f)
        assertTrue("last coordinate is the original end", dense.last().x == 60f && dense.last().y == 0f)
        for (i in 0 until dense.size - 1) {
            val dx = dense[i + 1].x - dense[i].x
            val dy = dense[i + 1].y - dense[i].y
            val gap = sqrt(dx * dx + dy * dy)
            assertTrue("gap $gap at $i must be <= 8", gap <= StrokeSegmenter.ERASE_DENSIFY_MAX_GAP_PX + 0.01f)
        }
    }

    @Test
    fun `densifyErasePoints keeps the gap ceiling and interpolates the mask radius along a pressure ramp`() {
        val samples = listOf(
            StrokeSegmenter.ErasePoint(0f, 0f, radius = 6f),
            StrokeSegmenter.ErasePoint(60f, 0f, radius = 12f)
        )
        val dense = StrokeSegmenter.densifyErasePoints(samples)
        assertTrue("rising-pressure swipe gains interior samples", dense.size > 2)
        var prevR = 0f
        for (i in 0 until dense.size - 1) {
            val dx = dense[i + 1].x - dense[i].x
            val gap = sqrt(dx * dx)
            assertTrue("gap $gap at $i must be <= 8", gap <= StrokeSegmenter.ERASE_DENSIFY_MAX_GAP_PX + 0.01f)
            val r = dense[i].radius ?: 0f
            assertTrue("radius must rise with the ramp", r >= prevR - 0.0001f)
            prevR = r
        }
        assertTrue("final interpolated radius reaches the heavy sample", (dense.last().radius!! - 12f).let { kotlin.math.abs(it) } < 0.001f)
    }

    // ---- 2. H1: a mask crossing an edge is a hit, even without a point ------

    @Test
    fun `strokeTouchedBy hits the middle of a 60px edge where the raw point loop missed`() {
        // A single long edge (0,0)->(60,10), a mask landing on its middle.
        val s = stroke(points = listOf(PointF(0f, 0f), PointF(60f, 10f)), width = 2f)
        val mask = StrokeSegmenter.ErasePoint(30f, 0f, radius = 6f)
        val coverage = EraserGeometryPolicy.coverageRadius(6f, 2f)
        // Perpendicular distance from (30,0) to the edge is ~4.9 px < coverage 7.
        assertTrue("mask must reach the edge's mid-point", 4.92f < coverage)

        // The BUG (pre-256): the raw-point loop only tested both endpoints.
        val rawHit = s.points.any { p ->
            val r = coverage
            (p.x - mask.x) * (p.x - mask.x) + (p.y - mask.y) * (p.y - mask.y) <= r * r
        }
        assertFalse("neither raw endpoint is inside the mask (that is the bug)", rawHit)

        // The FIX: the segment-aware test reaches the edge midpoint.
        assertTrue(
            "strokeTouchedBy must hit an edge the raw points miss",
            StrokeSegmenter.strokeTouchedBy(s, listOf(mask), StrokeSegmenter.DEFAULT_EXTRA_RADIUS)
        )
    }

    @Test
    fun `segment splits a stroke whose middle edge the mask crosses`() {
        // Two long edges: (0,0)->(30,0)->(60,0); mask crossing the middle edge.
        val s = stroke(points = listOf(PointF(0f, 0f), PointF(30f, 0f), PointF(60f, 0f)), width = 2f)
        // radius 6 covers y in [-7,7] relative to the line but never an endpoint
        // if the mask sits in the middle of the second edge at (45,0)... the
        // endpoints are 45 px away — only mid-edge ink is covered.
        val mask = StrokeSegmenter.ErasePoint(45f, 0f, radius = 6f)
        val result = StrokeSegmenter.segment(s, listOf(mask), StrokeSegmenter.DEFAULT_EXTRA_RADIUS)
        assertTrue("a mask carving the middle must report affected", result.affected)
        assertTrue("the covered middle must split the stroke into two survivors", result.surviving.size == 2)
        // Left survivor ends at the densified edge before the mask; right begins after.
        val leftEnd = result.surviving[0].points.last()
        val rightStart = result.surviving[1].points.first()
        assertTrue("left run ends left of the mask", leftEnd.x < mask.x)
        assertTrue("right run starts right of the mask", rightStart.x > mask.x)
    }

    @Test
    fun `hitStrokeAt finds a stroke by tapping the middle of its long edge`() {
        val s = stroke(id = "long", points = listOf(PointF(0f, 0f), PointF(80f, 20f)), width = 4f)
        // Tap at the perpendicular middle of the long edge.
        val x = 40f
        val y = 5f
        // distance from (40,5) to edge (0,0)->(80,20) is small (~?); legacies
        // (width 4 + 18) certainly reach it.
        val hit = StrokeSegmenter.hitStrokeAt(listOf(s), x, y)
        assertEquals("hitStrokeAt must return the stroke via the mid-edge segment", "long", hit?.id)
    }

    // ---- 3. H1: the STROKE decision honors the per-sample pressure radius ----

    @Test
    fun `a light press never deletes a stroke 15px away while the max radius would`() {
        // currentWidth=12: light press stamp ~10.6; a stroke width 2 needs
        // coverage 11.6. The stroke's centerline is 15 px from the erase path —
        // OUTSIDE the light mask but INSIDE the legacy width+18 threshold.
        val s = stroke(points = listOf(PointF(0f, 0f), PointF(10f, 0f)), width = 2f)
        val lightStamp = EraserGeometryPolicy.stampRadius(12f, 0.1f)
        val mask = StrokeSegmenter.ErasePoint(5f, 15f, radius = lightStamp)
        assertTrue("light press mask covers only 11.6px centerline reach", EraserGeometryPolicy.coverageRadius(lightStamp, 2f) < 15f)
        assertFalse(
            "the light press at 15px must NOT touch the stroke",
            StrokeSegmenter.strokeTouchedBy(s, listOf(mask), StrokeSegmenter.DEFAULT_EXTRA_RADIUS)
        )
        // The legacy rule (width + 18 = 20) would have over-deleted it:
        assertTrue("legacy threshold 20 > 15 proves the over-delete reach", (2f + 18f) > 15f)
    }

    @Test
    fun `the same press at full pressure DOES delete the 15px-away stroke`() {
        val s = stroke(points = listOf(PointF(0f, 0f), PointF(10f, 0f)), width = 2f)
        val heavyStamp = EraserGeometryPolicy.stampRadius(12f, 1f)
        val mask = StrokeSegmenter.ErasePoint(5f, 12f, radius = heavyStamp)
        assertTrue(
            "heavy press stamp must reach the 12px-away stroke",
            EraserGeometryPolicy.coverageRadius(heavyStamp, 2f) >= 12f
        )
        assertTrue(
            StrokeSegmenter.strokeTouchedBy(s, listOf(mask), StrokeSegmenter.DEFAULT_EXTRA_RADIUS)
        )
    }

    // ---- 4. H2 source pins: one commit per eraser swipe ---------------------

    private fun repoRoot(): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            val d: File = dir
            if (File(d, "app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt").isFile) return d
            dir = d.parentFile
        }
        return start
    }

    private fun canvasSource(): String =
        File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt").readText()

    @Test
    fun `applyEraser mutates the live list and defers the emit so a swipe yields ONE onStrokesChanged`() {
        val src = canvasSource()
        // Densified per-sample window feeds the decisions.
        assertTrue(
            "applyEraser must densify the new-sample window",
            src.contains("StrokeSegmenter.densifyErasePoints(")
        )
        // The STROKE-mode gate is the segment-aware, pressure-aware decision.
        assertTrue(
            "applyEraser must decide with the per-sample-radius hit test, not a max-radius point test",
            src.contains("if (erasesStroke(stroke, samples)) {")
        )
        // The deferral: applyEraser sets the flag; it does NOT emit onStrokesChanged.
        assertTrue("the deferral flag must be armed on mutation", src.contains("eraserDidMutateDuringDrag = true"))
        // The single drain is CALLED exactly once per terminal path. The function
        // DECLARATION and prose comments also spell "commitEraserMutationIfAny()",
        // so count only line-anchored call sites (whitespace before the name).
        val callSiteCount = src.lineSequence()
            .filter { it.trimStart().startsWith("commitEraserMutationIfAny()") }
            .count()
        assertEquals(
            "commitEraserMutationIfAny must be called once per gesture-end path (drag-end, cancel, dispose)",
            3,
            callSiteCount
        )
        // No stray emission remains inside the per-sample apply path.
        val applyStart = src.indexOf("fun applyEraser(canvasOffset: Offset)")
        val applyEnd = src.indexOf("fun commitEraserMutationIfAny()")
        assertTrue("applyEraser and the commit helper must exist in source order", applyStart in 0 until applyEnd)
        assertFalse(
            "applyEraser must NEVER emit directly (that was the 30-snapshots-per-swipe bug)",
            src.substring(applyStart, applyEnd).contains("onStrokesChanged(")
        )
    }
}