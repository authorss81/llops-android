package com.authorss81.noteflow.services

/**
 * Phase 215: visual constants for the SELECT tool's selection overlay — the
 * live lasso/marquee trail and the resting selected-stroke highlight.
 *
 * Pure JVM decision table so the overlay renderer stays literal-free and the
 * values are unit-pinnable. The highlight deliberately reuses the eraser
 * highlight's geometry (`stroke.width + 10f` round pass at low alpha) but a
 * DIFFERENT hue (the theme accent) so a selection is never mistaken for an
 * erase preview; the dashed outlines distinguish "region being drawn" (trail)
 * from "what is held" (bounds box).
 */
object LassoTrailPolicy {

    /** Translucent fill inside the closed lasso trail. */
    const val TRAIL_FILL_ALPHA = 0.08f

    /** Dash outline of the lasso trail while it is being drawn. */
    const val TRAIL_OUTLINE_ALPHA = 0.9f

    /** Trail outline width in world px. */
    const val TRAIL_STROKE_WIDTH_PX = 2f

    /** Resting highlight pass alpha over every selected stroke. */
    const val HIGHLIGHT_ALPHA = 0.25f

    /** Dashed bounds-box outline alpha around the whole selection. */
    const val BOUNDS_OUTLINE_ALPHA = 0.85f

    /** Dashed bounds-box stroke width in world px. */
    const val BOUNDS_STROKE_WIDTH_PX = 2f

    /**
     * Dash pattern for the lasso trail, scaled with zoom so dashes keep a
     * constant SCREEN size: [on, off] world px for the current [zoomScale].
     */
    fun trailDashPattern(zoomScale: Float = 1f): FloatArray =
        floatArrayOf(
            TRAIL_DASH_ON_PX / zoomScale.coerceAtLeast(0.1f),
            TRAIL_DASH_OFF_PX / zoomScale.coerceAtLeast(0.1f)
        )

    /**
     * Dash pattern for the resting bounds box, zoom-scaled like the trail.
     */
    fun boundsDashPattern(zoomScale: Float = 1f): FloatArray =
        floatArrayOf(
            BOUNDS_DASH_ON_PX / zoomScale.coerceAtLeast(0.1f),
            BOUNDS_DASH_OFF_PX / zoomScale.coerceAtLeast(0.1f)
        )

    /** Trail dash "on" segment at 1x zoom (world px). */
    const val TRAIL_DASH_ON_PX = 12f

    /** Trail dash "off" gap at 1x zoom (world px). */
    const val TRAIL_DASH_OFF_PX = 8f

    /** Bounds dash "on" segment at 1x zoom (world px). */
    const val BOUNDS_DASH_ON_PX = 14f

    /** Bounds dash "off" gap at 1x zoom (world px). */
    const val BOUNDS_DASH_OFF_PX = 10f
}
