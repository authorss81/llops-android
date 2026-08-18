package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.NotePageEntity

/**
 * Phase 133: pure-JVM decision table for resolving the currently active page.
 *
 * The bug: MainActivity resolved the active page ONLY against `viewModel.pages`
 * (the section-filtered Room flow). Room-backed lists emit asynchronously, so a
 * brand-new page — Add Page FAB, Daily Journal entry, wiki-link create, shared-
 * content note — is missing from `pages` on the immediate creation frame and
 * `pages.find { it.id == activePageId }` returned null, losing the transition.
 *
 * [resolve] picks the first match in **order of precedence**:
 * 1. the SYNCHRONOUS in-memory copy (the page just created/opened — available on
 *    the exact frame of creation, no Room round-trip);
 * 2. the global all-active-pages list (catches a page created in a section that
 *    is not the currently observed one);
 * 3. the section-filtered `pages` list (the original source).
 */
object ActivePageResolution {

    /**
     * Returns the entity whose [NotePageEntity.id] equals [activePageId], or
     * null when nothing matches. [synchronous] wins when its id matches, then
     * [allActivePages], then [sectionPages].
     */
    fun resolve(
        activePageId: String?,
        synchronous: NotePageEntity?,
        allActivePages: List<NotePageEntity>,
        sectionPages: List<NotePageEntity>
    ): NotePageEntity? {
        val id = activePageId ?: return null
        if (synchronous != null && synchronous.id == id) return synchronous
        allActivePages.firstOrNull { it.id == id }?.let { return it }
        return sectionPages.firstOrNull { it.id == id }
    }
}

/**
 * Phase 133: the synchronous in-memory tracker state MainActivity holds for the
 * active page. [synchronous] is the page captured at open/create time (before
 * Room emits); [confirmed] records whether the page has been seen in an
 * authoritative (Room-backed) list.
 */
data class ActivePageTrackerState(
    val id: String? = null,
    val synchronous: NotePageEntity? = null,
    val confirmed: Boolean = false
)

/**
 * Phase 133: pure-JVM state machine that keeps the synchronous active-page copy
 * ([ActivePageTrackerState.synchronous]) in lock-step with the authoritative
 * async Room lists.
 */
object ActivePageTracker {

    /**
     * A page becomes the active page. Returns an idle state for null (back
     * navigation / close); otherwise captures the page synchronously and marks
     * it [ActivePageTrackerState.confirmed] only when an authoritative list
     * already knows it (an existing page opened from a list). A brand-new page
     * is unconfirmed until the Room flows emit — exactly the window the old
     * code lost the transition in.
     */
    fun open(
        current: ActivePageTrackerState,
        page: NotePageEntity?,
        allActivePages: List<NotePageEntity>,
        sectionPages: List<NotePageEntity>
    ): ActivePageTrackerState {
        if (page == null) return ActivePageTrackerState()
        val knownToDb = allActivePages.any { it.id == page.id } || sectionPages.any { it.id == page.id }
        return ActivePageTrackerState(id = page.id, synchronous = page, confirmed = knownToDb)
    }

    /**
     * Re-run whenever an authoritative (Room-backed) list re-emits.
     * - The page is present: refresh the synchronous copy to the authoritative
     *   instance (fresh title/tags/etc.) and mark it confirmed.
     * - The page is absent BUT was previously confirmed: it was genuinely
     *   deleted/trashed — return an idle state so the editor never keeps
     *   rendering a stale entity.
     * - The page is absent and NOT yet confirmed: this is the creation-frame
     *   race — keep the synchronous copy untouched so the editor stays open.
     */
    fun onAuthoritative(
        current: ActivePageTrackerState,
        allActivePages: List<NotePageEntity>,
        sectionPages: List<NotePageEntity>
    ): ActivePageTrackerState {
        val id = current.id ?: return current
        val authoritative = allActivePages.firstOrNull { it.id == id }
            ?: sectionPages.firstOrNull { it.id == id }
        return when {
            authoritative != null -> current.copy(synchronous = authoritative, confirmed = true)
            current.confirmed -> ActivePageTrackerState()
            else -> current
        }
    }

    /**
     * Re-arms the active page from a persisted page id (launch / unlock / config
     * change). Returns an idle state when [savedId] is blank or no longer exists
     * in any authoritative list.
     */
    fun restore(
        savedId: String?,
        allActivePages: List<NotePageEntity>,
        sectionPages: List<NotePageEntity>
    ): ActivePageTrackerState {
        if (savedId.isNullOrBlank()) return ActivePageTrackerState()
        val page = sectionPages.firstOrNull { it.id == savedId }
            ?: allActivePages.firstOrNull { it.id == savedId }
        if (page == null) return ActivePageTrackerState()
        return ActivePageTrackerState(id = page.id, synchronous = page, confirmed = true)
    }
}
