package com.authorss81.noteflow.services

/**
 * Phase 205: the ONE derivation for the full-list payload the canvas emits
 * after a scoped (current-page) change.
 *
 * Defect being closed: every emit site used to rebuild the payload from the
 * `strokes` list CAPTURED by the gesture/dialog closure — a frozen snapshot of
 * whatever composition last restarted the `pointerInput` block. Combined with
 * the pre-205 ASYNC commit (background launch + Main hop) that stale snapshot
 * resurrected erased strokes and reordered rapid commits. The fix routes every
 * emit through the CALLER'S current-state provider (`currentStrokesProvider`),
 * so "other pages" is derived from the live parent state AT APPLY TIME, never
 * from capture time, while the scoped replacement (the canvas' own authoritative
 * page list) supplies the active page's strokes in gesture order.
 *
 * Semantics are byte-identical to the pre-205 expressions:
 *  - continuous mode: the scoped replacement IS the whole document;
 *  - paginated mode: every stroke on another page (from CURRENT state) followed
 *    by the active page's strokes.
 */
object CanvasCommitListPolicy {

    /**
     * @param currentAll        the parent's CURRENT full list, read at apply time
     *                          via the provider — never a captured parameter.
     * @param isContinuousMode  seamless world: the scoped list covers everything.
     * @param pageOf            per-item page key (`Stroke::pdfPage` et al).
     * @param pdfPageFilter     the active page the change happened on.
     * @param scopedReplacement the authoritative post-change items of the ACTIVE
     *                          page, already in final order (commit order).
     */
    fun <T> emittedList(
        currentAll: List<T>,
        isContinuousMode: Boolean,
        pageOf: (T) -> Int,
        pdfPageFilter: Int,
        scopedReplacement: List<T>
    ): List<T> =
        if (isContinuousMode) {
            scopedReplacement
        } else {
            currentAll.filter { pageOf(it) != pdfPageFilter } + scopedReplacement
        }
}
