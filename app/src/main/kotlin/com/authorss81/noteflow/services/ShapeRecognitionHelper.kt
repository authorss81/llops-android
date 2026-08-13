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

        // 1. Check Straight Line (direct distance ~ path length)
        if (directDistance / max(1f, pathLength) > snapThreshold) {
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

            if (avgDev < 0.35f) {
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
        if (isClosedLoop && pts.size >= 10) {
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

        // 4. Check Arrow (Straight shaft + arrowhead at end)
        if (pts.size >= 10 && directDistance / max(1f, pathLength) > 0.68f) {
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
}
