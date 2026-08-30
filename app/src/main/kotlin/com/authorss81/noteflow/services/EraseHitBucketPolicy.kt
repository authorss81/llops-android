package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.Stroke
import kotlin.math.floor
import kotlin.math.max

/**
 * Phase 249 (Bug 4): fixed-size spatial hash that buckets a stroke list by
 * WORLD-space bounding box, so the eraser never scans every stroke on the page
 * per drag sample (the pre-249 `applyEraser` was O(strokes × points ×
 * samples) per sample — quadratic on long eraser drags over dense notes →
 * frame stutter / "dots far from touch").
 *
 * Pure JVM, no Android/Compose deps. Bounds are computed from the same
 * geometry `strokeContainsPoint` uses (centerline points + start/end anchors),
 * so a stroke the eraser could actually hit is ALWAYS inside the bucket's
 * candidate superset: cells are sized >= 3× the selection radius, which keeps
 * every query-ring fetch to at most ~4 cells per sample. Candidates are then
 * filtered per-stroke by the exact coverage radius passed to
 * [candidatesWithinCircle].
 *
 * Thread-safety: NOT thread-safe. The canvas route is single-threaded (UI
 * thread, one gesture at a time); a bucket is (re)built per eraser drag start
 * and replaced incrementally as strokes are carved.
 */
class EraseHitBucketPolicy private constructor(
    private val cellSizePx: Float,
    private val selectionRadiusPx: Float,
    private val cells: MutableMap<Long, MutableList<CellEntry>>,
    private val cellKeysByStrokeId: MutableMap<String, MutableList<Long>>
) {

    private class CellEntry(val stroke: Stroke, val bounds: Bounds)

    /**
     * Strokes whose world bounding box intersects the circle of radius
     * [radiusFor]-per-stroke around (cx, cy). The CELL fetch uses the stored
     * uniform [selectionRadiusPx] — a superset of every stroke's [radiusFor] —
     * so the per-stroke filter below can never drop a stroke the eraser's
     * actual coverage rules could hit (no false negatives).
     */
    fun candidatesWithinCircle(
        cx: Float,
        cy: Float,
        radiusFor: (Stroke) -> Float
    ): List<Stroke> {
        if (selectionRadiusPx <= 0f) return emptyList()
        val minCellX = floor((cx - selectionRadiusPx) / cellSizePx).toLong()
        val maxCellX = floor((cx + selectionRadiusPx) / cellSizePx).toLong()
        val minCellY = floor((cy - selectionRadiusPx) / cellSizePx).toLong()
        val maxCellY = floor((cy + selectionRadiusPx) / cellSizePx).toLong()
        val seen = java.util.LinkedHashSet<Stroke>()
        for (cellX in minCellX..maxCellX) {
            for (cellY in minCellY..maxCellY) {
                val entryList = cells[cellKey(cellX, cellY)] ?: continue
                for (entry in entryList) {
                    if (seen.contains(entry.stroke)) continue
                    if (entry.bounds.intersectsCircle(cx, cy, radiusFor(entry.stroke))) {
                        seen.add(entry.stroke)
                    }
                }
            }
        }
        return seen.toList()
    }

    /**
     * Re-tile after a batch of strokes was erased/segmented. Incremental and
     * in place: only the [removed]/[added] strokes move, so a long drag never
     * re-scans the whole page. [removed] strokes are detached by IDENTITY
     * (a wet partial survivor may share the removed stroke's id — it is
     * detached and re-inserted by [added]).
     */
    fun replaceStrokes(removed: List<Stroke>, added: List<Stroke>) {
        for (stroke in removed) detach(stroke)
        for (stroke in added) insert(stroke)
    }

    private fun detach(stroke: Stroke) {
        val keys = cellKeysByStrokeId.remove(stroke.id) ?: return
        for (key in keys) {
            cells[key]?.removeAll { it.stroke === stroke }
        }
    }

    private fun insert(stroke: Stroke) {
        val bounds = computeBounds(stroke)
        val minCellX = floor(bounds.minX / cellSizePx).toLong()
        val maxCellX = floor(bounds.maxX / cellSizePx).toLong()
        val minCellY = floor(bounds.minY / cellSizePx).toLong()
        val maxCellY = floor(bounds.maxY / cellSizePx).toLong()
        val keys = mutableListOf<Long>()
        for (cellX in minCellX..maxCellX) {
            for (cellY in minCellY..maxCellY) {
                val key = cellKey(cellX, cellY)
                cells.getOrPut(key) { mutableListOf() }.add(CellEntry(stroke, bounds))
                keys.add(key)
            }
        }
        cellKeysByStrokeId[stroke.id] = keys
    }

    private fun cellKey(cellX: Long, cellY: Long): Long =
        ((cellX and 0xffffffffL) shl 32) or (cellY and 0xffffffffL)

    companion object {

        /**
         * Hard cap on the number of accumulated erase-path samples each
         * `applyEraser` pass processes (the coalesced-history burst size).
         * Anything older was already carved into the surviving strokes'
         * geometry / eraseMasks, so re-scanning it only re-does work and lets
         * a long drag grow the per-call cost without bound.
         */
        const val MAX_ERASE_SAMPLES_PER_APPLY = 8

        /**
         * Build a bucket over [strokes]. [maxStampRadiusPx] is the widest
         * eraser stamp for the gesture (full-pressure radius of the current
         * brush width); the selection radius is derived from the widest stroke
         * in the list so every coverage rule (whole-stroke `width + 18`,
         * pressure-stamped coverage) is a true subset of the query radius.
         */
        fun build(strokes: List<Stroke>, maxStampRadiusPx: Float): EraseHitBucketPolicy {
            val maxStrokeWidth = strokes.maxOfOrNull { it.width } ?: 0f
            val selectionRadius = max(
                com.authorss81.noteflow.services.EraserGeometryPolicy.coverageRadius(
                    maxStampRadiusPx,
                    maxStrokeWidth
                ),
                com.authorss81.noteflow.services.EraserGeometryPolicy.legacyRadius(
                    maxStrokeWidth,
                    com.authorss81.noteflow.services.StrokeSegmenter.DEFAULT_EXTRA_RADIUS
                )
            )
            val cell = max(DEFAULT_CELL_SIZE_PX, selectionRadius * 3f)
            val policy = EraseHitBucketPolicy(
                cell,
                selectionRadius,
                LinkedHashMap(),
                HashMap()
            )
            for (stroke in strokes) policy.insert(stroke)
            return policy
        }

        /** Default world-space cell size (px). */
        const val DEFAULT_CELL_SIZE_PX = 384f
    }
}

private class Bounds(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
    /** True if the AABB touches the circle of radius [r] around (cx, cy). */
    fun intersectsCircle(cx: Float, cy: Float, r: Float): Boolean {
        if (r <= 0f) return false
        val closeX = cx.coerceIn(minX, maxX)
        val closeY = cy.coerceIn(minY, maxY)
        val dx = closeX - cx
        val dy = closeY - cy
        return dx * dx + dy * dy <= r * r
    }
}

/**
 * World-space AABB of one stroke's centerline geometry (points + start/end
 * anchors), computed from the same geometry `strokeContainsPoint` uses so the
 * bucket's per-stroke circle test is a true superset of the eraser hit rules.
 */
private fun computeBounds(stroke: Stroke): Bounds {
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    fun include(x: Float, y: Float) {
        if (x.isFinite()) {
            if (x < minX) minX = x
            if (x > maxX) maxX = x
        }
        if (y.isFinite()) {
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
    }
    for (p in stroke.points) include(p.x, p.y)
    stroke.start?.let { include(it.x, it.y) }
    stroke.end?.let { include(it.x, it.y) }
    if (minX > maxX || minY > maxY) {
        // Degenerate (no points/anchors): zero-sized box at the origin,
        // which never intersects any meaningful query circle.
        return Bounds(0f, 0f, 0f, 0f)
    }
    return Bounds(minX, minY, maxX, maxY)
}