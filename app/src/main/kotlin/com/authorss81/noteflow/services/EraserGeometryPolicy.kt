package com.authorss81.noteflow.services

import kotlin.math.max
import kotlin.math.min

/**
 * Phase 124: single decision table for the **smooth / pressure-aware** eraser
 * stamp, kept pure JVM so it is unit-testable and allocation-free per point.
 *
 * The PARTIAL eraser removes every centerline point that falls INSIDE a round
 * mask. Each mask is a circle stamped at one point of the erase path, whose
 * radius is derived from [stampRadius] — the user's current brush width scaled
 * by the touch pressure at that instant (the "pressure-aware edge": a heavier
 * press carves a wider, rounder swath like a real eraser).
 *
 * The visible result is smooth because coverage uses the whole nib geometry:
 * [coverageRadius] mandates that a point only stays covered once the mask has
 * swallowed the stroke's full half-width, so the surviving run's end point is
 * guaranteed to lie OUTSIDE the round mask (dist(e, p) > mask radius). The
 * surviving run is then rendered with a round cap, which produces a clean,
 * round cut — never a jagged carve.
 */
object EraserGeometryPolicy {

    /** The erase stamp never drops below this radius (a light tap still works). */
    const val MIN_ERASE_WIDTH_PX = 6f

    /** Cap for the erase stamp so a huge brush width cannot create a 100px blob. */
    const val MAX_ERASE_WIDTH_PX = 48f

    /** Radius fraction at ZERO pressure (the minimum usable brush radius). */
    const val LIGHT_PRESSURE_SCALE = 0.5f

    /** Extra reach so a quick tap reliably engages the mask (finger tolerance). */
    const val TAP_TOLERANCE_PX = 4f

    /** Whole-stroke (STROKE mode) hit margin kept from the classic eraser. */
    const val WHOLE_STROKE_EXTRA_RADIUS = 18f

    fun clampPressure(pressure: Float): Float = pressure.coerceIn(0f, 1f)

    /**
     * The round-mask stamp radius for one erase-path sample, derived from
     * [baseWidth] (the current brush width) and the touch [pressure] at that
     * sample. Higher pressure -> wider round swath; linear between the light
     * and full-pressure scales. Fully bounded -> low-end / allocation-free.
     */
    fun stampRadius(baseWidth: Float, pressure: Float): Float {
        val basis = baseWidth.coerceIn(MIN_ERASE_WIDTH_PX, MAX_ERASE_WIDTH_PX)
        val scale = LIGHT_PRESSURE_SCALE + (1f - LIGHT_PRESSURE_SCALE) * clampPressure(pressure)
        return basis * scale + TAP_TOLERANCE_PX
    }

    /**
     * Coverage radius of one stamp against a stroke's centerline: the mask must
     * swallow the full nib half-width, so the surviving run boundary is always
     * OUTSIDE the mask (round, no slivers).
     */
    fun coverageRadius(stampRadius: Float, strokeWidth: Float): Float =
        stampRadius + strokeWidth * 0.5f

    /**
     * The PARTIAL-preview circle radius shown under the cursor: the coverage of
     * a full-pressure stamp against a stroke of the same current width — i.e.
     * the widest confident cut the user will produce. Bounded (never larger
     * than [MAX_ERASE_WIDTH_PX] × 2) and clamped to a usable minimum.
     */
    fun previewRadius(baseWidth: Float, strokeWidth: Float): Float {
        val base = baseWidth.coerceIn(MIN_ERASE_WIDTH_PX, MAX_ERASE_WIDTH_PX)
        val cut = coverageRadius(stampRadius(base, 1f), strokeWidth.coerceAtLeast(0f))
        return min(cut, MAX_ERASE_WIDTH_PX * 2f).coerceAtLeast(MIN_ERASE_WIDTH_PX)
    }

    /**
     * Backward-compatible radius rule for a sample WITHOUT an explicit stamp
     * radius (legacy phase-19 path): the classic `stroke.width + extraRadius`.
     */
    fun legacyRadius(strokeWidth: Float, extraRadius: Float): Float =
        max(strokeWidth + extraRadius, 1f)
}