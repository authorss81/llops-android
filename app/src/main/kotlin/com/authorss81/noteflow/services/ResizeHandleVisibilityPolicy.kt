package com.authorss81.noteflow.services

/**
 * Resize-handle visibility decision table (Phase 193, refined phase-217).
 *
 * Pure JVM. One consistent policy shared by every draggable canvas item
 * (sticky notes, photo embeds, code blocks, audio cards, stickers): corner
 * resize symbols are DIMLY VISIBLE at rest (phase-217: raised from 0f to
 * [HIDDEN_HANDLE_ALPHA] = 0.45f so the hit-box is discoverable) and fade to
 * full opacity while the item is being dragged/resized. The handle hit-targets
 * themselves stay composed (they are not removed from the layout), so resizing
 * still works even when the visual affordance is dim — the gesture can start
 * from the item body / the dim corner hit-box.
 */
object ResizeHandleVisibilityPolicy {

    /** Visual side length of a corner handle hit-box (dp). */
    const val HANDLE_SIZE_DP = 24f

    /** Visual side length of the rotation handle hit-box (dp). */
    const val ROTATION_HANDLE_SIZE_DP = 26f

    /** Canvas embeds do NOT show handles at rest (dimly visible via [HIDDEN_HANDLE_ALPHA]). */
    fun visibleAtRest(): Boolean = false

    /**
     * Markdown code blocks always show their bottom-edge handle at rest (phase-217).
     * This separates the markdown "always dim" policy from the canvas "dim at rest"
     * policy.
     */
    fun markdownVisibleAtRest(): Boolean = true

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
     * composed at [HIDDEN_HANDLE_ALPHA] (0.45f — phase-217) so the resize
     * gesture can still start there AND the handle is discoverable; active
     * handles render at full opacity.
     */
    fun handleAlpha(visible: Boolean): Float = if (visible) 1f else HIDDEN_HANDLE_ALPHA

    /**
     * Visual alpha for a markdown code-block handle. Markdown handles are
     * ALWAYS at [MARKDOWN_HANDLE_ALPHA] (0.45f) — dim but discoverable —
     * and never fade to full because the block isn't a draggable canvas item.
     */
    fun markdownHandleAlpha(): Float = MARKDOWN_HANDLE_ALPHA

    /**
     * Dim-but-visible alpha for handles at rest. Phase-217 raised this from
     * 0f to 0.45f so corner resize symbols are discoverable without being
     * visually heavy.
     */
    const val HIDDEN_HANDLE_ALPHA = 0.45f

    /**
     * Always-on dim alpha for markdown code-block resize handles. Same visual
     * weight as [HIDDEN_HANDLE_ALPHA] but applied unconditionally (the handle
     * is always visible, not just while interacting).
     */
    const val MARKDOWN_HANDLE_ALPHA = 0.45f

    /** Fixed strings for min/max resize feedback (phase-217). */
    const val RESIZE_MIN_WIDTH_TOAST = "Minimum width reached"
    const val RESIZE_MAX_WIDTH_TOAST = "Maximum width reached"
    const val RESIZE_MIN_HEIGHT_TOAST = "Minimum height reached"
    const val RESIZE_MAX_HEIGHT_TOAST = "Maximum height reached"
}
