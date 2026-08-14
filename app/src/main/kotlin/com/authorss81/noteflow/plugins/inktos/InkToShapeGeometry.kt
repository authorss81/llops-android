package com.authorss81.noteflow.plugins.inktos

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A single ink-stroke point as the geometry core sees it. Deliberately a bare
 * x/y pair — the plugin wrapper maps the app's `PointF` (which carries
 * pressure/tilt/timestamps) to [InkPoint] and back, so the core stays
 * Android-free and trivially unit-testable.
 */
data class InkPoint(val x: Float, val y: Float)

/**
 * The InkStroke→Shape geometry core (Phase 25).
 *
 * PURE JVM — no Android, no Compose, no model classes. This is what makes the
 * plugin's decision logic independently unit-testable in CI. The thin plugin
 * wrapper (`InkToShapePlugin`) maps the app's `Stroke` points to [InkPoint] and
 * the detected shape back to a crisp `Stroke`; the canvas only talks to the
 * plugin through the `ShapeFromInk` capability and never reaches in here.
 *
 * The math mirrors the proven `ShapeRecognitionHelper` auto-snap engine
 * (straightness ratio + perpendicular deviation for lines, ellipse-equation fit
 * for ellipses, bounding-box perimeter fit for rectangles, a straight-shaft +
 * arrowhead band for arrows) and adds three explicit discriminators:
 *
 * - **Ellipse:** perimeter-fit (ellipse equation) AND circularity ratio
 *   `4π·Area / perimeter²` (≈1.0 for a circle, 0.88 for a 100×60 ellipse, 0.785
 *   for a square) — a sloppy closed loop that merely spans its box is rejected.
 * - **Rectangle / rounded-rect:** bounding-box perimeter fit AND corner
 *   coverage (points near the box corners). Corner coverage is what keeps a
 *   circle (which curves inside its bounding box) from being misdetected as a
 *   rectangle; rounded corners up to the perimeter margin still count.
 * - **Arrow / polyline:** straight-shaft ratio band PLUS an explicit direction
 *   change at the stroke's end (the arrowhead vee), so a plain wavy mark that
 *   merely spans a box cannot sneak in.
 *
 * Unlike the auto-snap engine, this core accepts 2-point strokes for the LINE
 * case: the canvas stores RDP-simplified points, so a genuinely straight
 * hand-drawn line often survives as exactly two points and must still convert.
 */
object InkToShapeGeometry {

    /** The detected geometric kind. */
    enum class ShapeType { LINE, RECTANGLE, ELLIPSE, ARROW }

    /**
     * A clean, snapped shape. [points] are crisp (2 points for a line, 5 for a
     * rectangle/arrow, 37 for an ellipse). The remaining fields are diagnostic
     * measurements used by the report/tests to show WHY a stroke converted.
     */
    data class DetectedShape(
        val type: ShapeType,
        val points: List<InkPoint>,
        val start: InkPoint,
        val end: InkPoint,
        /** 4π·Area/perimeter² — ≈1.0 for a circle; null when not a closed loop. */
        val circularity: Float? = null,
        /** Bounding-box corners (0..4) with a point within the perimeter margin. */
        val cornerCoverage: Int = 0,
        /** Fraction of points on the bounding-box perimeter (rect gate). */
        val perimeterFit: Float = 0f,
        /** Average ellipse-equation deviation (ellipse gate). */
        val ellipseFitDeviation: Float = 0f,
        /** directDistance / pathLength — 1.0 for a perfect line. */
        val straightness: Float = 0f,
        /** Direction change (degrees) of the final segment vs the shaft (arrow gate). */
        val endDirectionChangeDegrees: Float = 0f
    )

    // ---- public entry point ------------------------------------------------

    /**
     * Detect a clean shape in [points], or return null when the stroke is too
     * rough / the wrong kind of mark (honest rejection — never a guess).
     */
    fun detect(points: List<InkPoint>, snapThreshold: Float = 0.82f): DetectedShape? {
        if (points.size < 2) return null
        val start = points.first()
        val end = points.last()

        var minX = points[0].x
        var maxX = points[0].x
        var minY = points[0].y
        var maxY = points[0].y
        var pathLength = 0f
        for (i in 1 until points.size) {
            val dx = points[i].x - points[i - 1].x
            val dy = points[i].y - points[i - 1].y
            val seg = sqrt(dx * dx + dy * dy)
            pathLength += seg
            minX = min(minX, points[i].x)
            maxX = max(maxX, points[i].x)
            minY = min(minY, points[i].y)
            maxY = max(maxY, points[i].y)
        }
        val width = maxX - minX
        val height = maxY - minY
        val boundingDiag = sqrt(width * width + height * height)
        if (boundingDiag < 15f) return null // ignore tiny specks

        val directDistance = dist(start, end)
        val straightness = directDistance / max(1f, pathLength)

        // 1. Straight line — direct distance ≈ path length AND the path hugs
        //    the start→end line (a wavy mark that retraces itself can't sneak in).
        if (straightness > snapThreshold &&
            perpendicularDeviation(points, start, end) / max(1f, directDistance) < 0.10f
        ) {
            return DetectedShape(
                type = ShapeType.LINE,
                points = listOf(start, end),
                start = start,
                end = end,
                straightness = straightness
            )
        }

        val isClosedLoop = directDistance < boundingDiag * 0.28f

        if (isClosedLoop && points.size >= 6) {
            // 2. Rectangle / rounded-rect — check BEFORE the ellipse so a traced
            //    square (which also fits an ellipse) is a rectangle, not a circle.
            if (points.size >= 8) {
                val margin = max(5f, boundingDiag * 0.06f)
                val perimeterFit = perimeterFitRatio(points, minX, maxX, minY, maxY, margin)
                val cornerCoverage = cornerCoverage(points, minX, maxX, minY, maxY, margin)
                if (perimeterFit >= 0.72f && cornerCoverage >= 2) {
                    val rectPts = listOf(
                        InkPoint(minX, minY),
                        InkPoint(maxX, minY),
                        InkPoint(maxX, maxY),
                        InkPoint(minX, maxY),
                        InkPoint(minX, minY)
                    )
                    return DetectedShape(
                        type = ShapeType.RECTANGLE,
                        points = rectPts,
                        start = InkPoint(minX, minY),
                        end = InkPoint(maxX, maxY),
                        perimeterFit = perimeterFit,
                        cornerCoverage = cornerCoverage,
                        straightness = straightness
                    )
                }
            }

            // 3. Circle / ellipse — perimeter fit (ellipse equation) + circularity.
            if (points.size >= 10) {
                val centerX = (minX + maxX) / 2f
                val centerY = (minY + maxY) / 2f
                val radiusX = width / 2f
                val radiusY = height / 2f
                val avgDev = ellipseFitDeviation(points, centerX, centerY, radiusX, radiusY)
                val circularity = circularityRatio(points, pathLength)
                if (avgDev < 0.35f && circularity >= 0.30f) {
                    val steps = 36
                    val ellipsePts = ArrayList<InkPoint>(steps + 1)
                    for (i in 0..steps) {
                        val angle = (2 * PI * i / steps).toFloat()
                        ellipsePts.add(
                            InkPoint(
                                centerX + radiusX * cos(angle),
                                centerY + radiusY * sin(angle)
                            )
                        )
                    }
                    return DetectedShape(
                        type = ShapeType.ELLIPSE,
                        points = ellipsePts,
                        start = InkPoint(minX, minY),
                        end = InkPoint(maxX, maxY),
                        circularity = circularity,
                        ellipseFitDeviation = avgDev,
                        straightness = straightness
                    )
                }
            }
        }

        // 4. Arrow — straight shaft whose arrowhead adds enough path length to
        //    fall BELOW the LINE threshold, low overall deviation, AND an explicit
        //    direction change at the end (the head vee).
        if (points.size >= 8 &&
            straightness in 0.55f..0.82f &&
            perpendicularDeviation(points, start, end) / max(1f, directDistance) < 0.12f
        ) {
            val headDegrees = endDirectionChangeDegrees(points, start, end)
            if (headDegrees >= 10f) {
                val angle = atan2(end.y - start.y, end.x - start.x)
                val headLength = min(35f, max(15f, directDistance * 0.2f))
                val headAngle = PI.toFloat() / 6f // 30 degrees

                val p1x = end.x - headLength * cos(angle - headAngle)
                val p1y = end.y - headLength * sin(angle - headAngle)
                val p2x = end.x - headLength * cos(angle + headAngle)
                val p2y = end.y - headLength * sin(angle + headAngle)

                val arrowPts = listOf(
                    InkPoint(start.x, start.y),
                    InkPoint(end.x, end.y),
                    InkPoint(p1x, p1y),
                    InkPoint(end.x, end.y),
                    InkPoint(p2x, p2y)
                )
                return DetectedShape(
                    type = ShapeType.ARROW,
                    points = arrowPts,
                    start = start,
                    end = end,
                    straightness = straightness,
                    endDirectionChangeDegrees = headDegrees
                )
            }
        }

        return null
    }

    // ---- measurements -------------------------------------------------------

    /** Average perpendicular distance of every point from the start→end line. */
    private fun perpendicularDeviation(points: List<InkPoint>, start: InkPoint, end: InkPoint): Float {
        val len = dist(start, end)
        if (len < 1e-4f) return 0f
        val nx = -(end.y - start.y) / len
        val ny = (end.x - start.x) / len
        var acc = 0f
        for (p in points) {
            acc += abs((p.x - start.x) * nx + (p.y - start.y) * ny)
        }
        return acc / points.size
    }

    /**
     * Fraction of points lying at or within [margin] of the bounding-box
     * perimeter — how genuinely the stroke traces the box outline (vs merely
     * spanning it). Points outside the bounds count as off-perimeter.
     */
    private fun perimeterFitRatio(
        points: List<InkPoint>,
        minX: Float,
        maxX: Float,
        minY: Float,
        maxY: Float,
        margin: Float
    ): Float {
        if (points.isEmpty()) return 0f
        var onPerimeter = 0
        for (p in points) {
            if (p.x < minX || p.x > maxX || p.y < minY || p.y > maxY) continue
            val dLeft = p.x - minX
            val dRight = maxX - p.x
            val dTop = p.y - minY
            val dBottom = maxY - p.y
            if (minOf(minOf(dLeft, dRight), minOf(dTop, dBottom)) <= margin) onPerimeter++
        }
        return onPerimeter.toFloat() / points.size
    }

    /**
     * How many of the four bounding-box corners have at least one point within
     * [margin]. 4 for a traced rectangle, 2–4 for a rounded-rect whose corner
     * radius fits the margin, 0 for a circle (it curves inside the box).
     */
    private fun cornerCoverage(
        points: List<InkPoint>,
        minX: Float,
        maxX: Float,
        minY: Float,
        maxY: Float,
        margin: Float
    ): Int {
        val corners = listOf(
            InkPoint(minX, minY),
            InkPoint(maxX, minY),
            InkPoint(maxX, maxY),
            InkPoint(minX, maxY)
        )
        var covered = 0
        for (corner in corners) {
            if (points.any { dist(it, corner) <= margin }) covered++
        }
        return covered
    }

    /** Average deviation of every point from the ellipse equation. */
    private fun ellipseFitDeviation(
        points: List<InkPoint>,
        centerX: Float,
        centerY: Float,
        radiusX: Float,
        radiusY: Float
    ): Float {
        var totalDev = 0f
        for (p in points) {
            val dx = (p.x - centerX) / max(1f, radiusX)
            val dy = (p.y - centerY) / max(1f, radiusY)
            totalDev += abs((dx * dx + dy * dy) - 1.0f)
        }
        return totalDev / points.size
    }

    /** Circularity ratio 4π·Area/perimeter² (Shoelace area). ≈1 for a circle. */
    private fun circularityRatio(points: List<InkPoint>, pathLength: Float): Float {
        var area = 0f
        for (i in points.indices) {
            val p = points[i]
            val q = points[(i + 1) % points.size]
            area += p.x * q.y - q.x * p.y
        }
        area = abs(area) / 2f
        val p = max(1f, pathLength)
        return 4f * PI.toFloat() * area / (p * p)
    }

    /**
     * Direction change (degrees) of the FINAL segment vs the shaft direction.
     * A real arrow's last stroke segment is one wing of the head vee, so it
     * diverges from the shaft by roughly the head angle (≥ 10° here); a plain
     * straight/slightly-wavy mark ends along (or hugging) the shaft.
     */
    private fun endDirectionChangeDegrees(points: List<InkPoint>, start: InkPoint, end: InkPoint): Float {
        if (points.size < 2) return 0f
        val a = points[points.size - 2]
        val b = points.last()
        val segAngle = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
        val shaftAngle = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
        var delta = abs(segAngle - shaftAngle)
        while (delta > PI) delta = abs(delta - 2 * PI)
        return (delta * 180.0 / PI).toFloat()
    }

    private fun dist(a: InkPoint, b: InkPoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
    }
}
