package com.authorss81.noteflow.services

/**
 * Phase-127 (plugin-store compact descriptions): per-card description
 * presentation decision table — pure JVM.
 *
 * The Plugin Store card list is dominated by long plugin descriptions, so each
 * card collapses its description to at most [COLLAPSED_MAX_LINES]
 * (ellipsized) and reveals the FULL text only while that ONE card is expanded
 * (in-memory, per-card, session-only — never persisted). [needsExpandToggle]
 * decides whether a description is long enough to warrant the small
 * More/Less affordance next to it; [collapsedSummary] is the pure-JVM mirror
 * of the visual 2-line ellipsis (a bounded, ellipsis-terminated prefix) so the
 * summary truncation semantics are unit-testable without Compose layout.
 *
 * The composable callers live in `ui/components/PluginStoreDescriptionBlock.kt`
 * (the only Android-facing consumer); this object is plain string/char math.
 */
object PluginStoreCardPolicy {

    /** Maximum lines the collapsed two-line summary may occupy (Compose `maxLines`). */
    const val COLLAPSED_MAX_LINES = 2

    /**
     * Character budget of the collapsed summary. A description no longer than
     * this fits comfortably on [COLLAPSED_MAX_LINES] at `bodySmall` across the
     * supported dialog widths — longer descriptions are the ones the store card
     * collapses, so they are the ones that get the More/Less affordance.
     */
    const val MAX_SUMMARY_CHARS = 100

    /** True when [description] is long enough that collapsing hides content. */
    fun needsExpandToggle(description: String): Boolean =
        description.length > MAX_SUMMARY_CHARS

    /**
     * The collapsed summary string: unchanged when short, otherwise the first
     * [MAX_SUMMARY_CHARS] characters with whitespace trimmed at the cut plus a
     * trailing ellipsis. Reference mirror of the visual `maxLines` + ellipsis
     * rendering (used for tests/decision pins — the UI renders via Compose).
     */
    fun collapsedSummary(description: String): String =
        if (description.length <= MAX_SUMMARY_CHARS) description
        else description.take(MAX_SUMMARY_CHARS).trimEnd() + "…"

    /** Per-card reveal state while the dialog is open (never persisted). */
    enum class Reveal { COLLAPSED, EXPANDED }

    /** Collapsed is ALWAYS the default state, short or long description. */
    fun defaultReveal(): Reveal = Reveal.COLLAPSED

    /** Flip a card's reveal state (COLLAPSED ⇄ EXPANDED). */
    fun toggle(reveal: Reveal): Reveal = when (reveal) {
        Reveal.COLLAPSED -> Reveal.EXPANDED
        Reveal.EXPANDED -> Reveal.COLLAPSED
    }

    /** Human label shown by the affordance for each reveal state. */
    fun toggleLabel(reveal: Reveal): String = if (reveal == Reveal.EXPANDED) "Less" else "More"
}