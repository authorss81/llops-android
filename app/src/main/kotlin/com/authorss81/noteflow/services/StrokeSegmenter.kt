package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke

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
        fun hit(s: Stroke, px: Float, py: Float): Boolean {
            val threshold = (s.width + extraRadius).coerceAtLeast(1f)
            val r2 = threshold * threshold
            fun inRange(qx: Float, qy: Float): Boolean {
                val dx = qx - px
                val dy = qy - py
                return dx * dx + dy * dy <= r2
            }
            for (p in s.points) if (inRange(p.x, p.y)) return true
            s.start?.let { if (inRange(it.x, it.y)) return true }
            s.end?.let { if (inRange(it.x, it.y)) return true }
            return false
        }
        val mirror = SymmetryHelper.mirrorPoint(x, y, symmetryMode, symmetryCenterX, symmetryCenterY)
        val checkMirror = symmetryMode != SymmetryMode.OFF
        return strokes.lastOrNull { s ->
            hit(s, x, y) || (checkMirror && hit(s, mirror.x, mirror.y))
        }
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

        val covered = BooleanArray(stroke.points.size)
        var anyCovered = false
        for (i in stroke.points.indices) {
            val p = stroke.points[i]
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

        if (!anyCovered) {
            return SegmentResult(listOf(stroke), affected = false)
        }

        // Fix 2026-08-27: wet pigments are translucent (alpha 0.3-0.6) + Beer-Lambert blend
        // Splitting into 0..n translucent fragments draws overlapping round caps twice → darker common part.
        // Keep PARTIAL for pen/pencil (opaque) but make wet behave as whole-stroke (no fragments, no dark seam).
        // True mask-based partial (single raster + Clear punch) is deferred to keep no-schema guarantee.
        if (com.authorss81.noteflow.services.BrushStrokeMath.isWetRenderedTool(stroke.tool)) {
            return SegmentResult(surviving = emptyList(), affected = true)
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

        for (i in stroke.points.indices) {
            if (covered[i]) {
                flushRun()
            } else {
                run.add(stroke.points[i])
            }
        }
        flushRun()

        return SegmentResult(surviving = survivors, affected = true)
    }

    /**
     * Whole-stroke hit test over a point list (equivalent to the classic eraser
     * threshold). Radius per sample follows [coverageRadiusFor] so a Phase 124
     * stamp (radius-aware) and a legacy stamp behave consistently. Used for the
     * empty-points fallback rule above.
     */
    fun strokeTouchedBy(stroke: Stroke, eraseSamples: List<ErasePoint>, extraRadius: Float): Boolean {
        if (eraseSamples.isEmpty()) return false
        fun hit(x: Float, y: Float): Boolean {
            for (e in eraseSamples) {
                val r = coverageRadiusFor(stroke, e, extraRadius).coerceAtLeast(1f)
                val dx = x - e.x
                val dy = y - e.y
                if (dx * dx + dy * dy <= r * r) return true
            }
            return false
        }
        for (p in stroke.points) if (hit(p.x, p.y)) return true
        val startHit = stroke.start?.let { hit(it.x, it.y) } ?: false
        if (startHit) return true
        val endHit = stroke.end?.let { hit(it.x, it.y) } ?: false
        return endHit
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
}