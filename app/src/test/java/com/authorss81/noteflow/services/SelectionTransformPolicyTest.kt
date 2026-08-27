package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 226: unit tests for [SelectionTransformPolicy] — scale + rotate math
 * (a 90° square rotation swaps axis extents), size clamping, and centre
 * preservation. All tests are pure JVM (no Android framework).
 */
class SelectionTransformPolicyTest {

    private val pageStride = 1592f

    // ---- helpers -----------------------------------------------------------

    private fun squareStroke(
        id: String = "sq-1",
        cx: Float = 100f,
        cy: Float = 100f,
        width: Float = 40f,
        page: Int = 0
    ): Stroke {
        val left = cx - width / 2f
        val top = cy - width / 2f
        return Stroke(
            id = id,
            tool = StrokeTool.RECTANGLE,
            start = PointF(left, top),
            end = PointF(left + width, top + width),
            points = listOf(
                PointF(left, top),
                PointF(left + width, top),
                PointF(left + width, top + width),
                PointF(left, top + width),
                PointF(left, top)
            ),
            pdfPage = page
        )
    }

    // ---- rotation: 90° square swaps axis extents ---------------------------

    @Test
    fun `rotate 90 degrees swaps a squares x and y extents`() {
        // A 40x40 square centred at (100,100): left=80 right=120 top=80 bottom=120.
        val stroke = squareStroke()
        val rotated = SelectionTransformPolicy.rotateStroke(stroke, 100f, 100f, 90f, pageStride)

        // Rotating 90° about (100,100): the corner (80,80) should land at
        // (100 - 20, 100 + 20) = (80,120) — wait: rotateX(80,80)=100+(−20)*cos90 − (−20)*sin90
        // = 100 − (−20)(1) = 120; rotateY = 100 + (−20)(1) + (−20)(0) = 80.
        // So (80,80) → (120,80).
        assertEquals(120f, rotated.start!!.x, 0.01f)
        assertEquals(80f, rotated.start!!.y, 0.01f)

        // The opposite corner (120,120) → (80,120).
        assertEquals(80f, rotated.end!!.x, 0.01f)
        assertEquals(120f, rotated.end!!.y, 0.01f)

        // A 90° rotation about the centre is a +90° coordinate swap: the four
        // rotated corners still fill the same axis-aligned square 80..120, so
        // the recomputed centre is preserved (the square's bounds are
        // rotationally symmetric).
        val (cx, cy) = SelectionTransformPolicy.centerOf(
            StrokeSelectionActionPolicy.recomputeBounds(listOf(rotated), setOf(rotated.id))
        )
        assertEquals(100f, cx, 0.01f)
        assertEquals(100f, cy, 0.01f)
    }

    // ---- scale -------------------------------------------------------------

    @Test
    fun `scale doubles coordinates about the centre`() {
        val stroke = squareStroke()
        val scaled = SelectionTransformPolicy.scaleStroke(stroke, 100f, 100f, 2f, 2f, pageStride)
        // left=80 → 100 + (80-100)*2 = 60 ; top=80 → 60 ; right=120 → 140.
        assertEquals(60f, scaled.start!!.x, 0.01f)
        assertEquals(60f, scaled.start!!.y, 0.01f)
        assertEquals(140f, scaled.end!!.x, 0.01f)
        assertEquals(140f, scaled.end!!.y, 0.01f)
        // Each scaled point keeps the same ratio about the centre.
        val first = scaled.points.first()
        assertEquals(60f, first.x, 0.01f)
        assertEquals(60f, first.y, 0.01f)
    }

    @Test
    fun `scale preserves the centre point exactly`() {
        val stroke = squareStroke(cx = 300f, cy = 250f)
        val scaled = SelectionTransformPolicy.scaleStroke(stroke, 300f, 250f, 1.5f, 1.5f, pageStride)
        // A point at the centre stays fixed; recompute the scaled bounds centre.
        val b = StrokeSelectionActionPolicy.recomputeBounds(listOf(scaled), setOf(scaled.id))
        val (cx, cy) = SelectionTransformPolicy.centerOf(b)
        assertEquals(300f, cx, 0.01f)
        assertEquals(250f, cy, 0.01f)
    }

    @Test
    fun `non-uniform scale makes the bounds non-square`() {
        val stroke = squareStroke()
        val scaled = SelectionTransformPolicy.scaleStroke(stroke, 100f, 100f, 2f, 0.5f, pageStride)
        // Width doubles (40→80), height halves (40→20).
        val b = StrokeSelectionActionPolicy.recomputeBounds(listOf(scaled), setOf(scaled.id))
        assertEquals(80f, b.width, 0.01f)
        assertEquals(20f, b.height, 0.01f)
        assertEquals(100f, (b.left + b.right) / 2f, 0.01f)
        assertEquals(100f, (b.top + b.bottom) / 2f, 0.01f)
    }

    // ---- clamp -------------------------------------------------------------

    @Test
    fun `clampedScale keeps the scaled extent within min and max`() {
        // orig extent 1000 world px; scale 10 → 10000 must clamp to 2000.
        assertEquals(2000f, 1000f * SelectionTransformPolicy.clampedScale(10f, 1000f), 0.01f)
        // scale 0.001 → 1 px must clamp to 20 px.
        assertEquals(20f, 1000f * SelectionTransformPolicy.clampedScale(0.001f, 1000f), 0.01f)
        // in-range scale passes through.
        assertEquals(1500f, 1000f * SelectionTransformPolicy.clampedScale(1.5f, 1000f), 0.01f)
    }

    @Test
    fun `cornerScaleFromDrag clamps a too-large uniform scale`() {
        val bounds = androidx.compose.ui.geometry.Rect(0f, 0f, 100f, 100f)
        // Dragging the bottom-right corner outward a huge amount (locked) must
        // not exceed MAX_SELECTION_SIZE_PX on either axis → both capped at 2000.
        val (sx, sy) = SelectionTransformPolicy.cornerScaleFromDrag(
            corner = SelectionTransformPolicy.Corner.BOTTOM_RIGHT,
            bounds = bounds,
            dragWorldDx = 1_000_000f,
            dragWorldDy = 1_000_000f,
            locked = true
        )
        val scaledW = 100f * sx
        val scaledH = 100f * sy
        assertTrue("width must not exceed MAX", scaledW <= SelectionTransformPolicy.MAX_SELECTION_SIZE_PX + 0.01f)
        assertTrue("height must not exceed MAX", scaledH <= SelectionTransformPolicy.MAX_SELECTION_SIZE_PX + 0.01f)
        assertEquals(scaledW, scaledH, 0.01f)
    }

    @Test
    fun `cornerScaleFromDrag independent axis respects per-axis min`() {
        val bounds = androidx.compose.ui.geometry.Rect(0f, 0f, 200f, 50f)
        // Unlocked: shrink width hard, grow height. Width must not drop below 20.
        val (sx, sy) = SelectionTransformPolicy.cornerScaleFromDrag(
            corner = SelectionTransformPolicy.Corner.BOTTOM_LEFT,
            bounds = bounds,
            dragWorldDx = 1_000_000f,
            dragWorldDy = -1_000_000f,
            locked = false
        )
        assertTrue(200f * sx >= SelectionTransformPolicy.MIN_SELECTION_SIZE_PX - 0.01f)
        assertTrue(50f * sy <= SelectionTransformPolicy.MAX_SELECTION_SIZE_PX + 0.01f)
    }

    // ---- corner sign mapping ----------------------------------------------

    @Test
    fun `corner sign factors are correct`() {
        // Right corners grow on +x; left corners shrink on +x.
        assertEquals(1f, SelectionTransformPolicy.cornerSignX(SelectionTransformPolicy.Corner.TOP_RIGHT), 0f)
        assertEquals(-1f, SelectionTransformPolicy.cornerSignX(SelectionTransformPolicy.Corner.TOP_LEFT), 0f)
        // Top corners shrink on +y (downward); bottom corners grow on +y.
        assertEquals(-1f, SelectionTransformPolicy.cornerSignY(SelectionTransformPolicy.Corner.TOP_LEFT), 0f)
        assertEquals(1f, SelectionTransformPolicy.cornerSignY(SelectionTransformPolicy.Corner.BOTTOM_RIGHT), 0f)
    }

    // ---- transformSelected -------------------------------------------------

    @Test
    fun `transformSelected applies only to selected strokes`() {
        val selected = squareStroke(id = "a")
        val untouched = squareStroke(id = "b", cx = 500f, cy = 500f)
        val out = SelectionTransformPolicy.transformSelected(
            strokes = listOf(selected, untouched),
            selectedIds = setOf("a"),
            centerX = 100f,
            centerY = 100f,
            sx = 2f,
            sy = 2f,
            degrees = 90f,
            pageStride = pageStride
        )
        // 'a' moved far away (scaled about 100,100 then rotated), 'b' identical.
        val aOut = out.first { it.id == "a" }
        val bOut = out.first { it.id == "b" }
        assertEquals(untouched.start, bOut.start)
        assertEquals(untouched.points, bOut.points)
        // 'a' geometry no longer equals the original.
        assertFalse(aOut.points == selected.points)
    }

    @Test
    fun `identity transform returns strokes unchanged`() {
        val s = squareStroke()
        val input = listOf(s)
        val out = SelectionTransformPolicy.transformSelected(
            strokes = input,
            selectedIds = setOf("sq-1"),
            centerX = 100f,
            centerY = 100f,
            sx = 1f,
            sy = 1f,
            degrees = 0f,
            pageStride = pageStride
        )
        // Identity must return the SAME instances (no fake undo entry / no write).
        assertTrue(out === input)
    }

    // ---- pdfPage recompute -------------------------------------------------

    @Test
    fun `scale across a page boundary recomputes pdfPage`() {
        // A small stroke near the bottom of page 0; scale downward moves it into page 1.
        val stroke = Stroke(
            id = "p",
            tool = StrokeTool.PEN,
            points = listOf(PointF(10f, pageStride - 5f), PointF(20f, pageStride - 5f)),
            start = PointF(10f, pageStride - 5f),
            end = PointF(20f, pageStride - 5f),
            pdfPage = 0
        )
        // Scale Y by 4 about the top of page 0 (y=0): the point moves to ~pageStride*4.
        val scaled = SelectionTransformPolicy.scaleStroke(stroke, 15f, 0f, 1f, 4f, pageStride)
        // first point y: 0 + (pageStride-5)*4 = far past pageStride → page 3.
        assertEquals(3, scaled.pdfPage)
    }

    // ---- bounds helpers ----------------------------------------------------

    @Test
    fun `cornerPosition returns the four corners`() {
        val bounds = androidx.compose.ui.geometry.Rect(10f, 20f, 60f, 90f)
        assertEquals(Pair(10f, 20f), SelectionTransformPolicy.cornerPosition(bounds, SelectionTransformPolicy.Corner.TOP_LEFT))
        assertEquals(Pair(60f, 20f), SelectionTransformPolicy.cornerPosition(bounds, SelectionTransformPolicy.Corner.TOP_RIGHT))
        assertEquals(Pair(10f, 90f), SelectionTransformPolicy.cornerPosition(bounds, SelectionTransformPolicy.Corner.BOTTOM_LEFT))
        assertEquals(Pair(60f, 90f), SelectionTransformPolicy.cornerPosition(bounds, SelectionTransformPolicy.Corner.BOTTOM_RIGHT))
    }
}
