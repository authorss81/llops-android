package com.authorss81.noteflow.services

import androidx.compose.ui.geometry.Rect
import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke
import kotlin.math.max
import kotlin.math.min

/**
 * Phase 226: pure-JVM scale + rotate math for selected strokes/shapes.
 *
 * The selection is a set of [Stroke]s whose geometry lives entirely in baked
 * world-coordinate points ([Stroke.points]) and rule anchors
 * ([Stroke.start]/[Stroke.end]) — there is NO `rotationDegrees` field on a
 * stroke (and no Room schema migration this phase, per the AGENTS.md
 * major-arch-change rule). Transforming a selection therefore means re-baking
 * the geometry: scaling each point about the selection centre, and rotating
 * each point about the centre with the same rotation matrix the canvas items
 * use ([CanvasItemRotationMath]). Nothing is persisted as a new field; the
 * result is a plain new [Stroke] list handed back through a single undo entry.
 *
 * All functions are pure, allocation-light per call, and unit-testable on the
 * JVM (no Android framework dependency).
 */
object SelectionTransformPolicy {

    /** Minimum selection extent (world px) after a scale drag. */
    const val MIN_SELECTION_SIZE_PX = 20f

    /** Maximum selection extent (world px) after a scale drag. */
    const val MAX_SELECTION_SIZE_PX = 2000f

    /** Which corner of the selection bounds anchors a scale drag. */
    enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    /**
     * Horizontal scale-drag sign factor: +1 when a rightward drag GROWS the
     * selection, -1 when it SHRINKS it. Right-corner handles grow on +x, left
     * corners shrink on +x.
     */
    fun cornerSignX(corner: Corner): Float = when (corner) {
        Corner.TOP_LEFT, Corner.BOTTOM_LEFT -> -1f
        Corner.TOP_RIGHT, Corner.BOTTOM_RIGHT -> +1f
    }

    /**
     * Vertical scale-drag sign factor: +1 when a downward (+y) drag GROWS the
     * selection, -1 when it SHRINKS it. Bottom handles grow on +y, top handles
     * shrink on +y (y grows downward).
     */
    fun cornerSignY(corner: Corner): Float = when (corner) {
        Corner.TOP_LEFT, Corner.TOP_RIGHT -> -1f
        Corner.BOTTOM_LEFT, Corner.BOTTOM_RIGHT -> +1f
    }

    /**
     * Clamp one scale factor so the scaled extent stays within
     * [MIN_SELECTION_SIZE_PX]..[MAX_SELECTION_SIZE_PX]. [origSize] is the
     * pre-scale extent along that axis. Handles an out-of-range [origSize]
     * gracefully (the bounds never widen/shrink further past the cap).
     */
    fun clampedScale(scale: Float, origSize: Float): Float {
        if (origSize <= 0f) return 1f
        val low = MIN_SELECTION_SIZE_PX / origSize
        val high = MAX_SELECTION_SIZE_PX / origSize
        return scale.coerceIn(min(low, high), max(low, high))
    }

    /**
     * Clamp independent X/Y scales: each is clamped on its own axis so the
     * resulting selection stays within [MIN_SELECTION_SIZE_PX]..[MAX_SELECTION_SIZE_PX]
     * in both dimensions.
     */
    fun clampScales(scaleX: Float, scaleY: Float, bounds: Rect): Pair<Float, Float> =
        Pair(
            clampedScale(scaleX, bounds.width),
            clampedScale(scaleY, bounds.height)
        )

    /**
     * Compute the incoming scale factors from a corner drag, given the ORIGINAL
     * (pre-drag) [bounds] and the WORLD-coordinate accumulated deltas. A locked
     * (uniform) scale derives ONE factor used on both axes so aspect is kept.
     */
    fun cornerScaleFromDrag(
        corner: Corner,
        bounds: Rect,
        dragWorldDx: Float,
        dragWorldDy: Float,
        locked: Boolean
    ): Pair<Float, Float> {
        val w = if (bounds.width > 0f) bounds.width else 1f
        val h = if (bounds.height > 0f) bounds.height else 1f
        val sx = 1f + cornerSignX(corner) * dragWorldDx / w
        val sy = 1f + cornerSignY(corner) * dragWorldDy / h
        if (!locked) return clampScales(sx, sy, bounds)
        // Locked: derive one uniform factor from the average of the two raw
        // (unclamped) proposals, then clamp BOTH axes to the tightest extent so
        // the result respects min/max on each axis.
        val uniform = (sx + sy) / 2f
        val cxs = clampedScale(uniform, bounds.width)
        val cys = clampedScale(uniform, bounds.height)
        val final = min(cxs, cys)
        return Pair(final, final)
    }

    // ---- Per-stroke geometry baking ----------------------------------------

    /** Scale one [PointF] about (cx, cy). */
    fun scalePoint(p: PointF, cx: Float, cy: Float, sx: Float, sy: Float): PointF =
        p.copy(x = cx + (p.x - cx) * sx, y = cy + (p.y - cy) * sy)

    /** Rotate one [PointF] about (cx, cy) by [degrees] (same matrix as the canvas). */
    fun rotatePoint(p: PointF, cx: Float, cy: Float, degrees: Float): PointF =
        p.copy(
            x = CanvasItemRotationMath.rotateX(p.x, p.y, cx, cy, degrees),
            y = CanvasItemRotationMath.rotateY(p.x, p.y, cx, cy, degrees)
        )

    /**
     * Scale a single stroke's geometry (points + rule anchors) about (cx, cy)
     * and recompute [Stroke.pdfPage] from the new first-point Y, matching the
     * translate path ([StrokeSelectionActionPolicy.translateSelected]) so a
     * scale across a page boundary lands the stroke on the right page.
     */
    fun scaleStroke(stroke: Stroke, cx: Float, cy: Float, sx: Float, sy: Float, pageStride: Float): Stroke {
        val newPoints = stroke.points.map { scalePoint(it, cx, cy, sx, sy) }
        val newStart = stroke.start?.let { scalePoint(it, cx, cy, sx, sy) }
        val newEnd = stroke.end?.let { scalePoint(it, cx, cy, sx, sy) }
        val newY = newPoints.firstOrNull()?.y ?: newStart?.y ?: stroke.pdfPage * pageStride
        return stroke.copy(
            points = newPoints,
            start = newStart,
            end = newEnd,
            pdfPage = StrokeSelectionActionPolicy.getPageFromCanvasY(newY, pageStride)
        )
    }

    /**
     * Rotate a single stroke's geometry about (cx, cy) and recompute its page.
     */
    fun rotateStroke(stroke: Stroke, cx: Float, cy: Float, degrees: Float, pageStride: Float): Stroke {
        val newPoints = stroke.points.map { rotatePoint(it, cx, cy, degrees) }
        val newStart = stroke.start?.let { rotatePoint(it, cx, cy, degrees) }
        val newEnd = stroke.end?.let { rotatePoint(it, cx, cy, degrees) }
        val newY = newPoints.firstOrNull()?.y ?: newStart?.y ?: stroke.pdfPage * pageStride
        return stroke.copy(
            points = newPoints,
            start = newStart,
            end = newEnd,
            pdfPage = StrokeSelectionActionPolicy.getPageFromCanvasY(newY, pageStride)
        )
    }

    /**
     * Rotate then scale (or rotate-only / scale-only) EVERY selected stroke in
     * [strokes]. Identity deltas (sx==sy==1f, degrees==0f) return the input
     * unchanged — selection state is preserved so the caller's undo push stays
     * a no-op-free single entry.
     */
    fun transformSelected(
        strokes: List<Stroke>,
        selectedIds: Set<String>,
        centerX: Float,
        centerY: Float,
        sx: Float,
        sy: Float,
        degrees: Float,
        pageStride: Float
    ): List<Stroke> {
        if (selectedIds.isEmpty()) return strokes
        val needScale = sx != 1f || sy != 1f
        val needRotate = degrees != 0f
        if (!needScale && !needRotate) return strokes
        return strokes.map { stroke ->
            if (stroke.id !in selectedIds) return@map stroke
            var s = stroke
            if (needRotate) s = rotateStroke(s, centerX, centerY, degrees, pageStride)
            if (needScale) s = scaleStroke(s, centerX, centerY, sx, sy, pageStride)
            s
        }
    }

    /**
     * Rotate the four corners of [bounds] about its centre by [degrees] and
     * take the axis-aligned union — the selection overlay's mid-drag preview
     * and the resting dashed box after a rotation-only commit.
     */
    fun rotatedBounds(bounds: Rect, degrees: Float): Rect {
        if (bounds == Rect.Zero) return Rect.Zero
        val cx = (bounds.left + bounds.right) / 2f
        val cy = (bounds.top + bounds.bottom) / 2f
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        val corners = listOf(
            PointF(bounds.left, bounds.top),
            PointF(bounds.right, bounds.top),
            PointF(bounds.right, bounds.bottom),
            PointF(bounds.left, bounds.bottom)
        )
        for (c in corners) {
            val r = rotatePoint(c, cx, cy, degrees)
            minX = min(minX, r.x); maxX = max(maxX, r.x)
            minY = min(minY, r.y); maxY = max(maxY, r.y)
        }
        return Rect(minX, minY, maxX, maxY)
    }

    /**
     * Scaled bounds for the mid-drag preview: inflate [bounds] about its centre
     * by [ScaleX]/[ScaleY]. Used by the overlay (and tests) to show exactly
     * where the selection will land before the gesture commits.
     */
    fun scaledBounds(bounds: Rect, sx: Float, sy: Float): Rect {
        val cx = (bounds.left + bounds.right) / 2f
        val cy = (bounds.top + bounds.bottom) / 2f
        val w = bounds.width * sx
        val h = bounds.height * sy
        return Rect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    /**
     * A handle corner's world coordinate on [bounds].
     */
    fun cornerPosition(bounds: Rect, corner: Corner): Pair<Float, Float> = when (corner) {
        Corner.TOP_LEFT -> Pair(bounds.left, bounds.top)
        Corner.TOP_RIGHT -> Pair(bounds.right, bounds.top)
        Corner.BOTTOM_LEFT -> Pair(bounds.left, bounds.bottom)
        Corner.BOTTOM_RIGHT -> Pair(bounds.right, bounds.bottom)
    }

    /** Selection centre of [bounds]. */
    fun centerOf(bounds: Rect): Pair<Float, Float> =
        Pair((bounds.left + bounds.right) / 2f, (bounds.top + bounds.bottom) / 2f)
}
