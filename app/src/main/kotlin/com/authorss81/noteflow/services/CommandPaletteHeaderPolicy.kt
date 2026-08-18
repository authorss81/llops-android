package com.authorss81.noteflow.services

/**
 * Command-palette header chrome policy (Phase 132).
 *
 * Pure JVM. Single source of truth for the header strings so the layout can
 * never drift from the copy, plus the hint truncation/ellipsis decision.
 *
 * The rendering fix lives in the composable (title on its own full-width line,
 * shortcut hint beneath it — both single-line/ellipsized), but the hint string
 * and its truncation math are decision-table-extracted here so they are
 * JVM-testable: a hint is truncated to at most [maxChars] characters, trading
 * the tail for a single [ELLIPSIS] character, and [shortcutHint] answers what
 * the rendered hint SHOULD be at a given width budget.
 */
object CommandPaletteHeaderPolicy {

    /** Header title — kept at full width on every viewport. */
    const val TITLE = "Command Palette"

    /** Full keyboard + gesture hint shown beneath the header title. */
    const val SHORTCUT_HINT = "⌘ ↑/↓ · Enter · two-finger swipe down to open"

    /** Ellipsis glyph used when a hint overflows its width budget. */
    const val ELLIPSIS = "…"

    /** Default hint character budget for [shortcutHint]. */
    const val DEFAULT_HINT_MAX_CHARS = 40

    /**
     * The hint that fits a [maxChars] budget: the full [SHORTCUT_HINT] when it
     * fits, otherwise a truncated copy ending in [ELLIPSIS]. Blank/negative
     * budgets yield the ELLIPSIS-only marker (fail closed, never a full
     * un-truncated string).
     */
    fun shortcutHint(maxChars: Int = DEFAULT_HINT_MAX_CHARS): String =
        truncate(SHORTCUT_HINT, maxChars)

    /**
     * Truncate [text] to at most [maxChars] characters, dropping the tail for
     * a single [ELLIPSIS] when [text] overflows. A budget smaller than the
     * ELLIPSIS length yields the ELLIPSIS alone (never a raw sub-ellipsis
     * fragment).
     */
    fun truncate(text: String, maxChars: Int): String {
        val cap = maxChars.coerceAtLeast(0)
        if (cap < ELLIPSIS.length) return ELLIPSIS
        return if (text.length <= cap) text else text.take(cap - ELLIPSIS.length) + ELLIPSIS
    }
}