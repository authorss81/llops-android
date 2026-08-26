package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.StrokeTool
import kotlin.math.roundToLong

/**
 * Phase 214 (Stroke Smoothing v2): commit-time fairing of hairline ink.
 *
 * RDP ([RamerDouglasPeucker]) keeps geometry honest but leaves the small
 * angular kinks the stabilizer's EWMA could not fully absorb; on hairline nibs
 * those kinks are visible as a slightly "wobbly straight". A SINGLE Chaikin
 * corner-cutting pass over the ALREADY-simplified polyline replaces each
 * interior corner with two points at the ¼/¾ positions, which converts the
 * residual kinks into a visually fair curve at negligible cost — no spline
 * fitting, no allocations beyond the output list.
 *
 * COMMIT-TIMING CONTRACT (mirrors [StrokeSimplifyPolicy]): fairing runs ONLY
 * on pointer-up inside the canvas commit path, never mid-stroke — the live
 * preview must never snap under the pen. Shape-snapped strokes are excluded:
 * their geometry is already idealized and corner-cutting would blunt exact
 * rectangle/arrow vertices.
 *
 * Budgets: the pass roughly doubles interior point count, so the result is
 * re-capped through [StrokeGeometryPolicy] by the caller immediately after
 * (`capLoadedPoints`) — the cap is enforced AFTER any fairing, never before.
 */
object StrokeFairingPolicy {

    /**
     * Fairing applies only when MORE than this many points survived RDP.
     * Short marks (dots, ticks) keep their exact simplified geometry.
     */
    const val MIN_POINTS_FOR_FAIRING_EXCLUSIVE = 8

    /**
     * Gate for one Chaikin pass. True iff:
     *  - more than [MIN_POINTS_FOR_FAIRING_EXCLUSIVE] points survived RDP;
     *  - the stroke is HAIRLINE-class per [StrokeSimplifyPolicy.isHairlineBrush]
     *    (fine-tip brush AND narrow width) — broad brushes hide the kinks, so
     *    they are never touched and keep their byte-identical committed shape;
     *  - the epsilon actually used was inside the hairline band.
     */
    fun shouldFair(
        survivingPointCount: Int,
        tool: StrokeTool,
        strokeWidthPx: Float,
        appliedEpsilon: Float
    ): Boolean {
        if (survivingPointCount <= MIN_POINTS_FOR_FAIRING_EXCLUSIVE) return false
        if (!StrokeSimplifyPolicy.isHairlineBrush(tool, strokeWidthPx)) return false
        return appliedEpsilon <= StrokeSimplifyPolicy.HAIRLINE_MAX_EPSILON_PX &&
            appliedEpsilon >= StrokeSimplifyPolicy.HAIRLINE_MIN_EPSILON_PX
    }

    /**
     * ONE Chaikin corner-cutting pass over [points].
     *
     * Endpoints are preserved EXACTLY (same objects); every INTERIOR vertex V
     * (between neighbours U and W) is replaced by the pair
     * `Q = V·0.75 + U·0.25` and `R = V·0.75 + W·0.25`, so output size is
     * exactly `2·n − 2`. Pressure / tilt / timestamps interpolate linearly onto
     * the new points, so pressure-rendered width stays consistent along the
     * faired curve. If the doubled result would exceed [maxPoints] the input is
     * returned UNFAIRED instead of truncating mid-curve — a truncated fair
     * would end in a visible half-cut corner, while an unfaired (already RDP'd)
     * polyline is always safe.
     */
    fun chaikinOnce(
        points: List<PointF>,
        maxPoints: Int = StrokeGeometryPolicy.MAX_POINTS_PER_STROKE
    ): List<PointF> {
        val n = points.size
        if (n < 3) return points
        if (2 * n - 2 > maxPoints) return points

        val out = ArrayList<PointF>(2 * n - 2)
        out.add(points.first())
        for (i in 1 until n - 1) {
            val prev = points[i - 1]
            val vertex = points[i]
            val next = points[i + 1]
            out.add(mixPoint(vertex, prev, 0.75f))
            out.add(mixPoint(vertex, next, 0.75f))
        }
        out.add(points.last())
        return out
    }

    private fun mixPoint(a: PointF, b: PointF, weightA: Float): PointF {
        val wb = 1f - weightA
        return PointF(
            x = a.x * weightA + b.x * wb,
            y = a.y * weightA + b.y * wb,
            pressure = a.pressure?.let { ap ->
                b.pressure?.let { bp -> ap * weightA + bp * wb } ?: ap
            },
            tilt = a.tilt?.let { at ->
                b.tilt?.let { bt -> at * weightA + bt * wb } ?: at
            },
            // Timestamps interpolate in DOUBLE space (review-fix): event-uptime
            // millis exceed Float's 24-bit exact-integer range after ~4.6 h of
            // device uptime, where a Float blend would round stamps to coarse
            // or duplicated values. Double keeps every Long below 2^53 exact.
            timestampMs = a.timestampMs?.let { at ->
                b.timestampMs?.let { bt ->
                    (at.toDouble() * weightA + bt.toDouble() * wb).roundToLong()
                } ?: at
            }
        )
    }

    /**
     * Maximum absolute turn angle (degrees) between consecutive segments —
     * used by tests to prove fairing actually flattens kinks.
     */
    fun maxTurnAngleDeg(points: List<PointF>): Double {
        if (points.size < 3) return 0.0
        var maxDeg = 0.0
        for (i in 1 until points.size - 1) {
            val ax = (points[i].x - points[i - 1].x).toDouble()
            val ay = (points[i].y - points[i - 1].y).toDouble()
            val bx = (points[i + 1].x - points[i].x).toDouble()
            val by = (points[i + 1].y - points[i].y).toDouble()
            val la = kotlin.math.sqrt(ax * ax + ay * ay)
            val lb = kotlin.math.sqrt(bx * bx + by * by)
            if (la <= 1e-9 || lb <= 1e-9) continue
            var cos = (ax * bx + ay * by) / (la * lb)
            cos = cos.coerceIn(-1.0, 1.0)
            val deg = Math.toDegrees(Math.acos(cos))
            if (deg > maxDeg) maxDeg = deg
        }
        return maxDeg
    }
}
