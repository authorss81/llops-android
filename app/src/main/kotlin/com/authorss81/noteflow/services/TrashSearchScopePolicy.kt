package com.authorss81.noteflow.services

/**
 * Phase 208 (fix #1, CRITICAL data-loss bug): search results must be SCOPED to
 * the active home tab before they are rendered.
 *
 * The bug: `activePageList` replaced the whole list with `globalSearchResults`
 * whenever a query was present — regardless of [TAB_TRASH] being selected. Those
 * results come ONLY from non-deleted rows (`NotePageDao.searchPages` /
 * the cached corpus are both `WHERE deleted = 0`), yet the card renderer applied
 * `isTrash = selectedTab == 3`. A live note therefore rendered with the
 * Restore / **Delete Permanently** menu while the user sat on the Trash tab —
 * one tap from unrecoverable deletion of a perfectly healthy note.
 *
 * The contract:
 *  - No query → the tab branches already pick the right source list; scoping is
 *    a no-op ([Scope.LIVE_RESULTS]).
 *  - Query + a LIVE tab (Pages/Recent/Tag Vault) → raw search results render
 *    with `isTrash = false` — correct as-is.
 *  - Query + Trash tab → the result set is INTERSECTED with the actually-trashed
 *    ids. Today's search backend never returns deleted rows, so this yields the
 *    honest scoped-empty state ("no trashed notes match"); if the backend ever
 *    grows trashed coverage, the intersection keeps rendering correct trash
 *    cards instead of resurrecting the bug. Pure JVM; no Android imports.
 */
object TrashSearchScopePolicy {

    const val TAB_PAGES = 0
    const val TAB_RECENT = 1
    const val TAB_TAG_VAULT = 2
    const val TAB_TRASH = 3

    enum class Scope {
        /** Render the raw search-result list (live notes, `isTrash = false`). */
        LIVE_RESULTS,

        /** Intersect the search results with the trashed-id set before rendering. */
        TRASH_INTERSECT
    }

    /** Whether [selectedTab] renders cards in the recoverable-trash posture. */
    fun isTrashContext(selectedTab: Int): Boolean = selectedTab == TAB_TRASH

    /**
     * Decide the result scoping for a tab × query combination. Unknown tab
     * indices fail SAFE to [Scope.LIVE_RESULTS] (never destructive).
     */
    fun scopeFor(selectedTab: Int, hasQuery: Boolean): Scope =
        if (hasQuery && isTrashContext(selectedTab)) Scope.TRASH_INTERSECT else Scope.LIVE_RESULTS

    /**
     * Apply a [Scope] to a result list. [idOf] extracts the page id so the
     * policy stays decoupled from Room types. TRASH_INTERSECT keeps only rows
     * whose id is in [trashedIds]; LIVE_RESULTS passes through untouched.
     */
    fun <T> scoped(
        results: List<T>,
        scope: Scope,
        trashedIds: Set<String>,
        idOf: (T) -> String
    ): List<T> = when (scope) {
        Scope.LIVE_RESULTS -> results
        Scope.TRASH_INTERSECT -> results.filter { idOf(it) in trashedIds }
    }
}
