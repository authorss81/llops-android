package com.authorss81.noteflow.services

/**
 * Phase 186 — gallery card quick-action menu policy.
 *
 * Pure JVM decision table for the gallery grid card's overflow menu + pinned
 * badge, so the labels / badge rule / destructive-tint rule are ONE testable
 * source (the List view spells these strings inline in `NotePageCard`; the
 * gallery card sources them from here).
 *
 * Scope: the gallery is a NON-trash home view, so the menu carries the three
 * active-page actions — Pin/Unpin, Edit Tags, Move to Trash — mirroring the
 * list view's `MoreVert` items minus Rename (the card's ~140dp text column has
 * no room for a fourth action and the tap-to-open still handles opening; Rename
 * is out of the compact scope per the phase prompt). The single destructive
 * action is "Move to Trash" — recoverable via the Trash tab's Restore — which
 * the existing `NoteflowViewModel.trashPage` provides. The app's truly
 * destructive action ("Delete Permanently") stays behind its confirmation gate
 * (HomeScreen.kt deleteConfirmType) and is deliberately NOT offered here.
 */
object GalleryCardActionsPolicy {

    const val PIN_LABEL = "Pin"
    const val UNPIN_LABEL = "Unpin"
    const val EDIT_TAGS_LABEL = "Edit Tags"
    const val MOVE_TO_TRASH_LABEL = "Move to Trash"

    /** Ordered menu items for a gallery (non-trash) card in the row order shown. */
    fun menuItems(pinned: Boolean): List<String> =
        listOf(pinMenuLabel(pinned), EDIT_TAGS_LABEL, MOVE_TO_TRASH_LABEL)

    /** Whether the pinned badge renders on the card. */
    fun showPinnedBadge(pinned: Boolean): Boolean = pinned

    /** Overflow-menu label for a pinned/unpinned page. */
    fun pinMenuLabel(pinned: Boolean): String = if (pinned) UNPIN_LABEL else PIN_LABEL

    /** Content description for the pin toggle (accessibility). */
    fun pinContentDescription(pinned: Boolean): String =
        if (pinned) "Unpin this note" else "Pin this note"

    /** The item is the destructive trash action → error colour in the menu. */
    fun isDestructive(item: String): Boolean = item == MOVE_TO_TRASH_LABEL
}