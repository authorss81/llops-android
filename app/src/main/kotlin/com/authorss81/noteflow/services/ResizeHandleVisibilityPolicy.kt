package com.authorss81.noteflow.services

/**
 * Resize-handle visibility decision table (Phase 193).
 *
 * Pure JVM. One consistent policy shared by every draggable canvas item
 * (sticky notes, photo embeds, code blocks, audio cards, stickers): corner
 * resize symbols are INVISIBLE at rest and only appear while the item is
 * being dragged/resized. The handle hit-targets themselves stay composed
 * (they are not removed from the layout), so resizing still works even when
 * the visual affordance is hidden — the gesture can start from the item
 * body / the (invisible) corner hit-box.
 */
object ResizeHandleVisibilityPolicy {

    /** Visual side length of a corner handle hit-box (dp). */
    const val HANDLE_SIZE_DP = 24f

    /** Visual side length of the rotation handle hit-box (dp). */
    const val ROTATION_HANDLE_SIZE_DP = 26f

    /** A resting item never shows its resize symbols. */
    fun visibleAtRest(): Boolean = false

    /**
     * Handles are visible only while the item is being actively dragged or
     * resized. [interacting] is the item's interaction flag (true while any
     * drag gesture — body move or corner/rotation resize — is running).
     */
    fun visibleWhileActive(interacting: Boolean): Boolean = interacting

    /**
     * Full decision for rendering a handle's VISUAL layer.
     * Collapsed items (collapsed audio card, collapsed sticky note) never
     * show handles even while interacted with.
     */
    fun shouldShow(interacting: Boolean, collapsed: Boolean): Boolean =
        !collapsed && visibleWhileActive(interacting)

    /**
     * Visual alpha for a handle hit-box. Hidden handles keep their hit-target
     * composed at [HIDDEN_HANDLE_ALPHA] so the resize gesture can still start
     * there; active handles render at full opacity.
     */
    fun handleAlpha(visible: Boolean): Float = if (visible) 1f else HIDDEN_HANDLE_ALPHA

    /** Invisible-but-present handles keep a (real) 0 alpha. */
    const val HIDDEN_HANDLE_ALPHA = 0f
}
