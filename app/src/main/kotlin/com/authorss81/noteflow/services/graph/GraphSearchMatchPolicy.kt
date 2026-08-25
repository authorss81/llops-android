package com.authorss81.noteflow.services.graph

import kotlin.math.abs

/**
 * Phase 210 — graph search navigation math. Search used to ONLY recolor title
 * matches; this policy owns the two pieces that turn it into navigation:
 *
 *  - **ordered matching** ([orderedMatches]): case-insensitive contains over
 *    node titles, ranked prefix > word-start > elsewhere and stable within a
 *    tier (input order) so Enter-cycling visits matches deterministically;
 *  - **pan targets** ([panToCenter]): the `graphicsLayer` translation that
 *    centers a world-space point at the current zoom. The screen maps taps as
 *    `world = (screen - center - pan) / zoom`, i.e.
 *    `screen = center + (world - center)·zoom + pan`; solving for
 *    `screen(world) = center` gives `pan = (center - world)·zoom`.
 *
 * Pure JVM + fail-safe: non-finite or non-positive zoom inputs collapse to the
 * identity pan instead of throwing or producing NaN offsets mid-gesture.
 */
object GraphSearchMatchPolicy {

    /** Rank tiers: lower sorts first. */
    const val RANK_PREFIX = 0
    const val RANK_WORD_START = 1
    const val RANK_CONTAINS = 2

    data class PanTarget(val panX: Float, val panY: Float)

    /** Rank of [query] inside [title]; [Int.MAX_VALUE] when it does not match. */
    fun rankOf(title: String, query: String): Int {
        if (query.isEmpty()) return Int.MAX_VALUE
        val t = title.lowercase()
        val q = query.lowercase()
        val idx = t.indexOf(q)
        if (idx < 0) return Int.MAX_VALUE
        return when {
            idx == 0 -> RANK_PREFIX
            t[idx - 1].isWhitespace() -> RANK_WORD_START
            else -> RANK_CONTAINS
        }
    }

    /**
     * Ids whose titles contain [query], best tier first, input order preserved
     * inside a tier. Blank query → empty list (never "everything matches").
     */
    fun orderedMatches(
        query: String,
        entries: List<Pair<String, String>>
    ): List<String> {
        val q = query.trim()
        if (q.isEmpty() || entries.isEmpty()) return emptyList()
        return entries
            .map { (id, title) -> Triple(id, rankOf(title, q), id) }
            .filter { it.second != Int.MAX_VALUE }
            .sortedWith(compareBy({ it.second }, { it.third }))
            .map { it.first }
    }

    /** Enter cycles: wraps back to 0; empty lists never move the index. */
    fun nextIndex(current: Int, size: Int): Int {
        if (size <= 0) return 0
        val base = if (current < 0) 0 else current
        return (base + 1) % size
    }

    /**
     * Translation that centers the world point `(worldX, worldY)` in a viewport
     * of `[viewportW × viewportH]` at [zoom]. See the class doc for the algebra;
     * degenerate inputs (non-finite geometry, zoom ≤ 0) fail safe to (0, 0).
     */
    fun panToCenter(
        worldX: Float,
        worldY: Float,
        viewportW: Float,
        viewportH: Float,
        zoom: Float
    ): PanTarget {
        if (!isFinite(worldX) || !isFinite(worldY) ||
            !isFinite(viewportW) || !isFinite(viewportH) ||
            !isFinite(zoom) || zoom <= 0f ||
            viewportW <= 0f || viewportH <= 0f
        ) {
            return PanTarget(0f, 0f)
        }
        val cx = viewportW / 2f
        val cy = viewportH / 2f
        // Guard absurd magnitudes so a hostile layout can never fling the canvas
        // outside float precision.
        val px = ((cx - worldX) * zoom).let { if (abs(it) > 1e7f) 0f else it }
        val py = ((cy - worldY) * zoom).let { if (abs(it) > 1e7f) 0f else it }
        return PanTarget(px, py)
    }

    private fun isFinite(v: Float): Boolean = !v.isNaN() && !v.isInfinite()
}
