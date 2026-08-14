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

    data class ErasePoint(val x: Float, val y: Float)
    data class SegmentResult(val surviving: List<Stroke>, val affected: Boolean)

    /**
     * Extra hit distance added to the stroke's own width, mirroring the classic
     * whole-stroke eraser threshold in AnnotationCanvas (`stroke.width + 18f`).
     */
    const val DEFAULT_EXTRA_RADIUS = 18f

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

        val radius = (stroke.width + extraRadius).coerceAtLeast(1f)
        val r2 = radius * radius
        val covered = BooleanArray(stroke.points.size)
        var anyCovered = false
        for (i in stroke.points.indices) {
            val p = stroke.points[i]
            for (e in eraseSamples) {
                val dx = p.x - e.x
                val dy = p.y - e.y
                if (dx * dx + dy * dy <= r2) {
                    covered[i] = true
                    anyCovered = true
                    break
                }
            }
        }

        if (!anyCovered) {
            return SegmentResult(listOf(stroke), affected = false)
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
     * threshold). Used for the empty-points fallback rule above.
     */
    fun strokeTouchedBy(stroke: Stroke, eraseSamples: List<ErasePoint>, extraRadius: Float): Boolean {
        if (eraseSamples.isEmpty()) return false
        val radius = (stroke.width + extraRadius).coerceAtLeast(1f)
        val r2 = radius * radius
        fun hit(x: Float, y: Float): Boolean {
            for (e in eraseSamples) {
                val dx = x - e.x
                val dy = y - e.y
                if (dx * dx + dy * dy <= r2) return true
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