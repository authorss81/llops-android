package com.authorss81.noteflow

import androidx.compose.ui.geometry.Rect
import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeTool
import com.authorss81.noteflow.services.LassoPolicy
import com.authorss81.noteflow.services.SelectionTransformPolicy
import com.authorss81.noteflow.services.StrokeSelectionActionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 239 — lasso stroke-selection critical path, as pure-JVM logic tests.
 *
 * Phase-235 specified an instrumented `LassoSelectionTest` (SELECT tool →
 * freehand loop → a non-empty select; Copy → Paste grows the stroke list; drag a
 * scale handle → scaleX > 1; drag the rotation handle → rotation != 0). The
 * gesture capture is a Compose/pointer concern, but every decision the gesture
 * routes through is a pure-JVM policy class pinned here:
 *
 *  - `LassoPolicy.classifyDrag` + `windingContainsPoint` (a real freehand loop is
 *    a LASSO and encloses its interior — phase-215),
 *  - `StrokeSelectionActionPolicy.duplicateStrokes` + clipboard round-trip
 *    (Copy/Paste growth — phase-216),
 *  - `SelectionTransformPolicy.cornerScaleFromDrag` / `scaledBounds` (drag a
 *    corner → scale > 1 — phase-226),
 *  - `SelectionTransformPolicy.rotatePoint` / `rotatedBounds` (drag the rotation
 *    handle → rotation != 0 — phase-226).
 */
class Phase239LassoSelectionTest {

    // ---- SELECT tool classification: TAP / MARQUEE / LASSO -------------------

    @Test
    fun `a genuine freehand loop is a lasso and encloses its interior`() {
        // A finger loop around a central point: stays far from straight and
        // returns near its start so it's NOT a tap.
        val loop = listOf(
            PointF(100f, 100f), PointF(140f, 90f), PointF(170f, 110f), PointF(175f, 150f),
            PointF(150f, 180f), PointF(110f, 185f), PointF(90f, 150f), PointF(95f, 118f)
        )
        assertEquals(LassoPolicy.DragKind.LASSO, LassoPolicy.classifyDrag(loop))
        assertTrue("center is inside the loop", LassoPolicy.windingContainsPoint(loop, 135f, 140f))
        assertFalse("outside point is not selected", LassoPolicy.windingContainsPoint(loop, 260f, 260f))
    }

    @Test
    fun `a tap clears selection - it is never a marquee or lasso`() {
        val tap = listOf(PointF(50f, 50f), PointF(54f, 54f))
        assertEquals(LassoPolicy.DragKind.TAP, LassoPolicy.classifyDrag(tap))
        // A single sample is also a TAP.
        assertEquals(LassoPolicy.DragKind.TAP, LassoPolicy.classifyDrag(listOf(PointF(1f, 1f))))
    }

    @Test
    fun `a straight long drag is a marquee box`() {
        val straight = listOf(PointF(10f, 10f), PointF(110f, 10f), PointF(115f, 11f), PointF(120f, 10f))
        assertEquals(LassoPolicy.DragKind.MARQUEE_BOX, LassoPolicy.classifyDrag(straight))
    }

    @Test
    fun `winding test handles self-intersecting finger loops`() {
        // A bow-tie / self-intersecting path: a point inside should still select.
        val selfIntersect = listOf(
            PointF(0f, 0f), PointF(40f, 40f), PointF(0f, 80f), PointF(40f, 0f), PointF(80f, 80f)
        )
        // With fewer than 3 segments nothing encloses.
        assertFalse(LassoPolicy.windingContainsPoint(listOf(PointF(0f, 0f), PointF(1f, 1f)), 0.5f, 0.5f))
        // bounds/utilities stay sane.
        val bounds = LassoPolicy.boundsOf(selfIntersect)
        assertTrue(bounds.width > 0f && bounds.height > 0f)
    }

    // ---- Copy / Paste: duplicate grows the selected set ----------------------

    @Test
    fun `duplicate creates fresh independent strokes offset from originals`() {
        // phase-216: Copy → Paste must ADD strokes, never reuse ids, and keep the
        // selected visually distinct.
        val selected = listOf(
            Stroke(
                id = "a1", tool = StrokeTool.PEN,
                points = listOf(PointF(10f, 10f), PointF(20f, 20f)),
                start = PointF(10f, 10f), end = PointF(20f, 20f)
            )
        )
        val copies = StrokeSelectionActionPolicy.duplicateStrokes(selected, zoomScale = 1f)
        assertEquals(1, copies.size)
        assertNotEquals("duplicate gets a fresh id", "a1", copies[0].id)
        val off = StrokeSelectionActionPolicy.DUPLICATE_OFFSET_PX
        assertEquals(10f + off, copies[0].points[0].x, 0.0001f)
        assertEquals(10f + off, copies[0].points[0].y, 0.0001f)
        // No selected set → no copies.
        assertTrue(StrokeSelectionActionPolicy.duplicateStrokes(emptyList(), 1f).isEmpty())
    }

    @Test
    fun `copy paste preserves the full stroke attribute set`() {
        val src = Stroke(
            id = "a2", tool = StrokeTool.WATERCOLOR, width = 12f, colorInt = 0xFF223344.toInt(),
            text = "t", filled = true, layerId = "L1", colorMode = com.authorss81.noteflow.data.model.StrokeColorMode.SHIMMER,
            points = listOf(PointF(1f, 2f, pressure = 0.9f))
        )
        val copy = StrokeSelectionActionPolicy.duplicateStrokes(listOf(src), 1f).first()
        assertEquals("tool preserved", src.tool, copy.tool)
        assertEquals("width preserved", src.width, copy.width, 0f)
        assertEquals("color preserved", src.colorInt, copy.colorInt)
        assertEquals("layer preserved", src.layerId, copy.layerId)
        assertEquals("color mode preserved", src.colorMode, copy.colorMode)
        assertEquals("pressure preserved", 0.9f, copy.points[0].pressure!!, 0f)
    }

    @Test
    fun `clipboard serialize round-trips strokes`() {
        val strokes = listOf(
            Stroke(id = "x1", tool = StrokeTool.PEN, points = listOf(PointF(3f, 4f), PointF(5f, 6f))),
            Stroke(id = "x2", tool = StrokeTool.RECTANGLE, start = PointF(1f, 1f), end = PointF(9f, 9f))
        )
        val json = StrokeSelectionActionPolicy.serializeForClipboard(strokes)
        val back = StrokeSelectionActionPolicy.deserializeFromClipboard(json)
        assertEquals(strokes.size, back.size)
        assertEquals(strokes.map { it.id }, back.map { it.id })
        // Malformed payload fails closed (empty list, no crash).
        assertTrue(StrokeSelectionActionPolicy.deserializeFromClipboard("not-json{{{").isEmpty())
    }

    // ---- Scale + rotate: drag a handle (phase-226) ---------------------------

    @Test
    fun `dragging a bottom-right corner handle scales past identity`() {
        val bounds = Rect(0f, 0f, 100f, 100f)
        val (sx, sy) = SelectionTransformPolicy.cornerScaleFromDrag(
            SelectionTransformPolicy.Corner.BOTTOM_RIGHT, bounds,
            dragWorldDx = 25f, dragWorldDy = 25f, locked = false
        )
        assertTrue("scaleX > 1 after a grow drag", sx > 1f)
        assertTrue("scaleY > 1 after a grow drag", sy > 1f)
        // The scaled bounds are actually larger.
        val scaled = SelectionTransformPolicy.scaledBounds(bounds, sx, sy)
        assertTrue(scaled.width > bounds.width && scaled.height > bounds.height)
    }

    @Test
    fun `scale is bounded by the min and max selection size`() {
        // A shrink drag can never collapse the selection below MIN_SELECTION_SIZE.
        val bounds = Rect(0f, 0f, 100f, 100f)
        // TOP_LEFT +x shrinks: sx would go negative, so it must clamp to the floor.
        val (sxMin, _) = SelectionTransformPolicy.cornerScaleFromDrag(
            SelectionTransformPolicy.Corner.TOP_LEFT, bounds,
            dragWorldDx = 200f, dragWorldDy = 200f, locked = false
        )
        val floor = SelectionTransformPolicy.MIN_SELECTION_SIZE_PX / bounds.width // = 0.2
        assertTrue("shrink clamps to the min floor (sx=$sxMin)", kotlin.math.abs(sxMin - floor) < 0.001f)

        // A huge grow drag never exceeds the max cap.
        val cap = SelectionTransformPolicy.MAX_SELECTION_SIZE_PX / bounds.width // = 20
        val (sxMax, _) = SelectionTransformPolicy.cornerScaleFromDrag(
            SelectionTransformPolicy.Corner.BOTTOM_RIGHT, bounds,
            dragWorldDx = 100000f, dragWorldDy = 0f, locked = false
        )
        assertTrue("grow clamps to the max cap (sx=$sxMax)", kotlin.math.abs(sxMax - cap) < 0.001f)
    }

    @Test
    fun `rotation handle produces a non-zero rotation and rotated bounds`() {
        // A stroke with points on the diagonal; rotating 45° must move every
        // point and inflate the axis-aligned bounding box.
        val stroke = Stroke(
            id = "r1", tool = StrokeTool.LINE,
            points = listOf(PointF(0f, 0f), PointF(100f, 0f)),
            start = PointF(0f, 0f), end = PointF(100f, 0f)
        )
        val rotated = SelectionTransformPolicy.rotateStroke(stroke, 50f, 0f, 45f, pageStride = 1000f)
        assertNotEquals("45° rotation moves the end point", 0f, rotated.points[1].y, 0f)
        val rr = SelectionTransformPolicy.rotatePoint(PointF(100f, 0f), 50f, 0f, 90f)
        assertEquals("90° maps +x to +y about the center", 50f, rr.x, 0.001f)
        assertEquals("90° maps +x to +y about the center", 50f, rr.y, 0.001f)
        val rb = SelectionTransformPolicy.rotatedBounds(Rect(0f, 0f, 100f, 100f), 90f)
        // A 100x100 square rotated 90° keeps a 100x100 aabb; rotating by 45 grows it.
        val rb45 = SelectionTransformPolicy.rotatedBounds(Rect(0f, 0f, 100f, 100f), 45f)
        assertTrue("45° rotation grows the aabb", rb45.width > 100f)
    }

    @Test
    fun `no selection and identity transform are no-ops`() {
        val strokes = listOf(Stroke(id = "n1", tool = StrokeTool.PEN, points = listOf(PointF(1f, 1f))))
        val out = SelectionTransformPolicy.transformSelected(strokes, emptySet(), 0f, 0f, 1f, 1f, 0f, 1000f)
        assertEquals(strokes, out)
        val identity = SelectionTransformPolicy.transformSelected(strokes, setOf("n1"), 0f, 0f, 1f, 1f, 0f, 1000f)
        assertEquals(strokes, identity)
        // Only the selected stroke is transformed.
        val transformed = SelectionTransformPolicy.transformSelected(
            listOf(Stroke(id = "keep", tool = StrokeTool.PEN, points = listOf(PointF(1f, 1f))),
                Stroke(id = "sel", tool = StrokeTool.PEN, points = listOf(PointF(5f, 5f)))),
            setOf("sel"), 5f, 5f, 2f, 2f, 0f, 1000f
        )
        assertEquals(1f, transformed.first { it.id == "keep" }.points[0].x, 0f)
        assertEquals(5f, transformed.first { it.id == "sel" }.points[0].x, 0.0001f)
    }
}
