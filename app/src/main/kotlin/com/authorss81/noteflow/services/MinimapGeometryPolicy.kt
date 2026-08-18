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

    // ---- R2-b2b4-DOS-03 (phase-150): minimap per-frame work budget -----------
    // The pre-fix thumbnail loop iterated EVERY stroke and EVERY point with a
    // FIXED stride (`step <= 4`) and issued one `drawLine` per retained pair —
    // ~50k draw commands per frame at the phase-50 geometry cap
    // (StrokeGeometryPolicy.MAX_POINTS_PER_PAGE ≈ 200k), recomputed on every
    // pan/zoom frame. These two budgets turn it into a bounded polyline pass:
    // sample at most [MAX_MINIMAP_SAMPLED_STROKES] strokes and derive a GLOBAL
    // point stride from the sampled total so the whole thumbnail issues at most
    // [MAX_MINIMAP_POLYLINE_SEGMENTS] poly-line calls (+ the sampled single-
    // segment strokes' overhead ≤ [MAX_MINIMAP_SAMPLED_STROKES]).

    /** Total `drawLine` budget for the minimap's stroke-polyline pass. */
    const val MAX_MINIMAP_POLYLINE_SEGMENTS = 400

    /** Never more than this many strokes are sampled into the thumbnail. */
    const val MAX_MINIMAP_SAMPLED_STROKES = 120

    private fun ceilDiv(a: Int, b: Int): Int = if (b <= 0 || a <= 0) 1 else (a + b - 1) / b

    /** Iteration stride over the stroke list so ≤ [MAX_MINIMAP_SAMPLED_STROKES] strokes are sampled. */
    fun strokeStepFor(strokeCount: Int): Int = ceilDiv(strokeCount, MAX_MINIMAP_SAMPLED_STROKES)

    /**
     * Global point iteration stride derived from the sampled total point count
     * so the poly-line pass settles at ≤ [MAX_MINIMAP_POLYLINE_SEGMENTS]
     * segments (plus the [MAX_MINIMAP_SAMPLED_STROKES] single-segment cap).
     */
    fun pointStepFor(totalPoints: Int): Int = ceilDiv(totalPoints, MAX_MINIMAP_POLYLINE_SEGMENTS)

    /** How many strokes the stride-based sampler will visit (informational). */
    fun sampledStrokeCount(strokeCount: Int): Int =
        strokeCount.coerceAtLeast(0).coerceAtMost(MAX_MINIMAP_SAMPLED_STROKES)

    /**
     * Worst-case `drawLine` count the budgeted loop can issue for a page of
     * [strokeCount] strokes totalling [totalPoints] points: one segment per
     * sampled stroke at most (single-segment strokes / polylines) plus the
     * poly-line segments. Guaranteed ≤ [MAX_MINIMAP_SAMPLED_STROKES] +
     * [MAX_MINIMAP_POLYLINE_SEGMENTS] regardless of geometry size.
     */
    fun maxLineDraws(strokeCount: Int, totalPoints: Int): Int {
        val strokes = strokeCount.coerceAtLeast(0)
        val pts = totalPoints.coerceAtLeast(0)
        val sampled = minOf(strokes, MAX_MINIMAP_SAMPLED_STROKES)
        return sampled + ceilDiv(pts, pointStepFor(pts))
    }

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