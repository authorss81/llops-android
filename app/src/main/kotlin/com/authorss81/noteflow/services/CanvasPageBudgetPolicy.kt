package com.authorss81.noteflow.services

/**
 * R2-b2b5-FEA-04 (phase-150): single decision table for the paginated-canvas
 * world-size ceiling and dynamic page-count clamp. Pure JVM.
 *
 * The vulnerability (see docs/security-report-round2.md R2-b2b5-FEA-04): the
 * DAO/restore guards cap `pointsJson` by LENGTH only, never by coordinate
 * VALUE, so a crafted backup can carry a single stroke point
 * `{"x":0,"y":1e9}` (short JSON, passes every length cap). The renderer then
 * computed:
 *
 *   maxStrokeY = max over all points of pt.y            (no upper clamp)
 *   calculatedPages = (maxStrokeY / (pageHeightPx + pageGapPx)).toInt() + 1
 *
 * giving ~628,141 pages, and `AnnotationCanvas` iterated `0 until
 * renderPageCount` on EVERY draw frame (viewport culling retires early via
 * `continue` but the loop is still paid), plus a full `List.filter { it.pdfPage
 * == pageIdx }` per non-culled page.
 *
 * This policy owns:
 *  - [MAX_DYNAMIC_PAGES] — the sizable page-count ceiling. 2000 pages × the
 *    ~1592 px page stride is a ~3.18M px-tall document; a genuine long note
 *    can never need more, and a crafted `y:1e9` is clamped to it.
 *  - [clampMaxStrokeY] — clamps the raw end-of-stroke Y to the ceiling before
 *    the page-count math so the count cannot even be derived out of bounds.
 *    Non-finite (`NaN`/`±Inf`) maxima collapse to 0 (a document never has a
 *    stroke at Infinity).
 *  - [clampCalculatedPages] — belt-and-braces cap on the final derived page
 *    count (a huge `visibleBottomY` from extreme panning is covered too).
 *
 * The per-page stroke filter hoist (the precomputed `Map<Int, List<Stroke>>`
 * so a page's strokes are a map lookup, never a whole-list filter per page)
 * lives in [com.authorss81.noteflow.ui.components.AnnotationCanvas]; this
 * policy is the arithmetic half, unit-testable without Compose.
 */
object CanvasPageBudgetPolicy {

    /** Sizable world ceiling: the largest dynamic page count a document can expose. */
    const val MAX_DYNAMIC_PAGES = 2000

    /**
     * The world-space Y ceiling for end-of-stroke content given a page's
     * vertical stride (page height + inter-page gap). A document whose strokes
     * end below this is folded to the ceiling — the equivalent fully-filled
     * page count is exactly [MAX_DYNAMIC_PAGES].
     */
    fun maxStrokeYCeiling(pageStride: Float): Float =
        (pageStride.toDouble() * MAX_DYNAMIC_PAGES).toFloat()

    /**
     * Clamp a raw max stroke Y to the world ceiling. Non-finite input (NaN /
     * ±Infinity, possible via a crafted `1e400` literal) collapses to 0 so the
     * derived page count can never run into float edge cases.
     */
    fun clampMaxStrokeY(maxStrokeY: Float, pageStride: Float): Float {
        if (!maxStrokeY.isFinite()) return 0f
        if (pageStride <= 0f) return maxStrokeY
        return maxStrokeY.toDouble().coerceAtMost(maxStrokeYCeiling(pageStride).toDouble()).toFloat()
    }

    /** Clamp a derived page count to the [1, MAX_DYNAMIC_PAGES] window. */
    fun clampCalculatedPages(calculatedPages: Int): Int =
        calculatedPages.coerceIn(1, MAX_DYNAMIC_PAGES)

    /** The page count a `maxY` currently maps to (unclamped derivation, shared with the renderer). */
    fun calculatedPagesFor(maxY: Float, pageStride: Float): Int {
        if (!maxY.isFinite() || pageStride <= 0f) return 1
        return (maxY / pageStride).toInt() + 1
    }
}