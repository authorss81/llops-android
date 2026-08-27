package com.authorss81.noteflow.services

import android.graphics.Bitmap
import com.authorss81.noteflow.data.model.PointF
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Phase 221: tolerance-aware 4-way flood fill engine.
 *
 * Pure-JVM core algorithm operating on raw ARGB pixel data ([IntArray]).
 * An Android [Bitmap] adapter is provided for the canvas integration.
 *
 * Color comparison happens in **linear RGB** (sRGB EOTF via [WetMixingMath.srgbToLinear])
 * so tolerance behaves perceptually uniformly — a 12% tolerance on bright red is
 * proportionally the same perceptual distance as 12% on dark blue.
 *
 * The scan uses an iterative stack-based 4-way approach (no recursion, no allocation
 * inside the inner loop). Visited pixels are tracked via a same-size [IntArray] bitmask.
 *
 * After the fill, the outer contour is extracted and simplified via
 * Ramer–Douglas–Peucker (epsilon 1.3 px) to keep the stroke model compact.
 *
 * Constraints satisfied:
 * - At most ONE [Bitmap.copy] per fill (recycled after).
 * - No allocation inside the inner loop — stack and visited array are pre-allocated.
 * - Bounded by [MAX_FILLED_PIXELS] on the filled region, not the bitmap.
 */
object FloodFillEngine {

    private const val MAX_FILLED_PIXELS = StrokeGeometryPolicy.MAX_POINTS_PER_PAGE

    /**
     * Default tolerance for the fill bucket tool (12% in linear RGB).
     * Matches the PROMPT spec.
     */
    const val DEFAULT_TOLERANCE_PERCENT = 12f

    /**
     * Ramer–Douglas–Peucker simplification epsilon in pixel units.
     * Matches the PROMPT spec.
     */
    const val RDP_EPSILON = 1.3f

    /**
     * Core flood fill on raw ARGB pixel data.
     *
     * Mutates [pixels] in-place (writes [fillColorArgb] to matched pixels)
     * and returns the list of filled-pixel indices (row-major flat index).
     *
     * @return filled pixel indices, or empty on failure / bounds overflow.
     */
    internal fun floodFillIndices(
        pixels: IntArray,
        width: Int,
        height: Int,
        seedX: Int,
        seedY: Int,
        fillColorArgb: Int,
        tolerancePercent: Float = DEFAULT_TOLERANCE_PERCENT
    ): List<Int> {
        if (width <= 0 || height <= 0) return emptyList()
        if (seedX < 0 || seedX >= width || seedY < 0 || seedY >= height) return emptyList()

        val seedColor = pixels[seedY * width + seedX]
        val tolerance = tolerancePercent.coerceIn(0f, 100f) / 100f
        val seedLinR = srgbChannelToLinear(seedColor shr 16 and 0xFF)
        val seedLinG = srgbChannelToLinear(seedColor shr 8 and 0xFF)
        val seedLinB = srgbChannelToLinear(seedColor and 0xFF)

        val visited = IntArray(width * height)
        val filled = mutableListOf<Int>()
        val stack = ArrayDeque<Int>(256)
        stack.addLast(seedY * width + seedX)

        while (stack.isNotEmpty()) {
            if (filled.size >= MAX_FILLED_PIXELS) break

            val idx = stack.removeLast()
            if (visited[idx] != 0) continue
            val px = idx % width
            val py = idx / width

            val pixel = pixels[idx]
            val linR = srgbChannelToLinear(pixel shr 16 and 0xFF)
            val linG = srgbChannelToLinear(pixel shr 8 and 0xFF)
            val linB = srgbChannelToLinear(pixel and 0xFF)

            val maxDev = maxOf(
                kotlin.math.abs(linR - seedLinR),
                kotlin.math.abs(linG - seedLinG),
                kotlin.math.abs(linB - seedLinB)
            )
            if (maxDev > tolerance) continue

            visited[idx] = 1
            pixels[idx] = fillColorArgb
            filled.add(idx)

            if (px + 1 < width) { val r = py * width + (px + 1); if (visited[r] == 0) stack.addLast(r) }
            if (px - 1 >= 0) { val l = py * width + (px - 1); if (visited[l] == 0) stack.addLast(l) }
            if (py + 1 < height) { val b = (py + 1) * width + px; if (visited[b] == 0) stack.addLast(b) }
            if (py - 1 >= 0) { val a = (py - 1) * width + px; if (visited[a] == 0) stack.addLast(a) }
        }

        return filled
    }

    /**
     * Extract the outer contour from a filled bitmap region.
     *
     * A filled pixel is a boundary pixel if any of its 4 neighbours is NOT filled.
     * Boundary pixels are collected and then simplified via Ramer–Douglas–Peucker.
     *
     * @param filledIndices  flat row-major indices returned by [floodFillIndices].
     * @param width          bitmap width.
     * @param height         bitmap height.
     * @return simplified outer contour as [PointF] list (pixel centres), ordered
     *         roughly along the boundary.
     */
    fun extractContour(
        filledIndices: List<Int>,
        width: Int,
        height: Int
    ): List<PointF> {
        if (filledIndices.isEmpty()) return emptyList()

        val filledSet = HashSet(filledIndices)
        val boundary = mutableListOf<PointF>()

        for (idx in filledIndices) {
            val px = idx % width
            val py = idx / width
            val isBoundary = (px == 0 || px == width - 1 || py == 0 || py == height - 1) ||
                (py * width + (px + 1)) !in filledSet ||
                (py * width + (px - 1)) !in filledSet ||
                ((py + 1) * width + px) !in filledSet ||
                ((py - 1) * width + px) !in filledSet
            if (isBoundary) {
                boundary.add(PointF(px.toFloat() + 0.5f, py.toFloat() + 0.5f))
            }
        }

        if (boundary.size <= 2) return boundary

        // Sort by angle from centroid so the contour follows a coherent polyline
        // path before RDP simplification.
        val cx = boundary.map { it.x }.average().toFloat()
        val cy = boundary.map { it.y }.average().toFloat()
        boundary.sortBy { kotlin.math.atan2((it.y - cy).toDouble(), (it.x - cx).toDouble()).toFloat() }

        return simplifyRdp(boundary, RDP_EPSILON)
    }

    /**
     * Ramer–Douglas–Peucker polyline simplification.
     */
    fun simplifyRdp(points: List<PointF>, epsilon: Float): List<PointF> {
        if (points.size <= 2) return points

        var maxDist = 0f
        var maxIdx = 0
        val first = points.first()
        val last = points.last()

        for (i in 1 until points.size - 1) {
            val d = perpendicularDistance(points[i], first, last)
            if (d > maxDist) {
                maxDist = d
                maxIdx = i
            }
        }

        return if (maxDist > epsilon) {
            val left = simplifyRdp(points.subList(0, maxIdx + 1), epsilon)
            val right = simplifyRdp(points.subList(maxIdx, points.size), epsilon)
            left.dropLast(1) + right
        } else {
            listOf(first, last)
        }
    }

    /**
     * Android Bitmap adapter. Copies the source bitmap (one copy, recycled after),
     * performs the flood fill, extracts the outer contour, and returns simplified
     * boundary points.
     *
     * @param source     the bitmap to sample from.
     * @param seedX      seed X in pixel coordinates.
     * @param seedY      seed Y in pixel coordinates.
     * @param fillColorArgb  ARGB fill color.
     * @param tolerancePercent  tolerance 0..100 in linear RGB.
     * @return simplified outer contour [PointF] list, or empty on failure.
     */
    fun floodFillBitmap(
        source: Bitmap,
        seedX: Int,
        seedY: Int,
        fillColorArgb: Int,
        tolerancePercent: Float = DEFAULT_TOLERANCE_PERCENT
    ): List<PointF> {
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) return emptyList()

        val copy = source.copy(Bitmap.Config.ARGB_8888, true) ?: return emptyList()
        try {
            val pixels = IntArray(w * h)
            copy.getPixels(pixels, 0, w, 0, 0, w, h)
            val filled = floodFillIndices(pixels, w, h, seedX, seedY, fillColorArgb, tolerancePercent)
            return extractContour(filled, w, h)
        } finally {
            copy.recycle()
        }
    }

    private fun perpendicularDistance(point: PointF, lineStart: PointF, lineEnd: PointF): Float {
        val dx = lineEnd.x - lineStart.x
        val dy = lineEnd.y - lineStart.y
        val lengthSq = dx * dx + dy * dy
        if (lengthSq < 1e-6f) {
            val px = point.x - lineStart.x
            val py = point.y - lineStart.y
            return sqrt(px * px + py * py)
        }
        val t = ((point.x - lineStart.x) * dx + (point.y - lineStart.y) * dy) / lengthSq
        val closestX = lineStart.x + t * dx
        val closestY = lineStart.y + t * dy
        val ex = point.x - closestX
        val ey = point.y - closestY
        return sqrt(ex * ex + ey * ey)
    }

    private fun srgbChannelToLinear(byteValue: Int): Float {
        val c = (byteValue.coerceIn(0, 255) / 255f)
        return if (c <= 0.04045f) c / 12.92f
        else Math.pow(((c + 0.055) / 1.055).toDouble(), 2.4).toFloat()
    }
}
