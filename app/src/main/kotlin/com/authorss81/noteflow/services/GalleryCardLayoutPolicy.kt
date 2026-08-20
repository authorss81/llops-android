package com.authorss81.noteflow.services

/**
 * Phase 184 — gallery card layout policy.
 *
 * Pure JVM: decides the MINIMUM height of a gallery card in dp. The old fixed
 * `aspectRatio(10f / 16f)` produced a rigid 268.8dp card at the 168dp grid cell,
 * leaving a >60% empty band for short notes (the "empty bookmark" look) and
 * clipping the date/tags footer at large font scales (the ratio never yields to
 * content). The card is now CONTENT-DRIVEN — height follows the title/preview/
 * footer content so a short note renders a compact notebook tile — with a
 * modeled MINIMUM floor that scales with the user's font scale so large-font
 * content is never clipped (AGENTS.md accessibility rule).
 *
 * Because the value is a *minimum* (applied via `Modifier.heightIn`, not a strict
 * aspect ratio), content taller than the floor always wins; the floor only
 * guarantees the tile never collapses below a usable size.
 */
object GalleryCardLayoutPolicy {

    /** Minimum card height at 1.0 font scale (dp) — the notebook-tile floor. */
    const val BASE_MIN_HEIGHT_DP = 180f

    /**
     * The min-height floor can never exceed this value (dp), even at extreme font
     * scales — a 240%+ font scale still gets a usable tile without the grid
     * degenerating into giant mostly-empty cards.
     */
    const val MAX_MIN_HEIGHT_DP = 288f

    /**
     * @param fontScale the user's system font scale (`LocalDensity.current.fontScale`).
     *   Non-finite or non-positive values are treated as 1.0 (fail safe to the
     *   base floor) so a garbage input can never collapse the tile.
     * @return the minimum card height in dp. Never below [BASE_MIN_HEIGHT_DP],
     *   never above [MAX_MIN_HEIGHT_DP], and monotonic in [fontScale] once the
     *   base floor is reached.
     */
    fun minCardHeightDp(fontScale: Float): Float {
        val safe = if (fontScale.isFinite() && fontScale > 0f) fontScale else 1f
        val scaled = BASE_MIN_HEIGHT_DP * safe
        return scaled.coerceIn(BASE_MIN_HEIGHT_DP, MAX_MIN_HEIGHT_DP)
    }
}
