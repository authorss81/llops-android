package com.authorss81.noteflow.services

import kotlin.math.pow

/**
 * Phase 200 (PERF 3.3) — premium paper-grain decision table.
 *
 * The ink canvas page previously rendered a perfectly FLAT paper tint
 * (`drawPaperCard` fill only), which reads as plastic next to real cold-press
 * stock. This policy owns every constant of the fix: a small TILEABLE noise
 * tile is generated once per process (per light/dark paper family), cached in
 * a static LRU (`ui/components/PaperGrainTileCache`) and drawn as a single
 * REPEAT-tiled `BitmapShader` round-rect directly over the paper fill and
 * strictly UNDER the ink pass — near-zero per-frame cost (one textured quad,
 * no per-pixel work, no allocation).
 *
 * Pure JVM so the noise spec, alpha envelope and gating are unit-testable and
 * a reviewer cannot reintroduce inline literals in `AnnotationCanvas`.
 *
 * Tileability is structural: the hash folds its inputs through a modulo of
 * [TILE_SIZE_PX], so column 0 == column TILE and row 0 == row TILE and the
 * REPEAT wrap seam is invisible by construction.
 */
object PaperGrainPolicy {

    /** Noise tile edge in px. Small enough to generate in ~1 ms once. */
    const val TILE_SIZE_PX = 192

    /** Static LRU cap for resident tiles (light + dark + headroom). */
    const val MAX_CACHED_TILES = 4

    /** Peak speckle opacity on LIGHT paper (dark graphite flecks). */
    const val LIGHT_SPECKLE_MAX_ALPHA = 0.05f

    /** Peak speckle opacity on DARK paper (cool-white fibers). Slightly more visible because dark fills swallow contrast. */
    const val DARK_SPECKLE_MAX_ALPHA = 0.07f

    /**
     * Noise above this threshold becomes a "fiber fleck" (the top 8% of the
     * field); below it the speckle is the faint uniform tooth of the sheet.
     */
    const val FLECK_THRESHOLD = 0.92f

    /** Fraction of [LIGHT_SPECKLE_MAX_ALPHA]/[DARK_SPECKLE_MAX_ALPHA] carried by the flat tooth (non-fleck) band. */
    const val TOOTH_STRENGTH = 0.35f

    /** Fraction carried by a full fleck at the very top of the noise range. */
    const val FLECK_STRENGTH = 0.65f

    /** Speckle tint on light paper — soft graphite, not pure black. */
    const val LIGHT_SPECKLE_RGB = 0xFF2B2B2B.toInt()

    /** Speckle tint on dark paper — cool white, matching the slate palette. */
    const val DARK_SPECKLE_RGB = 0xFFE8EDF4.toInt()

    /**
     * The grain is cosmetic with a one-time generation cost and <300 KB of
     * resident tiles; low-end devices skip it entirely rather than pay either.
     */
    fun enabled(deviceTierLowEnd: Boolean): Boolean = !deviceTierLowEnd

    /** Cache key of a tile family (exactly two exist today). */
    fun cacheKey(isDarkPaper: Boolean): String =
        if (isDarkPaper) "paper_grain_dark" else "paper_grain_light"

    /** Worst-case resident bytes when [MAX_CACHED_TILES] ARGB_8888 tiles live at once. */
    fun maxResidentBytes(): Long = TILE_SIZE_PX.toLong() * TILE_SIZE_PX * 4L * MAX_CACHED_TILES

    /** Speckle tint (opaque RGB) for a paper family. */
    fun speckleRgb(isDarkPaper: Boolean): Int =
        if (isDarkPaper) DARK_SPECKLE_RGB else LIGHT_SPECKLE_RGB

    /** Peak speckle alpha for a paper family. */
    fun speckleMaxAlpha(isDarkPaper: Boolean): Float =
        if (isDarkPaper) DARK_SPECKLE_MAX_ALPHA else LIGHT_SPECKLE_MAX_ALPHA

    /**
     * Deterministic tileable value noise in [0,1). Same inputs -> same output
     * on every JVM/device; coordinates fold through [TILE_SIZE_PX] modulo so
     * the field wraps seamlessly.
     */
    fun noiseAt(x: Int, y: Int, isDarkPaper: Boolean): Float {
        val tx = Math.floorMod(x, TILE_SIZE_PX)
        val ty = Math.floorMod(y, TILE_SIZE_PX)
        var h = tx * 374761393 + ty * 668265263
        if (isDarkPaper) h += 0x9E3779B9.toInt()
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0x7FFFFFFF).toFloat() / 0x7FFFFFFFL.toFloat()
    }

    /**
     * Pixel alpha envelope for a raw noise sample: a faint uniform sheet tooth
     * everywhere, boosted to a visible fiber fleck in the top noise band.
     * Always within [0, speckleMaxAlpha]; NaN/Infinity fail safe to 0.
     */
    fun pixelAlphaAt(noise01: Float, isDarkPaper: Boolean): Float {
        if (!noise01.isFinite()) return 0f
        val n = noise01.coerceIn(0f, 1f)
        val maxA = speckleMaxAlpha(isDarkPaper)
        val tooth = n * TOOTH_STRENGTH
        val fleckT = if (n > FLECK_THRESHOLD) {
            ((n - FLECK_THRESHOLD) / (1f - FLECK_THRESHOLD)).coerceIn(0f, 1f)
        } else 0f
        val fleck = fleckT.pow(2f) * FLECK_STRENGTH
        return (tooth + fleck).coerceIn(0f, 1f) * maxA
    }
}
