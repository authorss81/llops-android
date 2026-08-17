package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.StrokeTool

/**
 * Phase 121 — single source of truth for brush EDGE roundness.
 *
 * The stroke render pipeline (`drawSingleStroke` in AnnotationCanvas.kt and the
 * textured fallbacks in BrushTextureEngine.kt) already draws every freehand tool
 * with round caps/joins — except the PALETTE_KNIFE smear, which is *documented*
 * character (docs/brush-styles.md style 10: "Flat, squared, directional — none
 * of the rounded pen feel"). This object centralises that decision so the brush
 * palette previews (`PenNibVisualPreview`) render the SAME edge the actual
 * stroke will have, and so the roundness contract is unit-testable on the JVM.
 *
 * Pure JVM: no Android/Compose types.
 */
object BrushEdgePolicy {

    /** Line-cap geometry a brush stroke/preview must use. */
    enum class LineCap { ROUND, SQUARE }

    /** Line-join geometry a brush stroke/preview must use. */
    enum class LineJoin { ROUND, BEVEL }

    /**
     * Edge geometry contract for one tool.
     *
     * @param cap            round vs. flat ends of the stroke
     * @param join           round vs. angled corners at direction changes
     * @param smoothWidth    whether the stroke modulates its width smoothly along
     *                       its length (velocity/pressure taper) instead of drawing
     *                       one constant-width pass — n/a (false) for constant-width
     *                       tools whose width is already a single smooth value.
     */
    data class EdgeStyle(
        val cap: LineCap,
        val join: LineJoin,
        val smoothWidth: Boolean
    )

    private const val FLAT_KNIFE = "(documented flat smear — PALETTE_KNIFE character, docs/brush-styles.md style 10)"

    /**
     * Edge style per freehand tool. Every brush is ROUND except the palette
     * knife, whose flat square smear is its documented identity. Wet-mixing and
     * texture character are unaffected by cap/join geometry.
     */
    fun edgeStyleFor(tool: StrokeTool): EdgeStyle = when (tool) {
        StrokeTool.PALETTE_KNIFE -> EdgeStyle(LineCap.SQUARE, LineJoin.BEVEL, smoothWidth = false)
        StrokeTool.PEN,
        StrokeTool.FOUNTAIN_PEN,
        StrokeTool.FINELINER,
        StrokeTool.CALLIGRAPHIC,
        StrokeTool.CHISEL_MARKER -> EdgeStyle(LineCap.ROUND, LineJoin.ROUND, smoothWidth = true)
        else -> EdgeStyle(LineCap.ROUND, LineJoin.ROUND, smoothWidth = false)
    }

    /** Round-capped freehand strokes. */
    fun isRoundCap(tool: StrokeTool): Boolean = edgeStyleFor(tool).cap == LineCap.ROUND

    /** Round-joined freehand strokes. */
    fun isRoundJoin(tool: StrokeTool): Boolean = edgeStyleFor(tool).join == LineJoin.ROUND

    /** Whether a tool can carry smooth (non-stepped) width transitions. */
    fun usesSmoothWidthTransitions(tool: StrokeTool): Boolean = edgeStyleFor(tool).smoothWidth

    /** Human-readable justification for a tool's edge choice (docs anchor). */
    fun rationaleFor(tool: StrokeTool): String = when (tool) {
        StrokeTool.PALETTE_KNIFE -> FLAT_KNIFE
        else -> "round caps/joins on the stroke and its palette preview"
    }
}