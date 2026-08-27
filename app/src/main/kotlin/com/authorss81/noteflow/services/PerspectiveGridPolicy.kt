package com.authorss81.noteflow.services

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Phase 223 — drafting-grid geometry. Pure JVM.
 *
 * Centralises the vanishing-point / isometric line math used both by the paper
 * template renderer (AnnotationCanvas.drawPaperTemplate) and by the Paparazzi +
 * unit tests, so the perspective and isometric line families are derived from
 * ONE audited source rather than re-inlined in the draw scope.
 *
 * All coordinates are in the PAGE/world space already passed to the draw: x in
 * [0, width], y in [0, height]. Every function returns the (start, end) pairs
 * of line segments a caller can feed straight to DrawScope.drawLine.
 */
object PerspectiveGridPolicy {

    /**
     * One-point perspective: a square floor plan whose receding edges converge
     * on a single vanishing point sitting on the horizon. The drawing overdraws
     * a set of receding (toward-the-VP) rays plus a set of horizon-parallel
     * "depth" lines, which together read as a 3D floor grid.
     */
    data class OnePointGeometry(
        val width: Float,
        val height: Float,
        val horizonY: Float,
        val vanishingX: Float
    )

    /** Horizon sits at 35% of the page height and the VP at horizontal centre. */
    fun onePoint(width: Float, height: Float): OnePointGeometry {
        val horizonY = height * HORIZON_FRACTION
        return OnePointGeometry(
            width = width,
            height = height,
            horizonY = horizonY,
            vanishingX = width / 2f
        )
    }

    /**
     * Two-point perspective: two vanishing points on the same horizon, one on
     * each side. Rays to the left point and rays to the right point fan out
     * from the horizon, plus horizon-parallel depth lines.
     */
    data class TwoPointGeometry(
        val width: Float,
        val height: Float,
        val horizonY: Float,
        val vpLeftX: Float,
        val vpRightX: Float
    )

    /** Two VPs sit 55% of the width outside each edge, on the same horizon. */
    fun twoPoint(width: Float, height: Float): TwoPointGeometry {
        val horizonY = height * HORIZON_FRACTION
        val vpOffset = width * TWO_POINT_VP_OFFSET_FRACTION
        return TwoPointGeometry(
            width = width,
            height = height,
            horizonY = horizonY,
            vpLeftX = -vpOffset,
            vpRightX = width + vpOffset
        )
    }

    /** Horizon fraction of the page height (matches the task's h*0.35). */
    const val HORIZON_FRACTION = 0.35f

    /** How far beyond each page edge the two-point vanishing points sit. */
    const val TWO_POINT_VP_OFFSET_FRACTION = 0.55f

    /**
     * Depth step between successive horizon-parallel lines, as a fraction of
     * the page width (so the grid reads denser per-unit-width on small pages).
     */
    const val DEPTH_STEP_FRACTION = 0.06f

    /**
     * Horizon-parallel depth lines for a one/two-point floor grid: they run
     * horizontally at a series of y positions below (and above) the horizon,
     * spaced by [DEPTH_STEP_FRACTION] of the width.
     */
    fun depthLines(
        width: Float,
        height: Float,
        horizonY: Float,
        stepFactor: Float = 1f
    ): List<Pair<Pair<Float, Float>, Pair<Float, Float>>> {
        val step = width * DEPTH_STEP_FRACTION * stepFactor
        if (step <= 0f) return emptyList()
        val out = mutableListOf<Pair<Pair<Float, Float>, Pair<Float, Float>>>()
        var y = horizonY
        while (y <= height) {
            out.add((0f to y) to (width to y))
            y += step
        }
        y = horizonY - step
        while (y >= 0f) {
            out.add((0f to y) to (width to y))
            y -= step
        }
        return out
    }

    /**
     * Rays that recede toward the one-point vanishing point. Each vertical
     * sample column x emits a ray from the bottom of the page to the VP,
     * producing the classic "floor boards receding to a point" fan.
     */
    fun onePointRays(g: OnePointGeometry, stepFactor: Float = 1f): List<Pair<Pair<Float, Float>, Pair<Float, Float>>> {
        val step = g.width * VERTICAL_SAMPLE_FRACTION * stepFactor
        if (step <= 0f) return emptyList()
        val out = mutableListOf<Pair<Pair<Float, Float>, Pair<Float, Float>>>()
        var x = 0f
        while (x <= g.width) {
            // From the page bottom toward the vanishing point on the horizon.
            out.add((x to g.height) to (g.vanishingX to g.horizonY))
            x += step
        }
        return out
    }

    /**
     * Two fans receding toward the left and right vanishing points. Each floor
     * line starts on the page's BOTTOM edge (like one-point's fan) and recedes
     * toward one of the two off-page vanishing points on the horizon, clipped to
     * the page rect via Liang–Barsky so no ray ever extends past the paper edge.
     * Together with the horizon-parallel depth lines this reads as the classic
     * two-point floor grid (left recedes to VPL, right recedes to VPR).
     */
    fun twoPointRays(
        g: TwoPointGeometry,
        stepFactor: Float = 1f
    ): List<Pair<Pair<Float, Float>, Pair<Float, Float>>> {
        val step = g.width * VERTICAL_SAMPLE_FRACTION * stepFactor
        if (step <= 0f) return emptyList()
        val out = mutableListOf<Pair<Pair<Float, Float>, Pair<Float, Float>>>()
        var x = 0f
        while (x <= g.width) {
            out.add(clipRay(x, g.height, g.vpLeftX, g.horizonY, g.width, g.height))
            out.add(clipRay(x, g.height, g.vpRightX, g.horizonY, g.width, g.height))
            x += step
        }
        return out
    }

    /**
     * Vertical sample density of receding rays (fraction of width between
     * neighbouring rays). Tight enough to read as a fan, sparse enough to stay
     * cheap (pure drawLine).
     */
    const val VERTICAL_SAMPLE_FRACTION = 0.045f

    /**
     * Isometric floor grid: three line families at 30° — left-diagonal
     * (down-right +30°), right-diagonal (down-left -30°) and verticals. This
     * draws the classic 30° isometric lattice.
     */
    data class IsometricGeometry(
        val width: Float,
        val height: Float,
        val isoAngleDeg: Float = 30f
    )

    fun isometric(width: Float, height: Float): IsometricGeometry =
        IsometricGeometry(width = width, height = height)

    /** cos/sin of the isometric angle (30° by default), cached for the loop. */
    val ISO_COS: Float = cos(java.lang.Math.toRadians(30.0)).toFloat()
    val ISO_SIN: Float = sin(java.lang.Math.toRadians(30.0)).toFloat()

    /**
     * Lines at ±[ISO_ANGLE] to the horizontal, spaced [ISO_STEP_FRACTION] of the
     * width apart, walking the whole page so any rotation still covers it. Each
     * diagonal family is produced by stepping along the page and emitting a line
     * of the fixed slope through each (x, y) anchor until it leaves the rect.
     */
    fun isometricDiagonals(
        g: IsometricGeometry,
        stepFactor: Float = 1f
    ): List<Pair<Pair<Float, Float>, Pair<Float, Float>>> {
        val dx = g.width * ISO_STEP_FRACTION * stepFactor
        val dy = g.height * ISO_STEP_FRACTION * stepFactor
        if (dx <= 0f || dy <= 0f) return emptyList()
        val out = mutableListOf<Pair<Pair<Float, Float>, Pair<Float, Float>>>()
        val mPos = ISO_SIN / ISO_COS // +tan(30°) slope (down-right)
        val mNeg = -ISO_SIN / ISO_COS // -tan(30°) slope (down-left)
        var anchorY = -g.height
        while (anchorY <= g.height * 2f) {
            out.add(clipSlopedLine(0f, anchorY, mNeg, g.width, g.height))
            out.add(clipSlopedLine(0f, anchorY, mPos, g.width, g.height))
            anchorY += dy
        }
        // Vertical family (part of a proper isometric 30° lattice).
        var vx = 0f
        while (vx <= g.width) {
            out.add((vx to 0f) to (vx to g.height))
            vx += dx
        }
        return out
    }

    const val ISO_STEP_FRACTION = 0.035f

    /**
     * Clip the infinite line through (originX, originY) with slope [m] to the
     * page rect [0,width]×[0,height], returning the two endpoints.
     */
    fun clipSlopedLine(
        originX: Float,
        originY: Float,
        m: Float,
        width: Float,
        height: Float
    ): Pair<Pair<Float, Float>, Pair<Float, Float>> {
        // Collect candidate boundary intersections / on-rect sample points.
        val candidates = mutableListOf<Pair<Float, Float>>()
        fun add(x: Float, y: Float) {
            if (x in 0f..width && y in 0f..height) {
                if (candidates.none { abs(it.first - x) < 0.5f && abs(it.second - y) < 0.5f }) {
                    candidates.add(x to y)
                }
            }
        }
        // Where does the line cross each page edge?
        // x = originX + t, y = originY + m*t
        // y = 0 -> t = -originY/m ; x = originX + t
        if (abs(m) > 1e-6f) {
            add(originX - originY / m, 0f)      // top edge
            add(originX + (height - originY) / m, height) // bottom edge
        }
        add(0f, originY - m * originX)          // left edge
        add(width, originY + m * (width - originX)) // right edge
        // If the anchor origin itself is inside (e.g. near-horizontal lines),
        // fall back to a horizontal span.
        if (candidates.size < 2 && originY in 0f..height) {
            add(0f, originY)
            add(width, originY)
        }
        return if (candidates.size >= 2) {
            candidates[0] to candidates[1]
        } else {
            (0f to 0f) to (0f to 0f)
        }
    }

    /** Clip a ray from a page point toward a possibly off-page target. */
    fun clipRay(
        startX: Float,
        startY: Float,
        targetX: Float,
        targetY: Float,
        width: Float,
        height: Float
    ): Pair<Pair<Float, Float>, Pair<Float, Float>> {
        // Liang–Barsky segment clip against the page rect. When both points are
        // inside the rect it returns them unchanged; when only the start is inside
        // it cuts the segment at the boundary; a fully outside segment collapses
        // to a zero-length line the caller can skip.
        var t0 = 0f
        var t1 = 1f
        val dx = targetX - startX
        val dy = targetY - startY
        fun clipTest(p: Float, q: Float): Boolean {
            if (p == 0f) return q >= 0f
            val r = q / p
            if (p < 0f) {
                if (r > 1f) return false
                if (r > t0) t0 = r
            } else {
                if (r < 0f) return false
                if (r < t1) t1 = r
            }
            return true
        }
        if (!clipTest(-dx, startX)) return (startX to startY) to (startX to startY)
        if (!clipTest(dx, width - startX)) return (startX to startY) to (startX to startY)
        if (!clipTest(-dy, startY)) return (startX to startY) to (startX to startY)
        if (!clipTest(dy, height - startY)) return (startX to startY) to (startX to startY)
        if (t1 < t0) return (startX to startY) to (startX to startY)
        val ex = startX + t1 * dx
        val ey = startY + t1 * dy
        return (startX to startY) to (ex to ey)
    }
}
