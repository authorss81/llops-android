package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeTool
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 212: [ShapeRecognitionHelper] runs on EVERY freehand commit
 * (AnnotationCanvas draw-end) and may REPLACE the user's committed points with
 * snapped geometry — a regression here silently corrupts ink. The original
 * phase-03 requirement is pinned: true geometry snaps, handwriting-like noise
 * NEVER does.
 *
 * Pure JVM: geometry math only.
 */
class ShapeRecognitionHelperTest {

    // ---- fixtures -----------------------------------------------------------

    private fun stroke(points: List<PointF>, tool: StrokeTool = StrokeTool.PEN) = Stroke(
        id = "stroke-under-test",
        tool = tool,
        colorInt = 0xFF00FF00.toInt(),
        width = 7.5f,
        points = points,
        layerId = "L9",
        pdfPage = 3,
        timestampMs = 12345L
    )

    /** A straight horizontal stroke with sub-pixel jitter (a clean pen line). */
    private fun lineJitter(): List<PointF> =
        (0..11).map { i ->
            PointF(i * 9f, if (i % 2 == 0) 0.2f else -0.2f)
        }

    /** Out-and-back along the SAME line (double underline / retrace). */
    private fun retrace(): List<PointF> {
        val out = (0..10).map { PointF(it * 10f, 0f) }
        val back = (9 downTo 0).map { PointF(it * 10f, 0f) }
        return out + back
    }

    /** A uniformly-traced square perimeter, closed (the canonical rect gesture). */
    private fun square(side: Float = 120f, step: Float = 10f): List<PointF> {
        val pts = mutableListOf(PointF(0f, 0f))
        var x = 0f
        var y = 0f
        while (x < side) { x += step; pts.add(PointF(minOf(x, side), y)) }
        while (y < side) { y += step; pts.add(PointF(x, minOf(y, side))) }
        while (x > 0f) { x -= step; pts.add(PointF(maxOf(x, 0f), y)) }
        while (y > 0f) { y -= step; pts.add(PointF(x, maxOf(y, 0f))) }
        pts[pts.size - 1] = PointF(0f, 0f)
        return pts
    }

    /** A long thin rectangle (legitimate shape that must keep snapping). */
    private fun thinRect(w: Float = 200f, h: Float = 20f, step: Float = 10f): List<PointF> {
        val pts = mutableListOf(PointF(0f, 0f))
        var x = 0f
        var y = 0f
        while (x < w) { x += step; pts.add(PointF(minOf(x, w), y)) }
        while (y < h) { y += step; pts.add(PointF(x, minOf(y, h))) }
        while (x > 0f) { x -= step; pts.add(PointF(maxOf(x, 0f), y)) }
        while (y > 0f) { y -= step; pts.add(PointF(x, maxOf(y, 0f))) }
        pts[pts.size - 1] = PointF(0f, 0f)
        return pts
    }

    private fun circle(r: Float = 40f, cx: Float = 60f, cy: Float = 60f, n: Int = 24): List<PointF> =
        (0 until n).map { i ->
            val a = 2 * PI * i / n
            PointF(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat())
        }

    /** Shaft + arrowhead flick at the end: the detector's arrow gesture model. */
    private fun arrow(): List<PointF> {
        val shaft = (0..13).map { PointF(it * (100f / 13f), 0f) }
        val headLength = 25f
        val barb = PointF(100f - headLength * cos(PI / 6).toFloat(), -headLength * sin(PI / 6).toFloat())
        return shaft + listOf(barb, PointF(100f, 0f))
    }

    /** Cursive-like zigzag with vertical swings (handwriting-like noise). */
    private fun squiggle(): List<PointF> =
        (0..29).map { i ->
            PointF(
                i * 7f,
                if (i % 2 == 0) ((i * 13) % 5) * 3f else 25f + ((i * 7) % 5) * 3f
            )
        }

    // ---- LINE ---------------------------------------------------------------

    @Test
    fun `a straight freehand mark snaps to a clean two-point LINE`() {
        val snapped = ShapeRecognitionHelper.trySnapShape(stroke(lineJitter()))

        assertNotNull(snapped)
        assertEquals(ShapeRecognitionHelper.ShapeType.LINE, snapped!!.type)
        assertEquals(StrokeTool.LINE, snapped.snappedStroke.tool)
        assertEquals(2, snapped.snappedStroke.points.size)
        assertEquals(0.2f, snapped.snappedStroke.start!!.y, 0.001f)
        assertEquals(99f, snapped.snappedStroke.end!!.x, 0.001f)
    }

    @Test
    fun `snapped strokes preserve identity and style attributes`() {
        val raw = stroke(lineJitter())
        val snapped = ShapeRecognitionHelper.trySnapShape(raw)!!

        assertEquals(raw.id, snapped.snappedStroke.id)
        assertEquals(raw.colorInt, snapped.snappedStroke.colorInt)
        assertEquals(raw.width, snapped.snappedStroke.width, 0f)
        assertEquals(raw.layerId, snapped.snappedStroke.layerId)
        assertEquals(raw.pdfPage, snapped.snappedStroke.pdfPage)
        assertEquals(raw.timestampMs, snapped.snappedStroke.timestampMs)
    }

    @Test
    fun `an in-and-out retrace along one line is never replaced by a degenerate box`() {
        // Regression (phase-212 fix A): a doubled-back stroke used to satisfy
        // the rectangle perimeter heuristic (every point sits on the collapsed
        // bbox edge) and got REPLACED by a zero-height rectangle; the detector
        // now requires a non-degenerate box.
        assertNull(ShapeRecognitionHelper.trySnapShape(stroke(retrace())))
    }

    @Test
    fun `a collinear hook past the far end still straightens to a LINE`() {
        val hooked = (0..10).map { PointF(it * 10f, 0f) } + PointF(94f, 0f)
        val snapped = ShapeRecognitionHelper.trySnapShape(stroke(hooked))

        assertNotNull("direct/path = 94/106 = 0.887 > 0.82 must straighten", snapped)
        assertEquals(ShapeRecognitionHelper.ShapeType.LINE, snapped!!.type)
    }

    // ---- RECTANGLE ----------------------------------------------------------

    @Test
    fun `a traced square snaps to a crisp RECTANGLE, not an ellipse`() {
        val snapped = ShapeRecognitionHelper.trySnapShape(stroke(square()))

        assertNotNull(snapped)
        assertEquals(ShapeRecognitionHelper.ShapeType.RECTANGLE, snapped!!.type)
        assertEquals(StrokeTool.RECTANGLE, snapped.snappedStroke.tool)
        val pts = snapped.snappedStroke.points
        assertEquals(5, pts.size)
        assertEquals(PointF(0f, 0f), pts.first())
        assertEquals(PointF(0f, 0f), pts.last())
        assertEquals(PointF(120f, 120f), snapped.snappedStroke.end)
    }

    @Test
    fun `a long thin traced rectangle still snaps`() {
        val snapped = ShapeRecognitionHelper.trySnapShape(stroke(thinRect()))

        assertNotNull("thin rectangles are legitimate shapes", snapped)
        assertEquals(ShapeRecognitionHelper.ShapeType.RECTANGLE, snapped!!.type)
    }

    // ---- ELLIPSE ------------------------------------------------------------

    @Test
    fun `a circle snaps to a smooth ELLIPSE`() {
        val snapped = ShapeRecognitionHelper.trySnapShape(stroke(circle()))

        assertNotNull(snapped)
        assertEquals(ShapeRecognitionHelper.ShapeType.ELLIPSE, snapped!!.type)
        assertEquals(StrokeTool.ELLIPSE, snapped.snappedStroke.tool)
        assertEquals(37, snapped.snappedStroke.points.size)
        assertEquals(20f, snapped.snappedStroke.start!!.x, 0.01f)
        assertEquals(100f, snapped.snappedStroke.end!!.x, 0.01f)
    }

    @Test
    fun `a jittered hand-drawn circle still snaps to an ELLIPSE (no false corner evidence)`() {
        // Review fix (phase-212): the corner-evidence gate fires at >=4% of
        // points hugging BOTH bbox axes — pin that ordinary pen noise on a real
        // circle never trips it into the RECTANGLE branch.
        val noisy = (0..24).map { i ->
            val a = 2 * PI * i / 24
            val jx = if (i % 2 == 0) 1.7f else -1.7f
            val jy = if (i % 3 == 0) -1.4f else 1.6f
            PointF(
                60f + 40f * cos(a).toFloat() + jx,
                60f + 40f * sin(a).toFloat() + jy
            )
        }
        val closed = noisy + noisy.first()

        val snapped = ShapeRecognitionHelper.trySnapShape(stroke(closed))

        assertNotNull(snapped)
        assertEquals(ShapeRecognitionHelper.ShapeType.ELLIPSE, snapped!!.type)
    }

    // ---- ARROW --------------------------------------------------------------

    @Test
    fun `a straight shaft with a two-segment head snaps to an ARROW`() {
        val snapped = ShapeRecognitionHelper.trySnapShape(stroke(arrow()))

        assertNotNull(snapped)
        assertEquals(ShapeRecognitionHelper.ShapeType.ARROW, snapped!!.type)
        assertEquals(StrokeTool.ARROW, snapped.snappedStroke.tool)
        assertEquals(5, snapped.snappedStroke.points.size)
        assertEquals(PointF(0f, 0f), snapped.snappedStroke.start)
        assertEquals(PointF(100f, 0f), snapped.snappedStroke.end)
    }

    @Test
    fun `an arrow-band stroke with too few points is left alone`() {
        val shortArrow = listOf(
            PointF(0f, 0f), PointF(20f, 0f), PointF(40f, 0f),
            PointF(60f, 0f), PointF(80f, 0f), PointF(100f, 0f),
            PointF(78f, -12.5f), PointF(100f, 0f)
        )
        assertNull("9 points < the 10-point arrow floor", ShapeRecognitionHelper.trySnapShape(stroke(shortArrow)))
    }

    // ---- rejections (phase-03 requirement) ----------------------------------

    @Test
    fun `cursive-like zigzag noise is never snapped`() {
        assertNull(ShapeRecognitionHelper.trySnapShape(stroke(squiggle())))
    }

    @Test
    fun `tiny specks below the diagonal floor are ignored`() {
        assertNull(ShapeRecognitionHelper.trySnapShape(stroke(circle(r = 5f))))
    }

    @Test
    fun `strokes with fewer than six points are ignored`() {
        val five = (0..4).map { PointF(it * 20f, 0f) }
        assertNull(ShapeRecognitionHelper.trySnapShape(stroke(five)))
    }

    @Test
    fun `a wide flat scribble spanning its box but filling the interior is not a rectangle`() {
        // Perimeter trace plus three scans across the middle: interior evidence
        // must keep the perimeter ratio below the 0.72 fit threshold.
        val pts = mutableListOf<PointF>()
        pts.addAll(square(step = 15f))
        for (y in listOf(30f, 60f, 90f)) {
            pts.addAll((1..9).map { PointF(it * 12f, y) })
        }
        val snapped = ShapeRecognitionHelper.trySnapShape(stroke(pts))
        assertTrue(snapped == null || snapped.type != ShapeRecognitionHelper.ShapeType.RECTANGLE)
    }

    // ---- call-site exclusions (source pins) ---------------------------------

    private fun canvasSource(): String {
        for (path in listOf(
            "app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt",
            "src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt"
        )) {
            val f = java.io.File(path)
            if (f.isFile) return f.readText()
        }
        throw IllegalStateException("AnnotationCanvas.kt not found")
    }

    @Test
    fun `shape snap runs only for enabled non-wet non-style-preserving freehand tools`() {
        val src = canvasSource()
        assertTrue(
            "call-site gate must require the setting + freehand tool + exclusions",
            src.contains("shapeAutoSnapEnabled && tool.isFreehandTool && !isWetOrFleeting && !stylePreservingTool")
        )
    }

    @Test
    fun `wet, fleeting and style-preserving tools are excluded at the call site`() {
        val src = canvasSource()
        assertTrue(src.contains("val isWetOrFleeting = tool == StrokeTool.LASER"))
        assertTrue(src.contains("BrushStrokeMath.isWetRenderedTool(tool)"))
        for (tool in listOf("DOTTED", "NEON", "CHARCOAL", "OIL_PASTEL", "DRY_BRUSH", "PALETTE_KNIFE")) {
            assertTrue(
                "$tool must stay excluded from shape snapping",
                src.contains("tool == StrokeTool.$tool")
            )
        }
    }

    @Test
    fun `a detected shape replaces the candidate stroke at commit time`() {
        val src = canvasSource()
        assertTrue(src.contains("val newStroke = if (snappedShape != null)"))
        assertTrue(src.contains("snappedShape.snappedStroke"))
    }
}
