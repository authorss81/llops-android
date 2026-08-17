package com.authorss81.noteflow.utils

import com.authorss81.noteflow.data.model.PointF
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Ramer-Douglas-Peucker (RDP) polyline simplification algorithm.
 * Reduces the number of points in a vector stroke while preserving
 * curve fidelity and exact start/end positions.
 */
object RamerDouglasPeucker {

    /**
     * Simplifies [points] to an epsilon-tolerance polyline with the exact same start/end.
     *
     * The classic algorithm is recursive (split at the max-distance point), but a single
     * long stroke whose farthest point is repeatedly adjacent to a segment end (worst-case
     * recursion depth ~ point count, e.g. a square-wave path) threw a StackOverflowError in
     * the canvas commit coroutine (B2-DOS-09). This implementation walks an EXPLICIT heap
     * stack of (start, end) segments, so the split depth is bounded by heap only — no call
     * stack growth — and degenerate long strokes simplify without crashing. The result is
     * byte-identical to the classic recursive formulation (same per-segment max-distance
     * split, same go/no-go epsilon decision, same kept-point set). API 26+ floor:
     * [java.util.ArrayDeque] exists since Android 1.0, so no newer-API requirement or
     * fallback is needed.
     */
    fun simplify(points: List<PointF>, epsilon: Float = 0.75f): List<PointF> {
        if (points.size <= 2) return points

        val size = points.size
        val keep = BooleanArray(size)
        keep[0] = true
        keep[size - 1] = true

        // Iterative RDP: process segments LIFO from a heap stack instead of recursing.
        // A segment (startIdx, endIdx) with no point farther than epsilon keeps only its
        // two (already kept) endpoints; otherwise the farthest point is kept and the two
        // halves are pushed. Both endpoints of every pushed segment are marked kept at
        // this point, so each segment's decision is fully independent of processing order.
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, size - 1))

        while (stack.isNotEmpty()) {
            val segment = stack.removeLast()
            val startIdx = segment[0]
            val endIdx = segment[1]
            if (endIdx - startIdx <= 1) continue

            var maxDistance = 0f
            var maxIndex = startIdx
            val start = points[startIdx]
            val end = points[endIdx]

            for (i in startIdx + 1 until endIdx) {
                val distance = perpendicularDistance(points[i], start, end)
                if (distance > maxDistance) {
                    maxDistance = distance
                    maxIndex = i
                }
            }

            if (maxDistance > epsilon) {
                keep[maxIndex] = true
                stack.addLast(intArrayOf(startIdx, maxIndex))
                stack.addLast(intArrayOf(maxIndex, endIdx))
            }
        }

        val result = ArrayList<PointF>(size)
        for (i in 0 until size) {
            if (keep[i]) result.add(points[i])
        }
        return result
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
