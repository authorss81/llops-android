package com.authorss81.noteflow.services

/**
 * Phase 266 (WCAG 2.2 AA pass 1): single decision table for accessibility
 * minimums so UI call sites never hardcode magic numbers.
 *
 * Pure JVM (no Compose/Android imports) so the contract is unit-testable.
 *
 * Honesty notes (verified 2026-09-12 against the tree):
 * - Most `contentDescription = null` hits are DECORATIVE icons paired with a
 *   text label (DropdownMenuItem leadingIcon, FilterChip leadingIcon, Button
 *   Icon+Text rows, empty-state illustration + heading). Null is CORRECT there
 *   per TalkBack guidance — only an icon-ONLY control with no text sibling
 *   must carry a label. See [nullAllowedForDecorative].
 * - The 28/26/36dp glyphs in EditorScreen and the 32dp LockScreen glyph already
 *   ride `minimumInteractiveComponentSize()` (48dp hit area); the dp size is
 *   the VISUAL glyph, not the touch target. See [meetsTouchTarget].
 * - KnowledgeGraph settle tweens (900/600ms), FluidPageReveal, and the canvas
 *   nav/zoom/sticky springs were ALREADY reduce-motion gated; the live gaps
 *   were ConfettiOverlay (1800ms, ungated — now capped at
 *   [CELEBRATION_DURATION_MS] and suppressed under reduce-motion) and the graph
 *   infinite pulse (transition kept running while its draw was gated — now the
 *   transition itself is only created when [shouldAnimate] is true).
 *   See [shouldAnimate].
 * - Contrast ratios here are code-level estimates, NOT lab-measured values:
 *   0.6f raises the unpinned pin glyph toward 3:1 but is not a certified pass.
 * - [meetsTouchTarget], [meetsLabelSize] and [meetsMotionBudget] are audit
 *   contracts pinned by unit tests; the production wirings are
 *   [CELEBRATION_DURATION_MS] (budget enforced by construction in
 *   ConfettiOverlay), [MIN_LABEL_TEXT_SP] (referenced by EditorScreen
 *   captions) and [UNPINNED_ICON_ALPHA] (referenced by HomeScreen).
 */
object A11yPolicy {

    /** WCAG 2.5.8 target size (AA): 24x24 CSS px minimum; Material/AA practice: 48dp hit area. */
    const val MIN_TOUCH_TARGET_DP = 48

    /** Smallest label text that stays legible at 1.0x scale (7sp dock captions were unreadable; matches labelSmall default). */
    const val MIN_LABEL_TEXT_SP = 11

    /**
     * Unpinned pin glyph tint alpha. 0.4f on onSurfaceVariant estimated ~2.1:1
     * against the card surface (WCAG 1.4.11 non-text contrast needs 3:1 for a
     * MEANINGFUL icon — the unpinned state is tappable, so it is meaningful).
     * 0.6f raises opacity toward 3:1 on light and dark surfaces (estimate, not
     * lab-measured — see class KDoc).
     */
    const val UNPINNED_ICON_ALPHA = 0.6f

    /** Pre-fix value, kept so the regression pin can assert the direction of change. */
    const val LEGACY_UNPINNED_ICON_ALPHA = 0.4f

    /** Celebratory/ambient motion budget cap (confetti ran 1800ms ungated). */
    const val MAX_AMBIENT_ANIMATION_MS = 1000

    /** Confetti flight duration: within [MAX_AMBIENT_ANIMATION_MS] by construction. */
    const val CELEBRATION_DURATION_MS = 800

    /**
     * Whether an animation may run. Reduce-motion (system animator scale 0 or
     * in-app setting) snaps instead — every motion call site must branch on this.
     */
    fun shouldAnimate(reduceMotion: Boolean): Boolean = !reduceMotion

    /**
     * Whether a null contentDescription is correct for an icon: ONLY when a
     * text sibling already exposes the meaning ([hasTextSibling]) or the icon
     * is pure illustration ([isPureIllustration], e.g. empty-state art under a
     * heading). An icon-ONLY control (no text, no illustration context) must
     * carry a real label.
     */
    fun nullAllowedForDecorative(hasTextSibling: Boolean, isPureIllustration: Boolean = false): Boolean =
        hasTextSibling || isPureIllustration

    /**
     * Whether a visual glyph of [glyphDp] inside a [hitAreaDp] touch target
     * meets the AA target-size bar. The glyph may be smaller than 48dp (e.g.
     * 16-36dp icons) as long as the HIT AREA (minimumInteractiveComponentSize)
     * reaches [MIN_TOUCH_TARGET_DP].
     */
    fun meetsTouchTarget(glyphDp: Int, hitAreaDp: Int): Boolean =
        glyphDp > 0 && hitAreaDp >= MIN_TOUCH_TARGET_DP

    /** Label text legibility floor. */
    fun meetsLabelSize(textSp: Int): Boolean = textSp >= MIN_LABEL_TEXT_SP

    /** Ambient animation duration budget. */
    fun meetsMotionBudget(durationMs: Int): Boolean =
        durationMs in 1..MAX_AMBIENT_ANIMATION_MS

    /**
     * TalkBack description for the ink canvas surface: a stable label (never
     * stroke content — titles/bodies stay out of a11y strings the same way
     * they stay out of logs). The live count lives in [canvasStateDescription].
     * Live (uncommitted) ink is deliberately NOT reported: the active-point
     * list mutates per pen sample and a semantics read would subscribe
     * composition to per-sample recomposition.
     */
    fun canvasContentDescription(): String =
        "Drawing canvas"

    fun canvasStateDescription(committedStrokes: Int): String =
        "$committedStrokes strokes"
}
