package com.authorss81.noteflow.services

import androidx.compose.ui.geometry.Offset
import com.authorss81.noteflow.data.model.StrokeTool

/**
 * Phase 213 — per-stroke soft drop-shadow ("paper elevation") decision table.
 *
 * The ink canvas previously rendered every stroke FLAT on the paper; the only
 * depth cue was the impasto ridge lighting inside the AGSL wet shader
 * (AgslShaders.kt, `uImpasto` highlight/shadow terms). This policy owns every
 * constant of the vector shadow underlay that `drawSingleStroke` now paints
 * UNDER the main ink pass (one offset blurred outline Path per stroke):
 *
 *  - light paper  -> soft BLACK shadow at [LIGHT_PAPER_ALPHA]
 *  - dark paper   -> subtle WHITE lift at [DARK_PAPER_ALPHA] (a black shadow on
 *                    near-black stock is invisible; the ink must LIFT instead)
 *  - offset       -> proportional to stroke width ([OFFSET_X_WIDTH_FACTOR] /
 *                    [OFFSET_Y_WIDTH_FACTOR]), clamped so hairlines still read
 *                    and fat markers do not detach
 *  - blur         -> [BLUR_WIDTH_FACTOR] of the width, clamped to a penumbra band
 *
 * Backward compatibility is structural: when [plan] returns null (setting off,
 * low-end device, skipped tool) the renderer contributes NOTHING and the draw
 * path is byte-for-byte the pre-213 path, so old strokes are unchanged.
 *
 * GPU tier table: [gpuCarrierPreferred] records which devices WOULD qualify for
 * a RenderEffect-carrying RenderNode blur carrier (the wet-pass pattern). Phase
 * 213 ships the BlurMaskFilter path for ALL tiers deliberately: committed ink
 * rasterizes into the software `LayerBitmapLruCache` bitmaps where no
 * RenderNode/RenderEffect carrier can run, so shadows must look identical via
 * BlurMaskFilter anyway; a shared carrier re-records the phase-201 hazard where
 * `drawRenderNode` keeps a live reference and N shadows-per-frame would rewrite
 * each other's display lists; and Skia already executes small-radius
 * BlurMaskFilter blurs on the GPU when the canvas is hardware accelerated.
 * The function stays as the pinned tier table for a follow-up that scopes a
 * carrier to the single live-preview stroke.
 *
 * Pure JVM apart from compose-ui `Offset`/`StrokeTool` (plain Kotlin data
 * types), so the whole table is unit-testable (`BrushShadowPolicyTest`).
 */
object BrushShadowPolicy {

    // ---------------------------------------------------------------------
    // Alpha
    // ---------------------------------------------------------------------

    /** Shadow opacity on LIGHT paper — visible but never muddy. */
    const val LIGHT_PAPER_ALPHA = 0.20f

    /** Shadow opacity on DARK paper — a white LIFT, kept subtler than light mode. */
    const val DARK_PAPER_ALPHA = 0.12f

    /**
     * HIGHLIGHTER ink sits flat ON the page by design (translucent marker), so
     * its shadow is reduced rather than removed — enough separation to keep it
     * legible under other strokes without pretending it is raised paint.
     */
    const val HIGHLIGHTER_ALPHA_SCALE = 0.5f

    // ---------------------------------------------------------------------
    // Offset (proportional to stroke width)
    // ---------------------------------------------------------------------

    /** Horizontal offset as a fraction of the stroke width (light from top-left). */
    const val OFFSET_X_WIDTH_FACTOR = 0.35f

    /** Vertical offset as a fraction of the stroke width (slightly heavier). */
    const val OFFSET_Y_WIDTH_FACTOR = 0.40f

    /** Offset floor in dp — hairline strokes still get a readable drop. */
    const val MIN_OFFSET_DP = 1f

    /** Offset ceiling in dp — wide markers must not float off the paper. */
    const val MAX_OFFSET_DP = 6f

    // ---------------------------------------------------------------------
    // Blur
    // ---------------------------------------------------------------------

    /** Penumbra radius as a fraction of the stroke width. */
    const val BLUR_WIDTH_FACTOR = 0.60f

    /** Blur floor in px — below this the shadow reads as a hard duplicate. */
    const val MIN_BLUR_RADIUS_PX = 2f

    /** Blur ceiling in px — above this wide strokes grow a fog bank. */
    const val MAX_BLUR_RADIUS_PX = 12f

    /** Tools that must NEVER carry a shadow (utility / non-pigment marks). */
    private val SKIPPED_TOOLS: Set<StrokeTool> = setOf(
        StrokeTool.ERASER,      // removes ink; a shadow would draw phantom ink
        StrokeTool.LASER,       // ephemeral pointer glow, fades in ~1.8 s
        StrokeTool.TEXT,        // text boxes already read as flat annotations
        StrokeTool.SMUDGE,      // blending smear is not raised paint
        StrokeTool.STICKER,     // placed artwork carries its own art
        StrokeTool.SELECT,
        StrokeTool.PAN,
        StrokeTool.EYEDROPPER
    )

    /**
     * Master gate: shadows are a cosmetic overlay, so LOW_END devices keep the
     * pre-213 draw path entirely (same precedent as PaperGrainPolicy.enabled).
     */
    fun enabled(lowEndDevice: Boolean): Boolean = !lowEndDevice

    /** Per-tool gate: false contributes zero draw work for utility tools. */
    fun shouldApply(tool: StrokeTool): Boolean = tool !in SKIPPED_TOOLS

    /** Shadow opacity for the current paper (before any per-tool scaling). */
    fun shadowAlpha(isDarkPaper: Boolean): Float =
        if (isDarkPaper) DARK_PAPER_ALPHA else LIGHT_PAPER_ALPHA

    /** Penumbra radius in px for a stroke of [widthPx]. */
    fun blurRadius(widthPx: Float): Float =
        (sanitizeWidth(widthPx) * BLUR_WIDTH_FACTOR).coerceIn(MIN_BLUR_RADIUS_PX, MAX_BLUR_RADIUS_PX)

    /**
     * Shadow offset in px for a stroke of [widthPx], clamped to
     * [MIN_OFFSET_DP]..[MAX_OFFSET_DP] using [pxPerDp] (fail-safe 1.0 for a
     * non-positive density so degenerate inputs cannot collapse the clamp).
     */
    fun offset(widthPx: Float, pxPerDp: Float): Offset {
        val density = if (pxPerDp.isFinite() && pxPerDp > 0f) pxPerDp else 1f
        val minPx = MIN_OFFSET_DP * density
        val maxPx = MAX_OFFSET_DP * density
        val w = sanitizeWidth(widthPx)
        return Offset(
            x = (w * OFFSET_X_WIDTH_FACTOR).coerceIn(minPx, maxPx),
            y = (w * OFFSET_Y_WIDTH_FACTOR).coerceIn(minPx, maxPx)
        )
    }

    /**
     * GPU-carrier tier table (API 31+ RenderEffect carriers, non-low-end only).
     * See the class KDoc for why phase 213 routes every tier through
     * BlurMaskFilter anyway.
     */
    fun gpuCarrierPreferred(sdkInt: Int, lowEndDevice: Boolean): Boolean =
        sdkInt >= 31 && !lowEndDevice

    /**
     * One-shot render plan for a stroke, or null when NOTHING may be drawn
     * (setting off / skipped tool / degenerate width). [settingEnabled] is the
     * user-facing "Paper elevation" toggle; highlighter alpha is scaled here so
     * the renderer stays dumb. The device tier is resolved UPSTREAM (the
     * EditorScreen auto-off honors user re-enables), so this core variant
     * assumes eligibility was already decided.
     */
    fun plan(
        tool: StrokeTool,
        widthPx: Float,
        isDarkPaper: Boolean,
        settingEnabled: Boolean,
        pxPerDp: Float
    ): ShadowPlan? {
        if (!settingEnabled) return null
        if (!shouldApply(tool)) return null
        val w = sanitizeWidth(widthPx)
        if (w <= 0f) return null
        val off = offset(w, pxPerDp)
        var alpha = shadowAlpha(isDarkPaper)
        if (tool == StrokeTool.HIGHLIGHTER) alpha *= HIGHLIGHTER_ALPHA_SCALE
        return ShadowPlan(
            offsetX = off.x,
            offsetY = off.y,
            blurRadiusPx = blurRadius(w),
            alpha = alpha
        )
    }

    /** Tier-aware wrapper: [enabled] first, then the core decision table. */
    fun plan(
        tool: StrokeTool,
        widthPx: Float,
        isDarkPaper: Boolean,
        settingEnabled: Boolean,
        lowEndDevice: Boolean,
        pxPerDp: Float
    ): ShadowPlan? {
        if (!enabled(lowEndDevice)) return null
        return plan(tool, widthPx, isDarkPaper, settingEnabled, pxPerDp)
    }

    private fun sanitizeWidth(widthPx: Float): Float =
        if (widthPx.isFinite()) widthPx else 0f

    /** Immutable shadow parameters for one stroke draw. */
    data class ShadowPlan(
        val offsetX: Float,
        val offsetY: Float,
        val blurRadiusPx: Float,
        val alpha: Float
    )
}
