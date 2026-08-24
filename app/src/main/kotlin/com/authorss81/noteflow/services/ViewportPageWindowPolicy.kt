package com.authorss81.noteflow.services

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Phase 198 (PERF 2.5): O(visiblePages) viewport page window for the paginated
 * canvas branch.
 *
 * The pre-198 culling loop iterated EVERY page index `0 until pageCount` and
 * skipped off-screen slabs with a per-page `continue` — correct, but still
 * O(totalPages) loop iterations per frame on long documents. Pages in this
 * canvas are fixed-stride slabs: page i occupies the world band
 * `[i * stride, i * stride + slabHeight]` where `stride = slabHeight + gap`,
 * so the visible index range is closed-form:
 *
 *   first = ceil((viewportTop - slabHeight) / stride)
 *   last  = floor(viewportBottom / stride)
 *
 * A page counts as visible when its slab TOUCHES the viewport band
 * (`>=`/`<=`), matching the pre-198 skip predicate
 * (`pageBottom < top || pageTop > bottom -> skip`) exactly — boundary-touching
 * pages keep drawing.
 *
 * Fail-safe direction: any non-finite/inverted/degenerate input over-draws
 * (returns the FULL page range) rather than under-draws — a math edge case can
 * never blank user ink (AGENTS.md hardware-reality rule). Only a non-positive
 * page count yields an empty range.
 */
object ViewportPageWindowPolicy {

    /**
     * Inclusive index range of pages whose slab intersects the world-space
     * viewport band [viewportTop, viewportBottom]. Empty when no page is
     * visible (or [pageCount] <= 0).
     */
    fun visiblePageRange(
        viewportTop: Float,
        viewportBottom: Float,
        pageStride: Float,
        pageSlabHeight: Float,
        pageCount: Int
    ): IntRange {
        if (pageCount <= 0) return IntRange.EMPTY
        val lastUsable = pageCount - 1
        val degenerate = !viewportTop.isFinite() ||
            !viewportBottom.isFinite() ||
            !pageStride.isFinite() ||
            !pageSlabHeight.isFinite() ||
            pageStride <= 0f ||
            pageSlabHeight < 0f ||
            viewportTop > viewportBottom
        if (degenerate) return 0..lastUsable
        val firstIdx = ceil((viewportTop - pageSlabHeight) / pageStride).toInt()
        val lastIdx = floor(viewportBottom / pageStride).toInt()
        // Unclamped window first: a viewport entirely ABOVE page 0 (negative
        // indices) or entirely BELOW the last page must stay EMPTY — the
        // pre-198 per-page predicate drew nothing there, and clamping before
        // this check would wrongly pull page 0 back in.
        if (lastIdx < 0 || firstIdx > lastUsable) return IntRange.EMPTY
        val lo = firstIdx.coerceIn(0, lastUsable)
        val hi = lastIdx.coerceIn(0, lastUsable)
        return if (lo > hi) IntRange.EMPTY else lo..hi
    }
}
