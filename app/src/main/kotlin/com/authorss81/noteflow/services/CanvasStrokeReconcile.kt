package com.authorss81.noteflow.services

/**
 * Reconciles a canvas's live local item list against the latest committed
 * snapshot from the editor (Phase 257 — undo resurrect + load-vs-save race).
 *
 * The AnnotationCanvas keeps a MUTABLE local list (strokes / sticky notes /
 * media embeds) so in-progress drawing renders immediately without waiting for
 * the editor's `strokes` state to propagate back down through recomposition.
 * Commit 074341b/d778cf9 added the `lastSeen` guard: when a committed snapshot
 * arrives, only items that were NEVER seen as committed are retained as pending
 * locals; items that were previously committed and then intentionally removed
 * (undo, eraser, cut, layer delete) are dropped. Without the guard an undo that
 * removes a stroke re-appeared because the stale local list still held it and
 * the blind document-level diff treated it as "not yet committed".
 */
object CanvasStrokeReconcile {

    /**
     * Returns the next list for a live local list `active` given the latest
     * committed snapshot `incoming`, keeping ONLY never-seen locals as pending.
     *
     * Semantics mirror the original LaunchedEffect body exactly:
     *   1. start from the committed snapshot (the authoritative editor state);
     *   2. append every `active` item whose id is absent from BOTH `incoming`
     *      and `lastSeen` (a brand-new local waiting for the editor round-trip);
     *   3. `lastSeen` must then be set to the incoming ids by the caller.
     *
     * An item in `lastSeen` that subsequently vanished from `incoming` is an
     * intentional removal and is NOT re-added.
     */
    fun <T> reconcile(
        active: List<T>,
        lastSeen: Set<String>,
        incoming: List<T>,
        idOf: (T) -> String
    ): List<T> {
        val incomingIds = HashSet<String>(incoming.size).apply { incoming.forEach { add(idOf(it)) } }
        val result = ArrayList<T>(incoming.size + 4)
        result.addAll(incoming)
        for (item in active) {
            if (idOf(item) !in incomingIds && idOf(item) !in lastSeen) {
                result.add(item)
            }
        }
        return result
    }
}