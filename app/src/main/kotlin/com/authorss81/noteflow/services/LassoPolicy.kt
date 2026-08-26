package com.authorss81.noteflow.services

import androidx.compose.ui.geometry.Rect
import com.authorss81.noteflow.data.model.PointF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Phase 215: pure-JVM geometry for the lasso stroke-select gesture.
 *
 * The repurposed SELECT tool accumulates a freehand drag path in world
 * coordinates; on release this policy classifies the drag (TAP / straight-line
 * marquee box / genuine lasso) and answers point-in-polygon queries with a real
 * winding-number test (robust for the self-intersecting loops a finger draws).
 *
 * Pure JVM: no Android framework imports (compose geometry is plain Kotlin),
 * allocation-free per query, unit-testable.
 */
object LassoPolicy {

    /**
     * A drag whose end-to-end distance is at least [STRAIGHT_DRAG_RATIO] of its
     * accumulated path length is treated as an axis-free STRAIGHT drag — the
     * classic box-marquee — rather than a lasso loop.
     */
    const val STRAIGHT_DRAG_RATIO = 0.98f

    /**
     * Minimum accumulated path length before a near-straight drag may become a
     * marquee box. Shorter drags are accidental taps, never useful marquees.
     */
    const val MIN_MARQUEE_PATH_PX = 24f

    /**
     * End-to-end distance at or below which a release is classified as a TAP
     * (tap clears the selection; it never selects nothing-by-marquee).
     */
    const val TAP_PATH_PX = 12f

    /** What the user drew with one SELECT-tool drag. */
    enum class DragKind { TAP, MARQUEE_BOX, LASSO }

    /**
     * Winding-number point-in-polygon over the CLOSED polygon formed by
     * [polygon] (the closure edge last→first is implicit). Self-intersecting
     * paths behave like a true winding test (a point inside two loops has
     * winding ±2 ≠ 0 → inside); fewer than 3 points can never enclose anything.
     */
    fun windingContainsPoint(polygon: List<PointF>, x: Float, y: Float): Boolean {
        val n = polygon.size
        if (n < 3) return false
        var wn = 0
        for (i in 0 until n) {
            val v1 = polygon[i]
            val v2 = polygon[(i + 1) % n]
            if (v1.y <= y) {
                if (v2.y > y && isLeftOfEdge(v1, v2, x, y) > 0f) wn++
            } else {
                if (v2.y <= y && isLeftOfEdge(v1, v2, x, y) < 0f) wn--
            }
        }
        return wn != 0
    }

    /** Cross-product sign of [p] relative to the directed edge a→b. */
    private fun isLeftOfEdge(a: PointF, b: PointF, px: Float, py: Float): Float =
        (b.x - a.x) * (py - a.y) - (px - a.x) * (b.y - a.y)

    /**
     * Minimum distance from ([x],[y]) to the closed polygon's boundary edges,
     * or [Float.MAX_VALUE] when the polygon has fewer than 2 points.
     */
    fun distanceToPolygonEdge(polygon: List<PointF>, x: Float, y: Float): Float {
        val n = polygon.size
        if (n < 2) return Float.MAX_VALUE
        var best = Float.MAX_VALUE
        var ax = polygon[n - 1].x
        var ay = polygon[n - 1].y
        for (i in 0 until n) {
            val bx = polygon[i].x
            val by = polygon[i].y
            best = min(best, distanceToSegment(x, y, ax, ay, bx, by))
            ax = bx
            ay = by
        }
        return best
    }

    /** Standard point→segment distance (projection clamped to the segment). */
    fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 <= 0f) {
            val ex = px - ax
            val ey = py - ay
            return sqrt(ex * ex + ey * ey)
        }
        var t = ((px - ax) * dx + (py - ay) * dy) / len2
        t = t.coerceIn(0f, 1f)
        val cx = ax + t * dx
        val cy = ay + t * dy
        val ex = px - cx
        val ey = py - cy
        return sqrt(ex * ex + ey * ey)
    }

    /**
     * Axis-aligned bounding box of any point list; empty input yields
     * [Rect.Zero].
     */
    fun boundsOf(points: List<PointF>): Rect {
        if (points.isEmpty()) return Rect.Zero
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in points) {
            if (p.x.isNaN() || p.y.isNaN()) continue
            minX = min(minX, p.x)
            minY = min(minY, p.y)
            maxX = max(maxX, p.x)
            maxY = max(maxY, p.y)
        }
        if (minX > maxX || minY > maxY) return Rect.Zero
        return Rect(minX, minY, maxX, maxY)
    }

    /** Accumulated polyline length of [path]. */
    fun pathLength(path: List<PointF>): Float {
        if (path.size < 2) return 0f
        var total = 0f
        var prev = path.first()
        for (i in 1 until path.size) {
            val cur = path[i]
            val dx = cur.x - prev.x
            val dy = cur.y - prev.y
            total += sqrt(dx * dx + dy * dy)
            prev = cur
        }
        return total
    }

    fun distanceBetween(a: PointF, b: PointF): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Classify a finished SELECT drag:
     *  - fewer than 2 samples, or end-to-end ≤ [TAP_PATH_PX] → [DragKind.TAP];
     *  - a single segment, or a ≥[MIN_MARQUEE_PATH_PX] path whose direct/total
     *    length ratio reaches [STRAIGHT_DRAG_RATIO] → [DragKind.MARQUEE_BOX];
     *  - everything else is a genuine [DragKind.LASSO] loop.
     */
    fun classifyDrag(path: List<PointF>): DragKind {
        if (path.size < 2) return DragKind.TAP
        val direct = distanceBetween(path.first(), path.last())
        if (direct <= TAP_PATH_PX) return DragKind.TAP
        if (path.size == 2) return DragKind.MARQUEE_BOX
        val len = pathLength(path)
        if (len >= MIN_MARQUEE_PATH_PX && len > 0f && direct / len >= STRAIGHT_DRAG_RATIO) {
            return DragKind.MARQUEE_BOX
        }
        return DragKind.LASSO
    }
}
