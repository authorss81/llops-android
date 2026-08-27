package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeTool
import kotlin.math.*

/**
 * Shape Auto-Snap & Straightening Engine (Samsung / Infinite Painter model).
 * Automatically detects rough gestures for straight lines, rectangles/squares,
 * circles/ellipses, and arrows, snapping them into crisp geometric vectors.
 */
object ShapeRecognitionHelper {

    enum class ShapeType {
        LINE, RECTANGLE, ELLIPSE, ARROW, NONE
    }

    data class SnappedShape(
        val type: ShapeType,
        val snappedStroke: Stroke
    )

    /**
     * Checks if a [Stroke] can be auto-snapped into a clean geometric shape.
     */
    fun trySnapShape(rawStroke: Stroke, snapThreshold: Float = 0.82f): SnappedShape? {
        val pts = rawStroke.points
        if (pts.size < 6) return null

        val start = pts.first()
        val end = pts.last()

        val minX = pts.minOf { it.x }
        val maxX = pts.maxOf { it.x }
        val minY = pts.minOf { it.y }
        val maxY = pts.maxOf { it.y }

        val width = maxX - minX
        val height = maxY - minY
        val boundingDiag = sqrt(width * width + height * height)

        if (boundingDiag < 15f) return null // Ignore tiny specks

        // Calculate total path length
        var pathLength = 0f
        for (i in 1 until pts.size) {
            val dx = pts[i].x - pts[i-1].x
            val dy = pts[i].y - pts[i-1].y
            pathLength += sqrt(dx * dx + dy * dy)
        }

        // Direct distance from start to end
        val directDistance = sqrt((end.x - start.x) * (end.x - start.x) + (end.y - start.y) * (end.y - start.y))

        // 1. Check Straight Line (direct distance ~ path length AND the path hugs
        //    the start→end line, so a wavy mark that merely retraces itself can't sneak in)
        if (directDistance / max(1f, pathLength) > snapThreshold &&
            perpendicularDeviation(pts, start, end) / max(1f, directDistance) < 0.10f
        ) {
            val snappedPoints = listOf(
                PointF(start.x, start.y),
                PointF(end.x, end.y)
            )
            val lineStroke = rawStroke.copy(
                tool = StrokeTool.LINE,
                start = PointF(start.x, start.y),
                end = PointF(end.x, end.y),
                points = snappedPoints
            )
            return SnappedShape(ShapeType.LINE, lineStroke)
        }

        // Distance between start and end (for closed loop shapes)
        val loopGap = sqrt((end.x - start.x) * (end.x - start.x) + (end.y - start.y) * (end.y - start.y))
        val isClosedLoop = loopGap < (boundingDiag * 0.28f)

        // 2. Check Circle / Ellipse
        if (isClosedLoop && pts.size >= 12) {
            val centerX = (minX + maxX) / 2f
            val centerY = (minY + maxY) / 2f
            val radiusX = width / 2f
            val radiusY = height / 2f

            // Variance from ellipse equation (x-h)^2/a^2 + (y-k)^2/b^2 = 1
            var totalDev = 0f
            for (p in pts) {
                val dx = (p.x - centerX) / max(1f, radiusX)
                val dy = (p.y - centerY) / max(1f, radiusY)
                val dev = abs((dx * dx + dy * dy) - 1.0f)
                totalDev += dev
            }
            val avgDev = totalDev / pts.size

            // Phase 212 fix: a traced RECTANGLE can also score a passing ellipse
            // fit, so crisp squares used to snap to smooth ELLIPSEs and were
            // replaced with the wrong geometry. An ellipse/circle only TOUCHES
            // its bounding box at four tangent regions and never approaches the
            // box CORNERS; a traced rectangle passes straight through them. When
            // the stroke shows corner evidence, let the rectangle detector
            // below decide instead.
            val cornerEvidence = hasCornerEvidence(
                pts, minX, maxX, minY, maxY, max(5f, boundingDiag * 0.06f)
            )

            if (avgDev < 0.35f && !cornerEvidence) {
                // Generate smooth ellipse points
                val ellipsePts = mutableListOf<PointF>()
                val steps = 36
                for (i in 0..steps) {
                    val angle = (2 * PI * i / steps).toFloat()
                    val px = centerX + radiusX * cos(angle)
                    val py = centerY + radiusY * sin(angle)
                    ellipsePts.add(PointF(px, py))
                }
                val ellipseStroke = rawStroke.copy(
                    tool = StrokeTool.ELLIPSE,
                    start = PointF(minX, minY),
                    end = PointF(maxX, maxY),
                    points = ellipsePts
                )
                return SnappedShape(ShapeType.ELLIPSE, ellipseStroke)
            }
        }

        // 3. Check Rectangle / Square
        //    Requires the drawing to actually trace the perimeter (a sloppy loop
        //    that merely spans the bounding box has interior points and must NOT
        //    snap to a rectangle).
        if (isClosedLoop && pts.size >= 10) {
            val perimeterMargin = max(5f, boundingDiag * 0.06f)
            val trackedPerimeter = perimeterFitRatio(pts, minX, maxX, minY, maxY, perimeterMargin)
            // Phase 212 fix: a stroke that doubles back along ONE line (e.g. an
            // underlined-twice mark) satisfies the perimeter heuristic because
            // every point sits on the collapsed bounding-box edge — it used to
            // be REPLACED by a zero-height rectangle. A real rectangle spans a
            // non-degenerate box in BOTH dimensions.
            val nonDegenerateBox =
                minOf(width, height) >= max(4f, boundingDiag * 0.04f)
            if (trackedPerimeter >= 0.72f && nonDegenerateBox) {
                val rectPts = listOf(
                    PointF(minX, minY),
                    PointF(maxX, minY),
                    PointF(maxX, maxY),
                    PointF(minX, maxY),
                    PointF(minX, minY)
                )
                val rectStroke = rawStroke.copy(
                    tool = StrokeTool.RECTANGLE,
                    start = PointF(minX, minY),
                    end = PointF(maxX, maxY),
                    points = rectPts
                )
                return SnappedShape(ShapeType.RECTANGLE, rectStroke)
            }
        }

        // 4. Check Arrow (Straight shaft + arrowhead at end).
        //    A real arrow has a fairly straight shaft while its two-segment head
        //    adds enough path length to fall BELOW the LINE snap threshold, and the
        //    whole stroke hugs the shaft line. Tight band + low deviation means a
        //    plain straight-freehand mark (dash, underline) is NOT turned into an arrow.
        if (pts.size >= 10 &&
            (directDistance / max(1f, pathLength)) in 0.55f..0.82f &&
            perpendicularDeviation(pts, start, end) / max(1f, directDistance) < 0.12f
        ) {
            val angle = atan2(end.y - start.y, end.x - start.x)
            val headLength = min(35f, max(15f, directDistance * 0.2f))
            val headAngle = PI.toFloat() / 6f // 30 degrees

            val p1x = end.x - headLength * cos(angle - headAngle)
            val p1y = end.y - headLength * sin(angle - headAngle)
            val p2x = end.x - headLength * cos(angle + headAngle)
            val p2y = end.y - headLength * sin(angle + headAngle)

            val arrowPts = listOf(
                PointF(start.x, start.y),
                PointF(end.x, end.y),
                PointF(p1x, p1y),
                PointF(end.x, end.y),
                PointF(p2x, p2y)
            )
            val arrowStroke = rawStroke.copy(
                tool = StrokeTool.ARROW,
                start = PointF(start.x, start.y),
                end = PointF(end.x, end.y),
                points = arrowPts
            )
            return SnappedShape(ShapeType.ARROW, arrowStroke)
        }

        return null
    }

    /**
     * Phase 223 — ruler line snap. Unlike [trySnapShape]'s LINE branch, this does
     * NOT gate on the perpendicularDeviation/direct-distance fit: the RULER is an
     * explicit "draw a straight line" mode, so ANY freehand drag is collapsed to
     * an exact start→end LINE regardless of how far it wavers. Returns the snapped
     * stroke directly (no SnappedShape wrapper) so callers can also drive the
     * distinct ruler snap tick.
     */
    fun forceLineSnap(rawStroke: Stroke): Stroke {
        val first = rawStroke.points.firstOrNull() ?: rawStroke.start ?: PointF(0f, 0f)
        val last = rawStroke.points.lastOrNull() ?: rawStroke.end ?: first
        val start = PointF(first.x, first.y)
        val end = PointF(last.x, last.y)
        return rawStroke.copy(
            tool = StrokeTool.LINE,
            start = start,
            end = end,
            points = listOf(start, end)
        )
    }

    /**
     * Average perpendicular distance of every point from the start→end line,
     * normalized by callers against the direct distance. Measures how much the
     * stroke wavers around a straight path.
     */
    private fun perpendicularDeviation(pts: List<PointF>, start: PointF, end: PointF): Float {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-4f) return 0f
        val nx = -dy / len
        val ny = dx / len
        var acc = 0f
        for (p in pts) {
            acc += abs((p.x - start.x) * nx + (p.y - start.y) * ny)
        }
        return acc / pts.size
    }

    /**
     * Fraction of points that lie at or within [margin] of the bounding-box
     * perimeter. High values mean the drawing genuinely traces the outline of
     * the rectangle rather than merely spanning it. Points outside the bounds
     * count as off-perimeter.
     */
    private fun perimeterFitRatio(
        pts: List<PointF>,
        minX: Float,
        maxX: Float,
        minY: Float,
        maxY: Float,
        margin: Float
    ): Float {
        if (pts.isEmpty()) return 0f
        var onPerimeter = 0
        for (p in pts) {
            if (p.x < minX || p.x > maxX || p.y < minY || p.y > maxY) continue
            val dLeft = p.x - minX
            val dRight = maxX - p.x
            val dTop = p.y - minY
            val dBottom = maxY - p.y
            if (minOf(minOf(dLeft, dRight), minOf(dTop, dBottom)) <= margin) onPerimeter++
        }
        return onPerimeter.toFloat() / pts.size
    }

    /**
     * Phase 212: fraction of points that hug BOTH axes of the bounding box
     * simultaneously (i.e. sit near a corner region). A traced rectangle passes
     * straight through all four corners; an ellipse/circle only touches the box
     * at four tangent regions and never approaches a corner, so it scores ~0.
     */
    private fun hasCornerEvidence(
        pts: List<PointF>,
        minX: Float,
        maxX: Float,
        minY: Float,
        maxY: Float,
        margin: Float
    ): Boolean {
        if (pts.isEmpty()) return false
        var corners = 0
        for (p in pts) {
            val nearX = (p.x - minX <= margin) || (maxX - p.x <= margin)
            val nearY = (p.y - minY <= margin) || (maxY - p.y <= margin)
            if (nearX && nearY) corners++
        }
        return corners.toFloat() / pts.size >= 0.04f
    }
}
