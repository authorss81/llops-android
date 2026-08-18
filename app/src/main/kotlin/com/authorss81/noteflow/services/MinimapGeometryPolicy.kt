package com.authorss81.noteflow.services

/**
 * Minimap geometry decision table (Phase 129).
 *
 * Pure JVM. Two jobs:
 *  1) [aspectFit] — the minimap map box is proportional to the canvas WORLD
 *     aspect ratio (so pan/zoom mapping agrees with the page), fitted inside
 *     the pre-35 nominal 120x140dp max box while preserving aspect, and never
 *     shrunk below [minSide] when a small upscale keeps it inside the box.
 *  2) [defaultAnchorBottomEnd] — the pre-35 bottom-right placement, plus the
 *     restored visibility contract: the minimap is OFF by default (a plain
 *     per-session toggle in the canvas settings sheet; the phase-35 persisted
 *     default-true regression is reverted).
 */
object MinimapGeometryPolicy {

    /** Restored pre-35 visibility: OFF unless the user enables it. */
    const val VISIBLE_BY_DEFAULT = false

    /** Nominal pre-35 max box (dp). */
    const val MAX_BOX_WIDTH_DP = 120f
    const val MAX_BOX_HEIGHT_DP = 140f

    /** The map is never shrunk below this side length when the box allows it. */
    const val MIN_SIDE_DP = 48f

    /** Pre-35 margin from the screen corner (dp). */
    const val DEFAULT_MARGIN_DP = 16f

    const val FALLBACK_WORLD = 1000f

    data class FitResult(val width: Float, val height: Float)

    /** Minimap visibility gate — ON only when the user enabled it. */
    fun shouldShow(userEnabled: Boolean): Boolean = userEnabled

    /**
     * Aspect-correct size for the minimap map box.
     *
     * Natural scale = min(maxBoxW/worldW, maxBoxH/worldH); both axes use the
     * SAME scale so the box aspect always matches the world aspect. If either
     * fitted side lands below [minSide], the whole box is upscaled (aspect
     * preserved) ONLY when the upscaled box still fits inside the max box —
     * otherwise the natural fit wins (a very tall/infinite world legitimately
     * renders a tall, narrow map). The box never exceeds [maxBoxW]x[maxBoxH].
     */
    fun aspectFit(
        worldW: Float,
        worldH: Float,
        maxBoxW: Float,
        maxBoxH: Float,
        minSide: Float = MIN_SIDE_DP
    ): FitResult {
        val pw = if (worldW > 0f) worldW else FALLBACK_WORLD
        val ph = if (worldH > 0f) worldH else FALLBACK_WORLD
        val mbw = maxBoxW.coerceAtLeast(1f)
        val mbh = maxBoxH.coerceAtLeast(1f)
        val ms = minSide.coerceAtLeast(1f)

        val naturalScale = minOf(mbw / pw, mbh / ph)
        val w0 = pw * naturalScale
        val h0 = ph * naturalScale
        if (w0 >= ms && h0 >= ms) {
            return FitResult(w0, h0)
        }

        // One side is below the minimum — try a whole-box upscale that meets
        // the minimum while still fitting inside the max box (aspect preserved).
        val upscale = maxOf(ms / w0, ms / h0)
        val w1 = w0 * upscale
        val h1 = h0 * upscale
        if (w1 <= mbw && h1 <= mbh) {
            return FitResult(w1, h1)
        }
        return FitResult(w0, h0)
    }

    /**
     * Pre-35 default anchor: bottom-right corner with [marginPx] breathing room
     * (content top-left).
     */
    fun defaultAnchorBottomEnd(
        screenW: Float,
        screenH: Float,
        mapW: Float,
        mapH: Float,
        marginPx: Float = DEFAULT_MARGIN_DP
    ): Offset {
        val sw = screenW.coerceAtLeast(1f)
        val sh = screenH.coerceAtLeast(1f)
        val m = marginPx.coerceAtLeast(0f)
        val mw = mapW.coerceAtLeast(0f)
        val mh = mapH.coerceAtLeast(0f)
        return Offset((sw - mw - m).coerceAtLeast(0f), (sh - mh - m).coerceAtLeast(0f))
    }

    /** Small 2D holder (mirrors [FloatingWidgetDragPolicy.Offset], no Compose import). */
    data class Offset(val x: Float, val y: Float)
}