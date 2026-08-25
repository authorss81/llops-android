package com.authorss81.noteflow.services

/**
 * Phase 208 review-fix (finding 2): "Move to Section…" must never silently
 * re-parent a page ACROSS notebooks.
 *
 * The vault-search corpus spans every notebook, so search-result cards can
 * carry foreign-notebook pages; the section picker lists only the ACTIVE
 * notebook's sections, and `movePage` is an unvalidated sectionId UPDATE —
 * picking one used to yank a foreign page into this notebook. A page is
 * movable iff its CURRENT section belongs to the notebook that owns the
 * picker's section list (section ids are globally unique, so membership in the
 * active notebook's id set is an exact ownership test — no extra lookups).
 * Pure JVM; no Android imports.
 */
object MoveSectionScopePolicy {

    /**
     * Partition [pageIds] into `(movable, blocked)` for a move into the active
     * notebook. [sectionIdOfPage] returns the page's current sectionId (null if
     * unknown — treated as blocked). [activeNotebookSectionIds] is the picker's
     * section list. An empty active set blocks everything (fail closed).
     */
    fun partition(
        pageIds: Collection<String>,
        sectionIdOfPage: (String) -> String?,
        activeNotebookSectionIds: Set<String>
    ): Pair<List<String>, List<String>> {
        val movable = ArrayList<String>(pageIds.size)
        val blocked = ArrayList<String>()
        for (id in pageIds) {
            val sid = sectionIdOfPage(id)
            if (sid != null && sid in activeNotebookSectionIds) {
                movable.add(id)
            } else {
                blocked.add(id)
            }
        }
        return Pair(movable, blocked)
    }
}
