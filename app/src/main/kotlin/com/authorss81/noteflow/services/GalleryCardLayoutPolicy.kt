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

    // ---- Phase 188: large-font layout-bounds policy (risk #2) ----

    /**
     * Line budget of each bounded text block inside a card. The card has NO
     * maximum height (the floor is a minimum), so the only way a reviewer could
     * clip the footer at 1.3–1.5x font scale is by capping one of these blocks
     * below its own natural height — these constants pin the budget in one
     * place so the composable and the regression guard share it.
     */
    const val TITLE_MAX_LINES = 2
    const val PREVIEW_MAX_LINES = 3
    const val TAG_ROW_MAX_LINES = 1
    const val FOOTER_DATE_MAX_LINES = 1

    /**
     * Card height that a body measuring [contentHeightDp] attains at
     * [fontScale]. The card is CONTENT-DRIVEN with a MINIMUM floor —
     * `max(content, floor)` — and there is intentionally NO maximum, so growing
     * font scales grow the card instead of clipping the footer. Non-finite or
     * negative content fails safe to the floor alone.
     */
    fun measuredCardHeightDp(contentHeightDp: Float, fontScale: Float): Float {
        val safeContent = if (contentHeightDp.isFinite()) contentHeightDp.coerceAtLeast(0f) else 0f
        return maxOf(safeContent, minCardHeightDp(fontScale))
    }

    /**
     * The phase-188 large-font guarantee: the date/tags footer is never clipped
     * because the measured card height always follows the content
     * ([measuredCardHeightDp] ≥ content for any font scale). Returns false only
     * for a garbage content height (NaN or negative) — the same fail-safe
     * posture as [minCardHeightDp].
     */
    fun footerAlwaysFits(contentHeightDp: Float, fontScale: Float): Boolean {
        if (contentHeightDp.isNaN() || contentHeightDp < 0f) return false
        return measuredCardHeightDp(contentHeightDp, fontScale) >= contentHeightDp.coerceAtLeast(0f)
    }

    // ---- Phase 188: dark-theme card border (risk #3) ----

    /** Width of the hairline border that separates cards from near-black surfaces. */
    const val GALLERY_CARD_BORDER_WIDTH_DP = 1f

    /** Alpha of `scheme.outlineVariant` on the border — visible on dark themes. */
    const val GALLERY_CARD_BORDER_ALPHA = 0.35f
}
