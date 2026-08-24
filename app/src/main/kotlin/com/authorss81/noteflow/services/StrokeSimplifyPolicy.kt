package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.StrokeTool

/**
 * Phase 201 (PERF 1.4) — single source of truth for the Ramer-Douglas-Peucker
 * simplification epsilon a committed stroke uses.
 *
 * The canvas previously simplified EVERY stroke at one hardcoded
 * [DEFAULT_EPSILON_PX] (1.3 px), which visibly flattened fine hairline work:
 * a 1.3 px tolerance is larger than half the width of a 1-2 px nib, so gentle
 * curves on PEN / FOUNTAIN_PEN / PENCIL / FINELINER lost their subtle
 * inflections at commit time. Fine-tip brushes now get a tighter epsilon in
 * the [HAIRLINE_MIN_EPSILON_PX]..[HAIRLINE_MAX_EPSILON_PX] band — scaled by
 * the stroke width so the very finest nibs keep the most detail — while every
 * other tool keeps the legacy 1.3 px behaviour unchanged.
 *
 * COMMIT-TIMING CONTRACT: simplification runs ONLY on pointer-up (the canvas
 * commit path inside `onDragEnd`), never mid-stroke — the live preview renders
 * the raw stabilized points so the ink never snaps under the pen. Source-pinned
 * in `B2Dos01StrokeGeometryTest` + `Phase201StrokeInputPipelineTest`.
 *
 * Pure JVM, no Android types.
 */
object StrokeSimplifyPolicy {

    /** Legacy epsilon (px) every non-hairline brush has always used. */
    const val DEFAULT_EPSILON_PX = 1.3f

    /** Tightest epsilon (px) — reserved for the finest nibs (~1 px wide). */
    const val HAIRLINE_MIN_EPSILON_PX = 0.6f

    /** Looser end of the hairline band, reached at [HAIRLINE_MAX_WIDTH_PX]. */
    const val HAIRLINE_MAX_EPSILON_PX = 0.8f

    /** A freehand stroke at or below this width (px) counts as hairline. */
    const val HAIRLINE_MAX_WIDTH_PX = 3.0f

    /** Finest width the thickness slider can produce; maps to [HAIRLINE_MIN_EPSILON_PX]. */
    const val HAIRLINE_MIN_WIDTH_PX = 1.0f

    private val HAIRLINE_TOOLS = setOf(
        StrokeTool.PEN,
        StrokeTool.FOUNTAIN_PEN,
        StrokeTool.PENCIL,
        StrokeTool.FINELINER
    )

    /**
     * True iff a stroke drawn with [tool] at [strokeWidthPx] is hairline-class:
     * a fine-tip brush AND a narrow width. Wide marker-style strokes of the same
     * tools keep the legacy epsilon.
     */
    fun isHairlineBrush(tool: StrokeTool, strokeWidthPx: Float): Boolean =
        tool in HAIRLINE_TOOLS && strokeWidthPx <= HAIRLINE_MAX_WIDTH_PX

    /**
     * RDP epsilon (px) for committing a stroke with [tool] at [strokeWidthPx].
     * Hairline brushes interpolate linearly between [HAIRLINE_MIN_EPSILON_PX]
     * at [HAIRLINE_MIN_WIDTH_PX] and [HAIRLINE_MAX_EPSILON_PX] at
     * [HAIRLINE_MAX_WIDTH_PX]; everything else gets [DEFAULT_EPSILON_PX].
     * Degenerate widths fail safe to the hairline floor (never above it).
     */
    fun epsilonFor(tool: StrokeTool, strokeWidthPx: Float): Float {
        if (!isHairlineBrush(tool, strokeWidthPx)) return DEFAULT_EPSILON_PX
        val span = HAIRLINE_MAX_WIDTH_PX - HAIRLINE_MIN_WIDTH_PX
        if (span <= 0f) return HAIRLINE_MIN_EPSILON_PX
        val t = ((strokeWidthPx - HAIRLINE_MIN_WIDTH_PX) / span).coerceIn(0f, 1f)
        return HAIRLINE_MIN_EPSILON_PX +
            (HAIRLINE_MAX_EPSILON_PX - HAIRLINE_MIN_EPSILON_PX) * t
    }
}
