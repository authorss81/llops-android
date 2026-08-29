package com.authorss81.noteflow.services

/**
 * Phase 238 — floating / freeform / split-window classification, pure JVM.
 *
 * Android doesn't hand the app a clean "in a floating window" signal: the flag
 * to look at ([android.app.Activity.isInMultiWindowMode]) only says *multi*,
 * not *freeform*, and freeform windows are otherwise indistinguishable from a
 * small landscape tablet. So this policy classifies by WINDOW SHAPE using the
 * real constraints the composables see (window size class + BoxWithConstraints),
 * then decides the two things layout needs:
 *
 *  1. whether content must treat the window as "floating" (give side UI back to
 *     the paper/editor, keep a minimum readable content width),
 *  2. whether the ONE-TIME, non-alarming notice should surface (AGENTS.md
 *     hardware rule — never silent degradation, one notice, once per install).
 */
object FloatingWindowPolicy {

    /** Settings key for the once-per-install notice (SettingsManager). */
    const val NOTICE_SHOWN_KEY = "floating_window_notice_shown"

    /**
     * A window that is neither phone-narrow nor tablet-tall is treated as
     * floating: it is wide enough to tempt a side rail, but its short height
     * betrays a freeform/split surface rather than a real landscape tablet.
     */
    const val FLOATING_MAX_HEIGHT_DP = 660

    /** A real tablet/landscape surface is ~16:9+; below 1.25 the shape is square-ish. */
    const val FLOATING_MAX_ASPECT = 1.25f

    /**
     * True when the window reads like a floating/split surface AND the activity
     * reports multi-window (so ordinary portrait-phone letterboxing never trips
     * the notice).
     */
    fun isLikelyFloatingWindow(widthDp: Int, heightDp: Int, inMultiWindow: Boolean): Boolean {
        if (!inMultiWindow) return false
        if (widthDp <= AdaptiveLayoutPolicy.COMPACT_MAX_WIDTH_DP) return false
        return heightDp <= FLOATING_MAX_HEIGHT_DP &&
            widthDp.toFloat() / heightDp.toFloat() <= FLOATING_MAX_ASPECT + 0.01f
    }

    /** The one-time notice has already been shown this install. */
    fun noticeDue(alreadyShown: Boolean): Boolean = !alreadyShown
}