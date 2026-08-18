package com.authorss81.noteflow.services

/**
 * Command-palette header chrome policy (Phase 132).
 *
 * Pure JVM. Single source of truth for the header copy so the layout can
 * never drift from the text it renders.
 *
 * Truncation is deliberately left to the composable: both strings render on
 * their own single line with `maxLines = 1` + `TextOverflow.Ellipsis`, so
 * Compose ellipsizes by pixel width (font-scale aware). A character-count
 * decision table was extracted here initially (Phase 132 review: finding 1),
 * but it was dead production code — the composable never called it — so it is
 * gone; a char-budget truncation that never matches the rendered output would
 * only introduce a second, disconnected source of truth.
 */
object CommandPaletteHeaderPolicy {

    /** Header title — kept at full width on every viewport. */
    const val TITLE = "Command Palette"

    /** Full keyboard + gesture hint shown beneath the header title. */
    const val SHORTCUT_HINT = "⌘ ↑/↓ · Enter · two-finger swipe down to open"
}