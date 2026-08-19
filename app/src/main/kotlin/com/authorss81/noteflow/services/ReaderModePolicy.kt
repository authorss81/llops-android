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
     * Proportional leading multiplier applied on top of a style's OWN line
     * height. The app's type scale already gives every style a leading ratio
     * (e.g. bodyLarge 16/24 — a 1.5× ratio over its type size), so multiplying
     * the base line height by a factor ABOVE 1.0 guarantees reader mode always
     * WIDENS the leading instead of the phase-158 bug where a fixed fraction
     * of the type size (1.35×) actually tightened it below the default. Every
     * value stays derived from the already-scaled theme metrics — never an
     * absolute size override that would break accessibility scaling.
     */
    const val BODY_LINE_HEIGHT_MULTIPLIER = 1.15f

    /**
     * Fallback leading ratio used only when a base style does not declare its
     * own line height (the type-scale default for body roles).
     */
    const val DEFAULT_BASE_LEADING_RATIO = 1.5f

    /**
     * Whether the reader layout (column cap + widened leading) applies. Kept
     * as a function so the UI decision "am I in reader layout?" routes through
     * one testable entry point instead of scattered boolean uses.
     */
    fun shouldUseReaderLayout(readerMode: Boolean): Boolean = readerMode

    /**
     * The target line height for a style in reader mode. Pass the base style's
     * already-scaled metrics — its type size AND its own line height. Reader
     * mode multiplies the base line height by [BODY_LINE_HEIGHT_MULTIPLIER]
     * (falling back to the [DEFAULT_BASE_LEADING_RATIO] ratio when the base
     * line height is unset), so large-font users keep a proportional — never
     * fixed — ratio and the reader leading is always wider than the default.
     */
    fun readerLineHeightSp(baseFontSizeSp: Float, baseLineHeightSp: Float): Float {
        val baseLeading = if (baseLineHeightSp > 0f) baseLineHeightSp else baseFontSizeSp * DEFAULT_BASE_LEADING_RATIO
        return baseLeading * BODY_LINE_HEIGHT_MULTIPLIER
    }

    /**
     * Reader mode is the DEFAULT post-capture destination: a note created from
     * a share-sheet clip should open in reading/focus mode so the user reads
     * the clipping immediately rather than dropping into an editor.
     */
    fun defaultReaderForCapturedNote(captureArrived: Boolean): Boolean =
        captureArrived
}