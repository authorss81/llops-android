package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.StrokeTool
import kotlin.math.abs

/**
 * Pure-JVM brush-physics math shared by the AGSL shader and the vector fallback
 * renderer (Phase 18). Everything here is unit-testable on the JVM and MUST stay
 * free of Android dependencies.
 *
 * The style selector ids MUST match the uBrushStyle values documented inside
 * [com.authorss81.noteflow.ui.components.AgslShaders.WET_MIXING_SHADER].
 */
object BrushStrokeMath {

    const val STYLE_DEFAULT = 0
    const val STYLE_WATERCOLOR = 1
    const val STYLE_OIL_PAINT = 2
    const val STYLE_SMUDGE = 3
    const val STYLE_SPLATTER = 4
    const val STYLE_CHARCOAL = 5
    const val STYLE_OIL_PASTEL = 6
    const val STYLE_INK_WASH = 7
    const val STYLE_GOUACHE = 8
    const val STYLE_DRY_BRUSH = 9
    const val STYLE_PALETTE_KNIFE = 10

    /**
     * Maps a [StrokeTool] onto its shader style selector. Existing tools keep the
     * same ids as before Phase 18; the six new tools get their own distinct ids.
     */
    fun brushStyleIdForTool(tool: StrokeTool): Int = when (tool) {
        StrokeTool.WATERCOLOR -> STYLE_WATERCOLOR
        StrokeTool.OIL_PAINT -> STYLE_OIL_PAINT
        StrokeTool.SMUDGE -> STYLE_SMUDGE
        StrokeTool.SPLATTER -> STYLE_SPLATTER
        StrokeTool.CHARCOAL -> STYLE_CHARCOAL
        StrokeTool.OIL_PASTEL -> STYLE_OIL_PASTEL
        StrokeTool.INK_WASH -> STYLE_INK_WASH
        StrokeTool.GOUACHE -> STYLE_GOUACHE
        StrokeTool.DRY_BRUSH -> STYLE_DRY_BRUSH
        StrokeTool.PALETTE_KNIFE -> STYLE_PALETTE_KNIFE
        else -> STYLE_DEFAULT
    }

    /**
     * Velocity-based width modulation: faster stroke -> thinner line.
     *
     * Returns a width multiplier in [0.55, 1.0]. At or below [baseThresholdMsPx]
     * the multiplier is 1.0 (identity — no regression for any existing brush when
     * velocity is slow, which is the default behaviour for most handwriting). When
     * [intensity] is 0 the multiplier is always 1.0, so a velocity feature that is
     * switched off reproduces the classic fixed-width look exactly.
     */
    fun velocityWidthFactor(
        velocity: Float,
        intensity: Float = 1f,
        baseThresholdMsPx: Float = 1.0f,
        fastThresholdMsPx: Float = 6.0f
    ): Float {
        if (intensity <= 0f || velocity <= baseThresholdMsPx) return 1f
        val t = ((velocity - baseThresholdMsPx) / (fastThresholdMsPx - baseThresholdMsPx)).coerceIn(0f, 1f)
        return 1f - t * (1f - 0.55f) * intensity.coerceIn(0f, 1f)
    }

    /**
     * Per-segment velocity in px/ms derived from a consecutive point pair.
     * Falls back to the interpolated point spacing (px per nominal 16 ms step)
     * when timestamps are absent so old saved strokes still modulate.
     */
    fun segmentVelocity(pointA: com.authorss81.noteflow.data.model.PointF, pointB: com.authorss81.noteflow.data.model.PointF): Float {
        val dx = pointB.x - pointA.x
        val dy = pointB.y - pointA.y
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        if (dist <= 0f) return 0f
        val dtMs = (pointB.timestampMs ?: 0L) - (pointA.timestampMs ?: 0L)
        return if (dtMs > 0L && dist > 0.001f) dist / dtMs.toFloat() else dist / 16f
    }

    /**
     * Pressure-driven bristle spread: higher pressure -> wider contact patch.
     *
     * Returns a width multiplier in [0.75, 1.0]. At full pressure (>= 0.85, which
     * is what most finger/mouse input reports) the multiplier is 1.0, so brushes
     * keep their classic width while stylus users get a real pressure taper.
     */
    fun bristleSpreadFactor(pressure: Float, intensity: Float = 1f): Float {
        if (intensity <= 0f) return 1f
        val p = pressure.coerceIn(0f, 1f)
        val taper = ((0.85f - p).coerceIn(0f, 1f))
        return 1f - 0.25f * taper * intensity.coerceIn(0f, 1f)
    }

    /** Pigment opacity (0.55..1.0) for a given remapped pressure. */
    fun pigmentFromPressure(pressure: Float): Float =
        0.55f + 0.45f * pressure.coerceIn(0f, 1f)

    /**
     * Stable per-stroke seed derived from a stroke id, so every stroke gets its own
     * texture orientation/phase. Deterministic for the same id (round-trip safe),
     * and different ids yield different seeds.
     */
    fun strokeSeedFromId(id: String): Float {
        var h = id.hashCode()
        if (h == Int.MIN_VALUE) h = Int.MAX_VALUE
        return (abs(h.toLong()) % 100000L).toFloat() / 1000f
    }

    /**
     * Whether a tool participates in the AGSL wet pass (live smear/blend preview).
     * All wet tools and the smear-based new tools are included; pure dry tools are not.
     */
    fun isWetRenderedTool(tool: StrokeTool): Boolean = when (tool) {
        StrokeTool.WATERCOLOR, StrokeTool.OIL_PAINT, StrokeTool.SMUDGE, StrokeTool.SPLATTER,
        StrokeTool.INK_WASH, StrokeTool.GOUACHE, StrokeTool.PALETTE_KNIFE -> true
        else -> false
    }

    /** Peak hydration level reported for a tool (used by the wet-sheet UI). */
    fun wetnessPeakForTool(tool: StrokeTool): Float = when (tool) {
        StrokeTool.WATERCOLOR -> 0.9f
        StrokeTool.INK_WASH -> 0.85f
        StrokeTool.SPLATTER -> 0.8f
        StrokeTool.GOUACHE -> 0.55f
        StrokeTool.PALETTE_KNIFE -> 0.5f
        StrokeTool.SMUDGE -> 0.5f
        StrokeTool.OIL_PAINT -> 0.4f
        else -> 0.0f
    }
}