package com.authorss81.noteflow.utils

import com.authorss81.noteflow.data.model.PointF
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Ramer-Douglas-Peucker (RDP) polyline simplification algorithm.
 * Reduces the number of points in a vector stroke while preserving
 * curve fidelity and exact start/end positions.
 */
object RamerDouglasPeucker {

    fun simplify(points: List<PointF>, epsilon: Float = 0.75f): List<PointF> {
        if (points.size <= 2) return points

        var maxDistance = 0f
        var maxIndex = 0
        val start = points.first()
        val end = points.last()

        for (i in 1 until points.size - 1) {
            val distance = perpendicularDistance(points[i], start, end)
            if (distance > maxDistance) {
                maxDistance = distance
                maxIndex = i
            }
        }

        return if (maxDistance > epsilon) {
            val rec1 = simplify(points.subList(0, maxIndex + 1), epsilon)
            val rec2 = simplify(points.subList(maxIndex, points.size), epsilon)
            rec1.dropLast(1) + rec2
        } else {
            listOf(start, end)
        }
    }

    private fun perpendicularDistance(point: PointF, lineStart: PointF, lineEnd: PointF): Float {
        val dx = lineEnd.x - lineStart.x
        val dy = lineEnd.y - lineStart.y

        if (dx == 0f && dy == 0f) {
            val pdx = point.x - lineStart.x
            val pdy = point.y - lineStart.y
            return sqrt(pdx * pdx + pdy * pdy)
        }

        val numerator = abs(dy * point.x - dx * point.y + lineEnd.x * lineStart.y - lineEnd.y * lineStart.x)
        val denominator = sqrt(dx * dx + dy * dy)
        return (numerator / denominator)
    }
}
