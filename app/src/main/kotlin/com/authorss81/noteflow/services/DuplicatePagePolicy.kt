package com.authorss81.noteflow.services

/**
 * Phase 208 (fixes #3 + #4): page-management verb policy.
 *
 * Holds the two small decision tables the new Move/Duplicate menu items and the
 * multi-select bulk-action bar share, so the List card menu, the Gallery card
 * menu and the bulk bar can never drift apart on labels or availability.
 *
 * - [duplicateTitle] derives the copy's title from the source title (the
 *   repository re-encrypts it under the NEW record AAD — ciphertext is never
 *   copied verbatim because field AEAD binds `pages|<pageId>|title`).
 * - [BulkVerbs] decides which actions a selection bar offers for a given tab
 *   context. Trash-context selections offer RESTORE + DELETE PERMANENTLY; live
 *   selections offer TRASH (recoverable) + MOVE + TAG. Pure JVM, no Android
 *   imports.
 */
object DuplicatePagePolicy {

    /** Suffix appended to a duplicated note's title (before encryption). */
    const val COPY_SUFFIX = " (Copy)"

    /**
     * Derive the duplicate's display/stored title. Appends [COPY_SUFFIX] unless
     * the title already ends with it (duplicating a copy reads "X (Copy)" not
     * "X (Copy) (Copy)"). Blank titles become "Untitled" + suffix so the copy
     * is never an empty string.
     */
    fun duplicateTitle(originalTitle: String): String {
        val base = originalTitle.trim()
        if (base.isEmpty()) return "Untitled$COPY_SUFFIX"
        return if (base.endsWith(COPY_SUFFIX)) base else base + COPY_SUFFIX
    }

    /** Bulk-action verbs offered by the multi-select contextual bar. */
    enum class BulkVerb {
        MOVE_TO_TRASH,
        RESTORE,
        DELETE_PERMANENTLY,
        MOVE_TO_SECTION,
        EDIT_TAGS
    }

    /**
     * Verbs available for the CURRENT multi-selection, in bar render order.
     * [trashContext] mirrors [TrashSearchScopePolicy.isTrashContext] (tab 3).
     */
    fun bulkVerbs(trashContext: Boolean): List<BulkVerb> = if (trashContext) {
        listOf(BulkVerb.RESTORE, BulkVerb.DELETE_PERMANENTLY)
    } else {
        listOf(
            BulkVerb.MOVE_TO_TRASH,
            BulkVerb.MOVE_TO_SECTION,
            BulkVerb.EDIT_TAGS
        )
    }
}
