package com.authorss81.noteflow.services

/**
 * Posture + default-placeholder math for the floating ink bar (Phase 129).
 *
 * Pure JVM. Restores the pre-phase-35 posture rules as the single source of
 * truth: PORTRAIT renders the 56dp horizontal capsule anchored bottom-centre,
 * LANDSCAPE renders the 56dp-wide side column anchored to the end edge. The
 * posture is orientation-only — dragging the bar repositions it but never
 * reshapes it (portrait stays a horizontal pill, landscape stays a side
 * column), so snap-to-edge / drag extras can never break the restored default.
 *
 * Coordinates are in screen pixels; returned anchors are the content top-left.
 */
object DockPosturePolicy {

    enum class InkBarPosture { HORIZONTAL, VERTICAL }

    /** Portrait → HORIZONTAL pill, landscape → VERTICAL side column. */
    fun postureFor(isLandscape: Boolean): InkBarPosture =
        if (isLandscape) InkBarPosture.VERTICAL else InkBarPosture.HORIZONTAL

    /** Convenience: is the portrait (horizontal) posture active? */
    fun isHorizontal(isLandscape: Boolean): Boolean = postureFor(isLandscape) == InkBarPosture.HORIZONTAL

    /**
     * Phase 238: shape-aware posture. A freeform/floating window can be near-
     * square while [android.content.res.Configuration.orientation] still reads
     * PORTRAIT — the posture must follow the WINDOW SHAPE, not the flag. This
     * is the documented default the callers with real constraints should use.
     */
    fun isHorizontalForSize(widthDp: Int, heightDp: Int): Boolean =
        !AdaptiveLayoutPolicy.inkBarIsLandscape(widthDp, heightDp)

    /**
     * Default portrait anchor: horizontally centred, [bottomMarginPx] above the
     * bottom edge — content top-left.
     */
    fun horizontalDefaultAnchor(
        screenW: Float,
        screenH: Float,
        barW: Float,
        barH: Float,
        bottomMarginPx: Float
    ): Pair<Float, Float> {
        val sw = screenW.coerceAtLeast(1f)
        val sh = screenH.coerceAtLeast(1f)
        val bw = barW.coerceAtLeast(0f)
        val bh = barH.coerceAtLeast(0f)
        val m = bottomMarginPx.coerceAtLeast(0f)
        return ((sw - bw) / 2f).coerceAtLeast(0f) to (sh - bh - m).coerceAtLeast(0f)
    }

    /**
     * Default landscape anchor: vertically centred, [endMarginPx] from the
     * end edge — content top-left.
     */
    fun verticalDefaultAnchor(
        screenW: Float,
        screenH: Float,
        barW: Float,
        barH: Float,
        endMarginPx: Float
    ): Pair<Float, Float> {
        val sw = screenW.coerceAtLeast(1f)
        val sh = screenH.coerceAtLeast(1f)
        val bw = barW.coerceAtLeast(0f)
        val bh = barH.coerceAtLeast(0f)
        val m = endMarginPx.coerceAtLeast(0f)
        return (sw - bw - m).coerceAtLeast(0f) to ((sh - bh) / 2f).coerceAtLeast(0f)
    }
}