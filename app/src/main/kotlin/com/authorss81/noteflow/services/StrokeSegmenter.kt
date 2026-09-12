package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * Eraser behaviour selector — persisted in SettingsManager (SharedPreferences),
 * NO DB schema change.
 *
 * [STROKE] is the classic whole-stroke eraser: any touched stroke is removed
 * entirely. [PARTIAL] removes only the covered portion of each touched stroke,
 * leaving the rest as one or more new segments.
 */
enum class EraserMode(val key: String, val label: String) {
    STROKE("STROKE", "Whole Stroke"),
    PARTIAL("PARTIAL", "Partial");

    companion object {
        fun fromSettingKey(key: String?): EraserMode =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: STROKE
    }
}

object StrokeSegmenter {

    /**
     * One erase-path sample. [radius] is the round-mask stamp radius for this
     * sample (Phase 124 — pressure-aware, see [EraserGeometryPolicy.stampRadius]).
     * A null radius falls back to the legacy `stroke.width + extraRadius` rule,
     * keeping the pre-Phase 124 partial eraser byte-compatible.
     */
    data class ErasePoint(val x: Float, val y: Float, val radius: Float? = null)
    data class SegmentResult(val surviving: List<Stroke>, val affected: Boolean)

    /**
     * Phase 256: the polyline gap ceiling for eraser hit tests. Raw pen paths
     * can carry 10-40 px gaps between consecutive points (fast swipes after
     * simplification), so an erase stroke between two widely spaced points was
     * previously missed. Densifying the stroke AND the erase path to this
     * ceiling makes every STROKE/PARTIAL decision segment-aware.
     */
    const val ERASE_DENSIFY_MAX_GAP_PX = 8f

    /**
     * Phase 256: re-samples a stroke polyline so no two consecutive samples are
     * farther apart than [maxGap] (default [ERASE_DENSIFY_MAX_GAP_PX]).
     * Original coordinates are preserved exactly; interior interpolation
     * midpoints inherit the segment-start pressure/tilt and a null timestamp
     * (they are derived geometry, not captured ink). Empty/singleton inputs
     * pass through unchanged. Pure JVM.
     */
    fun densifyPoints(points: List<PointF>, maxGap: Float = ERASE_DENSIFY_MAX_GAP_PX): List<PointF> {
        if (points.size < 2) return points
        val out = ArrayList<PointF>(points.size)
        out.add(points[0])
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            appendDensified(out, a.x, a.y, b.x, b.y, maxGap) { t, x, y ->
                PointF(x, y, a.pressure, a.tilt, null)
            }
            out.add(b)
        }
        return out
    }

    /**
     * Phase 256: re-samples the erase path so no two erase samples are farther
     * apart than [maxGap]. An interpolated sample's mask radius is linearly
     * interpolated between its neighbours' radii (null radii stay null), so a
     * rising-pressure swipe still stamps a faithful round mask along its whole
     * length. Pure JVM.
     */
    fun densifyErasePoints(samples: List<ErasePoint>, maxGap: Float = ERASE_DENSIFY_MAX_GAP_PX): List<ErasePoint> {
        if (samples.size < 2) return samples
        val out = ArrayList<ErasePoint>(samples.size)
        out.add(samples[0])
        for (i in 0 until samples.size - 1) {
            val a = samples[i]
            val b = samples[i + 1]
            appendDensified(out, a.x, a.y, b.x, b.y, maxGap) { t, x, y ->
                val r = when {
                    a.radius != null && b.radius != null -> a.radius + (b.radius - a.radius) * t
                    a.radius != null -> a.radius
                    b.radius != null -> b.radius
                    else -> null
                }
                ErasePoint(x, y, r)
            }
            out.add(b)
        }
        return out
    }

    private inline fun <T> appendDensified(
        out: MutableList<T>,
        ax: Float, ay: Float,
        bx: Float, by: Float,
        maxGap: Float,
        mid: (t: Float, x: Float, y: Float) -> T
    ) {
        val len = hypot(bx - ax, by - ay)
        val n = if (len <= maxGap || maxGap <= 0f) 0 else ceil(len / maxGap).toInt() - 1
        for (k in 1..n) {
            val t = k.toFloat() / (n + 1)
            out.add(mid(t, ax + (bx - ax) * t, ay + (by - ay) * t))
        }
    }

    /**
     * Phase 256: squared distance from point (px, py) to the segment (ax, ay)
     * -> (bx, by). Projection clamped to the segment; degenerate zero-length
     * segments fall back to the endpoint distance. Pure JVM, no allocation.
     */
    fun distSqToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val abx = bx - ax
        val aby = by - ay
        val len2 = abx * abx + aby * aby
        if (len2 <= 0f) return distSq(px, py, ax, ay)
        val t = (((px - ax) * abx + (py - ay) * aby) / len2).coerceIn(0f, 1f)
        val cx = ax + abx * t
        val cy = ay + aby * t
        return distSq(px, py, cx, cy)
    }

    private fun distSq(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return dx * dx + dy * dy
    }

    /**
     * Extra hit distance added to the stroke's own width, mirroring the classic
     * whole-stroke eraser threshold in AnnotationCanvas (`stroke.width + 18f`).
     */
    const val DEFAULT_EXTRA_RADIUS = 18f

    /**
     * Coverage radius of one [ErasePoint] against [stroke]'s centerline.
     *
     * - A point with an explicit [ErasePoint.radius] (Phase 124) removes every
     *   centerline point inside `radius + stroke.width / 2` — the round mask
     *   must swallow the whole nib half so a surviving run's boundary point is
     *   always OUTSIDE the mask, giving a smooth, round carve.
     * - A null radius keeps the legacy `stroke.width + extraRadius` rule.
     *
     * Pure JVM, allocation-free.
     */
    fun coverageRadiusFor(stroke: Stroke, sample: ErasePoint, extraRadius: Float): Float {
        val r = sample.radius
        return if (r != null && r.isFinite() && r > 0f) {
            com.authorss81.noteflow.services.EraserGeometryPolicy.coverageRadius(r, stroke.width)
        } else {
            com.authorss81.noteflow.services.EraserGeometryPolicy.legacyRadius(stroke.width, extraRadius)
        }
    }

    /**
     * Phase 124: whole-stroke hit-test used by the STROKE eraser (and its cursor
     * highlight). Mirrors the canvas `strokeContainsPoint` threshold
     * (`stroke.width + 18`) and honors the same symmetry mirror the canvas uses,
     * returning the LAST (topmost) matching stroke — a tap therefore yields the
     * exact stroke id the eraser would delete. Pure JVM.
     */
    fun hitStrokeAt(
        strokes: List<Stroke>,
        x: Float,
        y: Float,
        extraRadius: Float = DEFAULT_EXTRA_RADIUS,
        symmetryMode: SymmetryMode = SymmetryMode.OFF,
        symmetryCenterX: Float = 0f,
        symmetryCenterY: Float = 0f
    ): Stroke? {
        if (strokes.isEmpty()) return null
        val query = ErasePoint(x, y, radius = null)
        val mirror = SymmetryHelper.mirrorPoint(x, y, symmetryMode, symmetryCenterX, symmetryCenterY)
        val checkMirror = symmetryMode != SymmetryMode.OFF
        return strokes.lastOrNull { s ->
            polylineTouched(s, s.points, listOf(query), extraRadius) ||
                (checkMirror && polylineTouched(s, s.points, listOf(ErasePoint(mirror.x, mirror.y, radius = null)), extraRadius))
        }
    }

    /**
     * Phase 256 H1 (HIGH): segment-aware whole-stroke hit test. Every erase
     * sample's mask is tested against every STROKE POLYLINE SEGMENT (not just
     * the raw points), so an eraser swipe that passes BETWEEN two widely spaced
     * points of a stroke still touches it. The per-sample mask radius follows
     * [coverageRadiusFor] (pressure-aware when [ErasePoint.radius] is set).
     * Used for empty-points fallback rules and the STROKE-mode removal decision.
     */
    fun strokeTouchedBy(stroke: Stroke, eraseSamples: List<ErasePoint>, extraRadius: Float): Boolean {
        if (eraseSamples.isEmpty()) return false
        // Point-less rule strokes (rect/arrow/ellipse snapshots): landmark
        // points only (nearest live eraser path to a corner/edge hits).
        if (stroke.points.isEmpty()) {
            fun hit(x: Float, y: Float): Boolean {
                for (e in eraseSamples) {
                    val r = coverageRadiusFor(stroke, e, extraRadius).coerceAtLeast(1f)
                    if (distSqToSegment(e.x, e.y, x, y, x, y) <= r * r) return true
                }
                return false
            }
            val startHit = stroke.start?.let { hit(it.x, it.y) } ?: false
            if (startHit) return true
            val endHit = stroke.end?.let { hit(it.x, it.y) } ?: false
            return endHit
        }
        return polylineTouched(stroke, stroke.points, eraseSamples, extraRadius)
    }

    /**
     * Segment-aware centerline test (Phase 256): the stroke polyline is
     * densified to [ERASE_DENSIFY_MAX_GAP_PX] so no two consecutive samples are
     * more than 8 px apart, every raw point is checked against every erase
     * sample's mask, and every EDGE between consecutive samples is ALSO checked
     * against the mask with [distSqToSegment] — the long-edge path that used to
     * let a between-points erase swipe slip through. Pure JVM.
     */
    private fun polylineTouched(
        stroke: Stroke,
        points: List<PointF>,
        samples: List<ErasePoint>,
        extraRadius: Float
    ): Boolean {
        if (points.isEmpty()) return false
        val densified = densifyPoints(points, ERASE_DENSIFY_MAX_GAP_PX)
        for (p in densified) {
            for (e in samples) {
                val r = coverageRadiusFor(stroke, e, extraRadius).coerceAtLeast(1f)
                val dx = p.x - e.x
                val dy = p.y - e.y
                if (dx * dx + dy * dy <= r * r) return true
            }
        }
        // Edges that STILL span a mask in the middle even after densification
        // (the last-interval remainder of a long edge, or an edge that started
        // inside a gap): test the segment itself.
        for (i in 0 until densified.size - 1) {
            val a = densified[i]
            val b = densified[i + 1]
            for (e in samples) {
                val r = coverageRadiusFor(stroke, e, extraRadius).coerceAtLeast(1f)
                if (distSqToSegment(e.x, e.y, a.x, a.y, b.x, b.y) <= r * r) return true
            }
        }
        return false
    }

    /**
     * Splits [stroke]'s polyline into contiguous runs of points that are NOT
     * covered by any [eraseSamples] point within `stroke.width + extraRadius`.
     *
     * - Runs of >= 1 point survive as new [Stroke] objects (fresh ids, all other
     *   attributes copied unchanged).
     * - If no point is covered the original stroke object is returned untouched
     *   ([SegmentResult.affected] = false), so canvases can skip persistence.
     * - If every point is covered the result is empty — the stroke is removed.
     *
     * Pure JVM — no Android/Compose APIs, no network, cheap O(points × samples).
     */
    fun segment(
        stroke: Stroke,
        eraseSamples: List<ErasePoint>,
        extraRadius: Float = DEFAULT_EXTRA_RADIUS
    ): SegmentResult {
        if (eraseSamples.isEmpty()) {
            return SegmentResult(listOf(stroke), affected = false)
        }
        if (stroke.points.isEmpty()) {
            // Point-less rule strokes (rect/arrow/ellipse snapshots) are not
            // polylines — the canvas gates them before calling this, but keep
            // the pure rule total: nothing to trim, whole-stroke behaviour.
            val touched = strokeTouchedBy(stroke, eraseSamples, extraRadius.coerceAtLeast(0f))
            return SegmentResult(
                surviving = if (touched) emptyList() else listOf(stroke),
                affected = touched
            )
        }

        // Phase 256 H2: densify the stroke polyline so masks landing BETWEEN two
        // widely spaced raw points still mark coverage, then an edge pass below
        // splits any edge a mask actually crosses through its middle.
        val workPoints = densifyPoints(stroke.points, ERASE_DENSIFY_MAX_GAP_PX)
        val covered = BooleanArray(workPoints.size)
        var anyCovered = false
        for (i in workPoints.indices) {
            val p = workPoints[i]
            for (e in eraseSamples) {
                val r = coverageRadiusFor(stroke, e, extraRadius).coerceAtLeast(1f)
                val dx = p.x - e.x
                val dy = p.y - e.y
                if (dx * dx + dy * dy <= r * r) {
                    covered[i] = true
                    anyCovered = true
                    break
                }
            }
        }
        // Edge pass: a mask crossing the MIDDLE of an edge (between two
        // densified samples) must split it, never slip between them.
        for (i in 0 until workPoints.size - 1) {
            if (covered[i] && covered[i + 1]) continue
            val a = workPoints[i]
            val b = workPoints[i + 1]
            for (e in eraseSamples) {
                val r = coverageRadiusFor(stroke, e, extraRadius).coerceAtLeast(1f)
                if (distSqToSegment(e.x, e.y, a.x, a.y, b.x, b.y) <= r * r) {
                    covered[i] = true
                    covered[i + 1] = true
                    anyCovered = true
                    break
                }
            }
        }

        if (!anyCovered) {
            return SegmentResult(listOf(stroke), affected = false)
        }

        // Fix 2026-08-27: wet translucent + Beer-Lambert — fragments cause dark seam (double alpha at caps).
        // Keep pen/pencil as split, but wet uses single raster + Clear punch mask (no fragments, 0α holes).
        if (com.authorss81.noteflow.services.BrushStrokeMath.isWetRenderedTool(stroke.tool)) {
            val allCovered = covered.all { it }
            if (allCovered) {
                return SegmentResult(surviving = emptyList(), affected = true)
            }
            val existing = stroke.eraseMask ?: emptyList()
            // Phase 228 review-fix: only append stamps that actually carve ink a
            // prior stamp did not clear. `eraseSamples` is the whole accumulated
            // drag path SHARED across every touched stroke, so without this check
            // each partial-erase pass appended every sample (including reaches that
            // missed this stroke) and a long session grew `stroke.eraseMask`
            // without bound — inflating the persisted pointsJson toward the
            // B2-DOS-01 budget and forcing an N-circle Clear punch per frame.
            val newMasks = mutableListOf<com.authorss81.noteflow.data.model.EraseMask>()
            for (e in eraseSamples) {
                val r = coverageRadiusFor(stroke, e, extraRadius).coerceAtLeast(1f)
                if (maskStillCarves(workPoints, e.x, e.y, r, existing, newMasks)) {
                    newMasks.add(com.authorss81.noteflow.data.model.EraseMask(e.x, e.y, r))
                }
            }
            val masked = if (newMasks.isEmpty()) stroke else stroke.copy(eraseMask = existing + newMasks)
            return SegmentResult(surviving = listOf(masked), affected = true)
        }

        val survivors = mutableListOf<Stroke>()
        val run = mutableListOf<PointF>()

        fun flushRun() {
            if (run.isNotEmpty()) {
                survivors.add(
                    buildSegment(stroke, run.toList())
                )
                run.clear()
            }
        }

        // Phase 256: surviving runs come from the DENSIFIED work polyline, so a
        // long-edge gap can never smuggle back the edge the mask crossed. (The
        // wet branch above already returned — this loop is pen/pencil splits.)
        for (i in workPoints.indices) {
            if (covered[i]) {
                flushRun()
            } else {
                run.add(workPoints[i])
            }
        }
        flushRun()

        return SegmentResult(surviving = survivors, affected = true)
    }

    private fun buildSegment(stroke: Stroke, run: List<PointF>): Stroke =
        Stroke(
            id = java.util.UUID.randomUUID().toString(),
            tool = stroke.tool,
            colorInt = stroke.colorInt,
            width = stroke.width,
            filled = stroke.filled,
            text = stroke.text,
            points = run,
            start = run.first(),
            end = run.last(),
            pdfPage = stroke.pdfPage,
            timestampMs = stroke.timestampMs,
            isAdvanced = stroke.isAdvanced,
            layerId = stroke.layerId
        )

    /**
     * Whether a round mask stamp at `(cx, cy)` with radius [r] clears any of
     * [points] that is not already cleared by an existing or queued mask. Used by
     * the wet partial-erase path to keep `stroke.eraseMask` minimal (unbounded
     * accumulation would bloat the encrypted pointsJson toward the B2-DOS-01
     * budget). Pure JVM, allocation-free on the hot loop.
     */
    private fun maskStillCarves(
        points: List<PointF>,
        cx: Float,
        cy: Float,
        r: Float,
        existing: List<com.authorss81.noteflow.data.model.EraseMask>,
        queued: List<com.authorss81.noteflow.data.model.EraseMask>
    ): Boolean {
        val r2 = r * r
        for (p in points) {
            val dx = p.x - cx
            val dy = p.y - cy
            if (dx * dx + dy * dy <= r2) {
                if (pointClearedBy(p, existing) || pointClearedBy(p, queued)) continue
                return true
            }
        }
        return false
    }

    private fun pointClearedBy(p: PointF, masks: List<com.authorss81.noteflow.data.model.EraseMask>): Boolean {
        for (m in masks) {
            val dx = p.x - m.x
            val dy = p.y - m.y
            if (dx * dx + dy * dy <= m.radius * m.radius) return true
        }
        return false
    }
}