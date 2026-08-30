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
     * bottom edge — content top-left. [topReservedPx] (default 0) reserves the
     * Scaffold top-app-bar band below the top inset; the resting anchor is never
     * placed above that line (a bottom-centre pill is unaffected — harmless).
     */
    fun horizontalDefaultAnchor(
        screenW: Float,
        screenH: Float,
        barW: Float,
        barH: Float,
        bottomMarginPx: Float = DEFAULT_BOTTOM_MARGIN_PX,
        topReservedPx: Float = 0f
    ): Pair<Float, Float> {
        val sw = screenW.coerceAtLeast(1f)
        val sh = screenH.coerceAtLeast(1f)
        val bw = barW.coerceAtLeast(0f)
        val bh = barH.coerceAtLeast(0f)
        val m = bottomMarginPx.coerceAtLeast(0f)
        val reserved = topReservedPx.coerceAtLeast(0f)
        return ((sw - bw) / 2f).coerceAtLeast(0f) to (sh - bh - m).coerceAtLeast(reserved)
    }

    /**
     * Default landscape anchor: vertically centred, [endMarginPx] from the
     * end edge — content top-left. [topReservedPx] (default 0) reserves the
     * Scaffold top-app-bar band: the vertical centre is never allowed to rest
     * above `topReservedPx` (i.e. the resting position stays below the app bar
     * on short/freeform windows).
     */
    fun verticalDefaultAnchor(
        screenW: Float,
        screenH: Float,
        barW: Float,
        barH: Float,
        endMarginPx: Float = DEFAULT_END_MARGIN_PX,
        topReservedPx: Float = 0f
    ): Pair<Float, Float> {
        val sw = screenW.coerceAtLeast(1f)
        val sh = screenH.coerceAtLeast(1f)
        val bw = barW.coerceAtLeast(0f)
        val bh = barH.coerceAtLeast(0f)
        val m = endMarginPx.coerceAtLeast(0f)
        val reserved = topReservedPx.coerceAtLeast(0f)
        return (sw - bw - m).coerceAtLeast(0f) to ((sh - bh) / 2f).coerceAtLeast(reserved)
    }

    /** Default bottom margin (px) when the caller does not pass one. */
    const val DEFAULT_BOTTOM_MARGIN_PX = 20f

    /** Default end-edge margin (px) when the caller does not pass one. */
    const val DEFAULT_END_MARGIN_PX = 20f
}
