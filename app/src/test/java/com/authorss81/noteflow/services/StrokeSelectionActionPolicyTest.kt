package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeColorMode
import com.authorss81.noteflow.data.model.StrokeTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 216: unit tests for [StrokeSelectionActionPolicy] — clipboard round-trip,
 * duplicate offset, hit-test per shape type, translate + cross-page pdfPage
 * recompute, delete, and selection bounds recomputation.
 *
 * All tests are pure JVM (no Android framework, no Robolectric).
 */
class StrokeSelectionActionPolicyTest {

    // ---- helpers -----------------------------------------------------------

    private fun pen(
        id: String = "pen-1",
        pts: List<PointF> = listOf(PointF(10f, 20f), PointF(100f, 200f)),
        width: Float = 3f,
        page: Int = 0
    ) = Stroke(
        id = id,
        tool = StrokeTool.PEN,
        points = pts,
        width = width,
        pdfPage = page
    )

    private fun rect(
        id: String = "rect-1",
        topLeft: PointF = PointF(50f, 50f),
        bottomRight: PointF = PointF(150f, 150f),
        width: Float = 3f
    ) = Stroke(
        id = id,
        tool = StrokeTool.RECTANGLE,
        start = topLeft,
        end = bottomRight,
        points = listOf(topLeft, PointF(bottomRight.x, topLeft.y), bottomRight, PointF(topLeft.x, bottomRight.y), topLeft),
        width = width
    )

    private fun ellipse(
        id: String = "ell-1",
        topLeft: PointF = PointF(50f, 50f),
        bottomRight: PointF = PointF(150f, 150f)
    ) = Stroke(
        id = id,
        tool = StrokeTool.ELLIPSE,
        start = topLeft,
        end = bottomRight,
        points = (0..36).map { i ->
            val angle = (2 * Math.PI * i / 36).toFloat()
            val cx = (topLeft.x + bottomRight.x) / 2f
            val cy = (topLeft.y + bottomRight.y) / 2f
            val rx = (bottomRight.x - topLeft.x) / 2f
            val ry = (bottomRight.y - topLeft.y) / 2f
            PointF(cx + rx * Math.cos(angle.toDouble()).toFloat(), cy + ry * Math.sin(angle.toDouble()).toFloat())
        }
    )

    private fun line(
        id: String = "line-1",
        from: PointF = PointF(0f, 0f),
        to: PointF = PointF(200f, 100f),
        width: Float = 3f
    ) = Stroke(
        id = id,
        tool = StrokeTool.LINE,
        start = from,
        end = to,
        points = listOf(from, to),
        width = width
    )

    private fun arrow(
        id: String = "arr-1",
        from: PointF = PointF(0f, 0f),
        to: PointF = PointF(200f, 0f)
    ) = Stroke(
        id = id,
        tool = StrokeTool.ARROW,
        start = from,
        end = to,
        points = listOf(from, to)
    )

    // ---- clipboard round-trip -----------------------------------------------

    @Test
    fun `clipboard round-trip preserves stroke data`() {
        val original = listOf(
            pen("p1", listOf(PointF(1f, 2f), PointF(3f, 4f))),
            pen("p2", listOf(PointF(5f, 6f)), width = 8f)
        )
        val json = StrokeSelectionActionPolicy.serializeForClipboard(original)
        val restored = StrokeSelectionActionPolicy.deserializeFromClipboard(json)
        assertEquals(2, restored.size)
        assertEquals("p1", restored[0].id)
        assertEquals(2, restored[0].points.size)
        assertEquals(1f, restored[0].points[0].x, 0f)
        assertEquals("p2", restored[1].id)
        assertEquals(8f, restored[1].width, 0f)
    }

    @Test
    fun `clipboard round-trip with empty list`() {
        val json = StrokeSelectionActionPolicy.serializeForClipboard(emptyList())
        val restored = StrokeSelectionActionPolicy.deserializeFromClipboard(json)
        assertTrue(restored.isEmpty())
    }

    @Test
    fun `clipboard deserialization fails closed on garbage`() {
        val restored = StrokeSelectionActionPolicy.deserializeFromClipboard("not-valid-json!!!")
        assertTrue(restored.isEmpty())
    }

    // ---- duplicate ---------------------------------------------------------

    @Test
    fun `duplicate produces fresh UUIDs and offset positions`() {
        val original = listOf(
            pen("original", listOf(PointF(10f, 20f), PointF(30f, 40f)))
        )
        val copies = StrokeSelectionActionPolicy.duplicateStrokes(original, zoomScale = 1f)
        assertEquals(1, copies.size)
        assertNotEquals("original", copies[0].id)
        // Offset by DUPLICATE_OFFSET_PX (12) at zoom 1
        assertEquals(10f + 12f, copies[0].points[0].x, 0.01f)
        assertEquals(20f + 12f, copies[0].points[0].y, 0.01f)
        assertEquals(30f + 12f, copies[0].points[1].x, 0.01f)
        assertEquals(40f + 12f, copies[0].points[1].y, 0.01f)
    }

    @Test
    fun `duplicate offset scales inversely with zoom`() {
        val original = listOf(pen("p", listOf(PointF(0f, 0f))))
        val copies = StrokeSelectionActionPolicy.duplicateStrokes(original, zoomScale = 2f)
        // At 2x zoom, offset should be 12/2 = 6
        assertEquals(6f, copies[0].points[0].x, 0.01f)
        assertEquals(6f, copies[0].points[0].y, 0.01f)
    }

    @Test
    fun `duplicate with empty list returns empty`() {
        assertTrue(StrokeSelectionActionPolicy.duplicateStrokes(emptyList(), 1f).isEmpty())
    }

    @Test
    fun `duplicate preserves start and end anchors`() {
        val r = rect("r1")
        val copies = StrokeSelectionActionPolicy.duplicateStrokes(listOf(r), 1f)
        assertEquals(1, copies.size)
        assertNotEquals(r.id, copies[0].id)
        assertTrue(copies[0].start != null)
        assertTrue(copies[0].end != null)
        assertEquals(50f + 12f, copies[0].start!!.x, 0.01f)
        assertEquals(150f + 12f, copies[0].end!!.x, 0.01f)
    }

    // ---- delete ------------------------------------------------------------

    @Test
    fun `delete removes selected strokes from list`() {
        val all = listOf(pen("a"), pen("b"), pen("c"))
        val result = StrokeSelectionActionPolicy.deleteSelected(all, setOf("b"))
        assertEquals(2, result.size)
        assertEquals("a", result[0].id)
        assertEquals("c", result[1].id)
    }

    @Test
    fun `delete with empty selection returns original list`() {
        val all = listOf(pen("a"), pen("b"))
        val result = StrokeSelectionActionPolicy.deleteSelected(all, emptySet())
        assertEquals(all, result)
    }

    @Test
    fun `delete with non-existent ids returns original list`() {
        val all = listOf(pen("a"), pen("b"))
        val result = StrokeSelectionActionPolicy.deleteSelected(all, setOf("z"))
        assertEquals(2, result.size)
    }

    // ---- hit-test: rectangle -----------------------------------------------

    @Test
    fun `rectangle hit test inside`() {
        val r = rect()
        assertTrue(StrokeSelectionActionPolicy.hitTestRectangle(100f, 100f, r, 12f))
    }

    @Test
    fun `rectangle hit test outside`() {
        val r = rect()
        assertFalse(StrokeSelectionActionPolicy.hitTestRectangle(0f, 0f, r, 12f))
    }

    @Test
    fun `rectangle hit test near edge within tolerance`() {
        val r = rect() // top-left 50,50 bottom-right 150,150
        // 3px outside the left edge — within tolerance of 12
        assertTrue(StrokeSelectionActionPolicy.hitTestRectangle(47f, 100f, r, 12f))
    }

    @Test
    fun `rectangle hit test near edge outside tolerance`() {
        val r = rect()
        // 20px outside the left edge — beyond tolerance of 12
        assertFalse(StrokeSelectionActionPolicy.hitTestRectangle(30f, 100f, r, 12f))
    }

    // ---- hit-test: ellipse -------------------------------------------------

    @Test
    fun `ellipse hit test inside`() {
        val e = ellipse()
        assertTrue(StrokeSelectionActionPolicy.hitTestEllipse(100f, 100f, e))
    }

    @Test
    fun `ellipse hit test outside`() {
        val e = ellipse()
        assertFalse(StrokeSelectionActionPolicy.hitTestEllipse(0f, 0f, e))
    }

    @Test
    fun `ellipse hit test near boundary within margin`() {
        val e = ellipse() // center 100,100 rx=50 ry=50
        // Point at (155, 100) — just outside the boundary, within 1.08 margin
        assertTrue(StrokeSelectionActionPolicy.hitTestEllipse(153f, 100f, e))
    }

    // ---- hit-test: line ----------------------------------------------------

    @Test
    fun `line hit test on the line`() {
        val l = line()
        // Midpoint of (0,0)→(200,100) = (100, 50)
        assertTrue(StrokeSelectionActionPolicy.hitTestLine(100f, 50f, l, 12f))
    }

    @Test
    fun `line hit test near the line within tolerance`() {
        val l = line()
        // 5px perpendicular from midpoint — within tolerance
        assertTrue(StrokeSelectionActionPolicy.hitTestLine(100f, 55f, l, 12f))
    }

    @Test
    fun `line hit test far from the line`() {
        val l = line()
        assertFalse(StrokeSelectionActionPolicy.hitTestLine(100f, 200f, l, 12f))
    }

    // ---- hit-test: arrow ---------------------------------------------------

    @Test
    fun `arrow hit test on the shaft`() {
        val a = arrow()
        assertTrue(StrokeSelectionActionPolicy.hitTestLine(100f, 0f, a, 12f))
    }

    // ---- hit-test: freehand ------------------------------------------------

    @Test
    fun `freehand hit test on a stroke point`() {
        val p = pen("p", listOf(PointF(50f, 50f), PointF(100f, 100f)))
        assertTrue(StrokeSelectionActionPolicy.hitTestFreehand(50f, 50f, p, 12f))
    }

    @Test
    fun `freehand hit test near a segment`() {
        val p = pen("p", listOf(PointF(0f, 0f), PointF(200f, 0f)))
        // 5px below the line — within tolerance
        assertTrue(StrokeSelectionActionPolicy.hitTestFreehand(100f, 5f, p, 12f))
    }

    @Test
    fun `freehand hit test far from stroke`() {
        val p = pen("p", listOf(PointF(0f, 0f), PointF(200f, 0f)))
        assertFalse(StrokeSelectionActionPolicy.hitTestFreehand(100f, 100f, p, 12f))
    }

    // ---- hit-test: dispatch ------------------------------------------------

    @Test
    fun `hitTestStroke dispatches to rectangle`() {
        val r = rect()
        assertTrue(StrokeSelectionActionPolicy.hitTestStroke(100f, 100f, r))
    }

    @Test
    fun `hitTestStroke dispatches to ellipse`() {
        val e = ellipse()
        assertTrue(StrokeSelectionActionPolicy.hitTestStroke(100f, 100f, e))
    }

    @Test
    fun `hitTestStroke dispatches to line`() {
        val l = line()
        assertTrue(StrokeSelectionActionPolicy.hitTestStroke(100f, 50f, l))
    }

    // ---- translate ---------------------------------------------------------

    @Test
    fun `translate moves selected strokes by delta`() {
        val all = listOf(
            pen("a", listOf(PointF(10f, 20f), PointF(30f, 40f))),
            pen("b", listOf(PointF(50f, 60f)))
        )
        val result = StrokeSelectionActionPolicy.translateSelected(
            all, setOf("a"), dx = 100f, dy = 50f,
            pageStride = 1592f, pageHeight = 1528f
        )
        assertEquals(110f, result[0].points[0].x, 0.01f)
        assertEquals(70f, result[0].points[0].y, 0.01f)
        assertEquals(130f, result[0].points[1].x, 0.01f)
        assertEquals(90f, result[0].points[1].y, 0.01f)
        // Stroke b is untouched
        assertEquals(50f, result[1].points[0].x, 0.01f)
        assertEquals(60f, result[1].points[0].y, 0.01f)
    }

    @Test
    fun `translate recomputes pdfPage from new Y position`() {
        // Stroke on page 0 at Y=100, translate down by pageStride (1592) to page 1
        val s = pen("s", listOf(PointF(50f, 100f)), page = 0)
        val result = StrokeSelectionActionPolicy.translateSelected(
            listOf(s), setOf("s"), dx = 0f, dy = 1592f,
            pageStride = 1592f, pageHeight = 1528f
        )
        assertEquals(1, result[0].pdfPage)
    }

    @Test
    fun `translate with empty selection returns original`() {
        val all = listOf(pen("a"))
        val result = StrokeSelectionActionPolicy.translateSelected(
            all, emptySet(), dx = 100f, dy = 100f,
            pageStride = 1592f, pageHeight = 1528f
        )
        assertEquals(all, result)
    }

    @Test
    fun `translate updates start and end anchors for shapes`() {
        val r = rect("r1")
        val result = StrokeSelectionActionPolicy.translateSelected(
            listOf(r), setOf("r1"), dx = 50f, dy = 30f,
            pageStride = 1592f, pageHeight = 1528f
        )
        assertEquals(100f, result[0].start!!.x, 0.01f)
        assertEquals(80f, result[0].start!!.y, 0.01f)
        assertEquals(200f, result[0].end!!.x, 0.01f)
        assertEquals(180f, result[0].end!!.y, 0.01f)
    }

    // ---- getPageFromCanvasY ------------------------------------------------

    @Test
    fun `pageFromCanvasY returns 0 for positive Y`() {
        assertEquals(0, StrokeSelectionActionPolicy.getPageFromCanvasY(100f, 1592f))
    }

    @Test
    fun `pageFromCanvasY returns 1 for Y at stride boundary`() {
        assertEquals(1, StrokeSelectionActionPolicy.getPageFromCanvasY(1592f, 1592f))
    }

    @Test
    fun `pageFromCanvasY returns 0 for negative Y`() {
        assertEquals(0, StrokeSelectionActionPolicy.getPageFromCanvasY(-100f, 1592f))
    }

    @Test
    fun `pageFromCanvasY returns 0 for zero stride`() {
        assertEquals(0, StrokeSelectionActionPolicy.getPageFromCanvasY(100f, 0f))
    }

    // ---- recomputeBounds ---------------------------------------------------

    @Test
    fun `recomputeBounds computes union of selected strokes`() {
        val all = listOf(
            pen("a", listOf(PointF(10f, 20f), PointF(30f, 40f))),
            pen("b", listOf(PointF(50f, 60f), PointF(100f, 200f)))
        )
        val bounds = StrokeSelectionActionPolicy.recomputeBounds(all, setOf("a", "b"))
        assertEquals(10f, bounds.left, 0.01f)
        assertEquals(20f, bounds.top, 0.01f)
        assertEquals(100f, bounds.right, 0.01f)
        assertEquals(200f, bounds.bottom, 0.01f)
    }

    @Test
    fun `recomputeBounds with empty selection returns Zero`() {
        val bounds = StrokeSelectionActionPolicy.recomputeBounds(
            listOf(pen("a")), emptySet()
        )
        assertEquals(androidx.compose.ui.geometry.Rect.Zero, bounds)
    }

    // ---- source pins -------------------------------------------------------

    @Test
    fun `source pin - StrokeSelectionActionPolicy is in services package`() {
        assertEquals(
            "com.authorss81.noteflow.services",
            StrokeSelectionActionPolicy::class.java.`package`.name
        )
    }

    @Test
    fun `source pin - CLIPBOARD_MIME is inkflow-strokes`() {
        assertEquals("inkflow-strokes", StrokeSelectionActionPolicy.CLIPBOARD_MIME)
    }

    @Test
    fun `source pin - DUPLICATE_OFFSET_PX is 12`() {
        assertEquals(12f, StrokeSelectionActionPolicy.DUPLICATE_OFFSET_PX, 0f)
    }

    @Test
    fun `source pin - LINE_HIT_TOLERANCE_PX is 12`() {
        assertEquals(12f, StrokeSelectionActionPolicy.LINE_HIT_TOLERANCE_PX, 0f)
    }
}
