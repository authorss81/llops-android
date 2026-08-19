package com.authorss81.noteflow.services

/**
 * Phase 158 (deferred ROADMAP 22.5) — reading/focus mode decision table for
 * markdown notes.
 *
 * A reader ("focus") mode strips the editing chrome, widens the leading and
 * caps the body to a comfortable column width so long-form content reads like
 * an article instead of an editor. Every number the UI needs lives here so the
 * layout decision is pure-JVM unit-testable and the composables can never
 * drift from a single source of truth.
 *
 * Constraints honored by the UI (not encoded here — they are Compose concerns):
 *  - reader mode is read-only by construction (the hybrid editor is never
 *    composed; only the PREVIEW pane renders), so long-press can never open an
 *    edit surface;
 *  - switching reader mode is INSTANT (no transition animation), which
 *    satisfies reduce-motion/remove-animations by not adding motion in the
 *    first place;
 *  - all type sizes stay relative to `MaterialTheme.typography` roles (which
 *    already scale with the system font scale / accessibility font size), so a
 *    user's larger-font setting is preserved — the policy only widens the
 *    LINE HEIGHT proportionally, never an absolute size.
 */
object ReaderModePolicy {

    /** Article-style reading measure: 68 dp less than a phone's full width. */
    const val MAX_COLUMN_WIDTH_DP = 680f

    /**
     * Proportional line-height multiplier applied on top of the base body
     * style. `TextStyle.lineHeight` set to this fraction of the base font size
     * gives comfortable long-form leading and STILL scales with the system
     * font-scale (it is derived from the already-scaled font size, never an
     * absolute sp override that breaks accessibility scaling).
     */
    const val BODY_LINE_HEIGHT_MULTIPLIER = 1.35f

    /**
     * Whether the reader layout (column cap + widened leading) applies. Kept
     * as a function so the UI decision "am I in reader layout?" routes through
     * one testable entry point instead of scattered boolean uses.
     */
    fun shouldUseReaderLayout(readerMode: Boolean): Boolean = readerMode

    /**
     * The target line height (in sp) for a body style in reader mode. Pass the
     * BASE style's base font size (the already-scaled one from the theme);
     * reader mode re-derives leading proportionally so large-font users keep a
     * proportional — never fixed — ratio.
     */
    fun readerLineHeightSp(baseFontSizeSp: Float): Float =
        baseFontSizeSp * BODY_LINE_HEIGHT_MULTIPLIER

    /**
     * Reader mode is the DEFAULT post-capture destination: a note created from
     * a share-sheet clip should open in reading/focus mode so the user reads
     * the clipping immediately rather than dropping into an editor.
     */
    fun defaultReaderForCapturedNote(captureArrived: Boolean): Boolean =
        captureArrived

    /** Non-localized UI label used by the reader toggle's content description. */
    const val READER_TOGGLE_LABEL = "Reader / focus mode"
}