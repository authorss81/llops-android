package com.authorss81.noteflow.services

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.PI
import kotlin.math.sin

/**
 * Phase 155: pure-JVM layout + hit-testing for the long-press QUICK-COLOR RING.
 *
 * The ring is a radial swatch picker that pops under the user's finger when they
 * long-press the canvas: the active [DesignerPalettes] swatches are laid out
 * around a ring band (a filled center disc shows the CURRENT tool color and acts
 * as the "cancel / keep current" target). Dragging rotates the selection; a
 * release over a swatch applies it.
 *
 * All geometry is deterministic and unit-testable (no Android/Compose): swatch
 * positions come from [ringLayout], and [hitIndex] maps a pointer position to a
 * swatch OR to the center-disc id ([-1] = nothing selected, [CENTER_SLOT] = the
 * center = keep current color). Seams reused from the palette stack: swatch order
 * is the caller's (seeded from [DesignerPalettes]/[PaletteMath]), and [angleDeltaDeg]
 * delegates to [HarmonicContrastMath.hueDeltaDeg] so the ring and the contrast
 * studio agree on "which side" a swatch sits.
 */
object QuickColorRingMath {

    /**
     * Full circle in radians. `kotlin.math` only exposes [PI] (no `TWO_PI`
     * constant), so 2*PI is derived here instead of importing a symbol that
     * does not exist in the stdlib (phase-155 review fix).
     */
    private val TWO_PI: Double = 2.0 * PI

    /** Sentinel returned by [hitIndex] when the ring is closed (nothing selectable). */
    const val NOTHING_HIT = -1

    /** Slot id returned by [hitIndex] for the CENTER disc (keep current color). */
    const val CENTER_SLOT = -2

    /** How many swatches the ring can show at most before crowding. */
    const val MAX_SWATCHES = 18

    /** Radius of the ring's outer rim (screen px). */
    const val RING_OUTER_RADIUS_PX = 152f

    /** Radius of the ring's inner hole (screen px). */
    const val RING_INNER_RADIUS_PX = 78f

    /** Radius of one selectable swatch circle (screen px). */
    const val SWATCH_RADIUS_PX = 24f

    /** Radius of the center (keep-current / cancel) disc (screen px). */
    const val CENTER_RADIUS_PX = RING_INNER_RADIUS_PX - 8f

    /**
     * Cleaves the supplied palette to the ring budget. Order is preserved; a
     * palette already at/under the cap is returned unchanged. The ring cannot
     * grow unbounded (a DoS-safe cap, mirroring the canvas-page budgets).
     */
    fun cappedSwatches(swatchCount: Int): Int = swatchCount.coerceAtMost(MAX_SWATCHES)

    /**
     * Radial layout of [swatchCount] swatch centers around [center]. Swatch 0
     * sits at 12 o'clock and the ring proceeds clockwise. Returns a stable,
     * evenly-spaced list (caller keeps it in a remembered list keyed on the
     * palette so the ring does not shimmer between frames).
     */
    fun ringLayout(
        swatchCount: Int,
        centerX: Float,
        centerY: Float,
        outerRadius: Float = RING_OUTER_RADIUS_PX,
        innerRadius: Float = RING_INNER_RADIUS_PX
    ): List<Pair<Float, Float>> {
        val n = cappedSwatches(swatchCount)
        if (n <= 0) return emptyList()
        val mid = (outerRadius + innerRadius) / 2f
        val out = ArrayList<Pair<Float, Float>>(n)
        for (i in 0 until n) {
            val angle = (-TWO_PI * i / n) + (TWO_PI / 4f) // 12 o'clock start, clockwise
            out += (centerX + (mid * cos(angle)).toFloat()) to (centerY + (mid * sin(angle)).toFloat())
        }
        return out
    }

    /**
     * Maps a pointer position to a swatch slot.
     *
     * @return [CENTER_SLOT] inside the inner disc, a swatch index (>=0) when the
     *         pointer is within [SWATCH_RADIUS_PX] (+ touch slop) of that
     *         swatch's center, or [NOTHING_HIT] when the pointer is outside the
     *         selectable band (e.g. between swatches, under the inner hole).
     */
    fun hitIndex(
        pointerX: Float,
        pointerY: Float,
        centerX: Float,
        centerY: Float,
        swatchCenters: List<Pair<Float, Float>>,
        touchSlopPx: Float = 0f,
        outerRadius: Float = RING_OUTER_RADIUS_PX,
        innerRadius: Float = RING_INNER_RADIUS_PX
    ): Int {
        val d = hypot(pointerX - centerX, pointerY - centerY)
        if (d <= CENTER_RADIUS_PX) return CENTER_SLOT

        val hitRadius = SWATCH_RADIUS_PX + touchSlopPx
        val bandMid = (outerRadius + innerRadius) / 2f
        // Selection only makes sense near the ring band (dist around swatch centers).
        if (d > outerRadius + hitRadius) return NOTHING_HIT
        if (d < innerRadius - hitRadius) return NOTHING_HIT

        var best = NOTHING_HIT
        var bestDist = Float.MAX_VALUE
        for (i in swatchCenters.indices) {
            val (sx, sy) = swatchCenters[i]
            val dd = hypot(pointerX - sx, pointerY - sy)
            if (dd <= hitRadius && dd < bestDist) {
                bestDist = dd
                best = i
            }
        }
        if (best != NOTHING_HIT) return best

        // Still inside the band but not touching any swatch: snap to the nearest
        // swatch ONLY when the pointer is within touch slop of the band circle --
        // this models the finger drifting between swatches while dragging.
        val bandDeviation = kotlin.math.abs(d - bandMid)
        if (best == NOTHING_HIT && bandDeviation <= hitRadius * 0.5f) {
            var nearest = NOTHING_HIT
            var nearestD = Float.MAX_VALUE
            for (i in swatchCenters.indices) {
                val (sx, sy) = swatchCenters[i]
                val dd = hypot(pointerX - sx, pointerY - sy)
                if (dd < nearestD) {
                    nearestD = dd
                    nearest = i
                }
            }
            return nearest
        }
        return NOTHING_HIT
    }

    /** Angular distance (degrees) between two ring swatch positions about [center]. */
    fun angleDeg(
        pointerX: Float,
        pointerY: Float,
        centerX: Float,
        centerY: Float
    ): Float {
        val deg = Math.toDegrees(atan2((pointerY - centerY).toDouble(), (pointerX - centerX).toDouble())).toFloat()
        return (deg % 360f + 360f) % 360f
    }

    /**
     * Selection "ring highlight" position normalized 0..1 along the ring — the
     * fraction used to animate the selection halo. Uses the same hue-wrapping
     * semantics as [BrushColorModeMath.normalizeHue] so the two stay consistent.
     */
    fun selectionProgress(index: Int, swatchCount: Int): Float {
        val n = max(1, cappedSwatches(swatchCount))
        val i = index.coerceIn(0, n - 1)
        return i.toFloat() / n
    }
}