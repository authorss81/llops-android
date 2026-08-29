package com.authorss81.noteflow.services

/**
 * Phase 238 — responsive-layout decision table, pure JVM.
 *
 * The app historically assumed a phone-portrait window: fixed-width side panels
 * (NotebookPanel 260dp + SectionPanel 240dp = 500dp), binary
 * portrait/landscape only via [android.content.res.Configuration.orientation],
 * and no shape handling for floating windows / multi-window splits. All layout
 * branches below take the CURRENT WINDOW size in dp and answer the three
 * questions every adaptive surface needs:
 *
 *  1. does the sidebar live in a drawer or on a side rail?
 *     (compact width OR landscape-phone height -> drawer)
 *  2. is there room for the dual fixed panels, or only one rail?
 *     (the dual 260dp+240dp stack needs a >=840dp window; on medium 600-839dp
 *     windows they crushed the content column to a hair-line — the reported
 *     floating-window regression)
 *  3. should the floating ink bar use the horizontal or vertical posture?
 *     (window SHAPE width>height — not the binary orientation flag — so a
 *     square floating window lands the same rule a real landscape rotation does)
 *
 * The material3 `WindowSizeClass` (Compact/Medium/Expanded per axis) supplies
 * the width classes at the activity root; the composables below additionally
 * read real `BoxWithConstraints` because a floating/freeform window's height is
 * shaped by the system, not by `Configuration.orientation`. All thresholds live
 * here so the source-pinning golden tests can pin them as literals.
 */
object AdaptiveLayoutPolicy {

    /** Compact width ceiling (dp) — same bound material3 uses (`< 600`). */
    const val COMPACT_MAX_WIDTH_DP = 599

    /** Expanded width floor (dp) — material3's MEDIUM/EXPANDED boundary. */
    const val EXPANDED_MIN_WIDTH_DP = 840

    /**
     * Landscape-phone / short-floating-window height floor (dp). Below this a
     * side rail is useless (the window is wide but short); the sidebar moves to
     * a drawer and the content takes the full width.
     */
    const val LANDSCAPE_PHONE_MAX_HEIGHT_DP = 479

    /** Room for the DUAL fixed panels (260dp notebook + 240dp section + divider). */
    const val DUAL_PANEL_MIN_WIDTH_DP = EXPANDED_MIN_WIDTH_DP

    // ---- width classes (module-private; callers prefer the bools below) ----

    fun isCompactWidth(widthDp: Int): Boolean = widthDp <= COMPACT_MAX_WIDTH_DP

    fun isMediumWidth(widthDp: Int): Boolean =
        widthDp > COMPACT_MAX_WIDTH_DP && widthDp < EXPANDED_MIN_WIDTH_DP

    fun isExpandedWidth(widthDp: Int): Boolean = widthDp >= EXPANDED_MIN_WIDTH_DP

    /**
     * A window that is wide enough to look "wide" but too short for a rail:
     * landscape phones and short floating windows.
     */
    fun isLandscapePhone(widthDp: Int, heightDp: Int): Boolean =
        widthDp > COMPACT_MAX_WIDTH_DP && heightDp <= LANDSCAPE_PHONE_MAX_HEIGHT_DP

    /** The sidebar belongs in a drawer (hamburger) instead of a side rail. */
    fun sidebarIsDrawer(widthDp: Int, heightDp: Int): Boolean =
        isCompactWidth(widthDp) || isLandscapePhone(widthDp, heightDp)

    /** A side rail is usable on this window shape. */
    fun useSideRail(widthDp: Int, heightDp: Int): Boolean =
        !sidebarIsDrawer(widthDp, heightDp)

    /**
     * Device preferences gate: when the user prefers the UNIFIED sidebar layout,
     * it wins on any side-rail shape. When they prefer the classic dual-panel
     * (notebook + section) stack, it may only render where it fits — a >=840dp
     * window; on medium windows the dual stack previously crushed the content
     * column (the floating-window regression).
     */
    fun useDualSidePanels(widthDp: Int, heightDp: Int, sidebarLayoutPreferred: Boolean): Boolean =
        useSideRail(widthDp, heightDp) &&
            !sidebarLayoutPreferred &&
            isExpandedWidth(widthDp)

    /** The UNIFIED sidebar renders on the rail when it fits (medium+ width, tall enough). */
    fun useUnifiedSidebarRail(widthDp: Int, heightDp: Int, sidebarLayoutPreferred: Boolean): Boolean =
        useSideRail(widthDp, heightDp) &&
            (sidebarLayoutPreferred || isMediumWidth(widthDp))

    /** UnifiedSidebar rail width (dp) — shrinks on medium windows to protect content. */
    fun unifiedRailWidthDp(widthDp: Int, heightDp: Int): Int =
        if (useSideRail(widthDp, heightDp)) {
            if (widthDp < EXPANDED_MIN_WIDTH_DP) COMPACT_RAIL_WIDTH_DP else DEFAULT_RAIL_WIDTH_DP
        } else 0

    const val DEFAULT_RAIL_WIDTH_DP = 280

    /** Medium-window rail: wide enough to hold a row, narrow enough to keep content. */
    const val COMPACT_RAIL_WIDTH_DP = 240

    /** NotebookPanel fixed width (dp) — only ever shown together with its sibling. */
    const val NOTEBOOK_PANEL_WIDTH_DP = 260

    /** SectionPanel fixed width (dp). */
    const val SECTION_PANEL_WIDTH_DP = 240

    // ---- ink bar posture (DockPosturePolicy callers) ----

    /**
     * Ink-bar posture follows the WINDOW SHAPE (width vs height), not the binary
     * orientation flag — a near-square floating window is NOT a "landscape"
     * surface: only a strictly-panorama window (wider than it is tall) gets the
     * vertical end-edge column; every other shape keeps the horizontal pill.
     */
    fun inkBarIsLandscape(widthDp: Int, heightDp: Int): Boolean = widthDp > heightDp

    // ---- overflow menus ----

    /** DropdownMenu width cap on a narrow window, so a wide menu never clips. */
    const val MAX_MENU_WIDTH_FRACTION_OF_SCREEN = 0.90f

    /** Floor (dp): never squeeze an overflow menu below roughly one wide row. */
    const val MIN_MENU_WIDTH_DP = 160f

    /** Ceiling (dp): roomy screens keep Material 3's natural width. */
    const val ABSOLUTE_MAX_MENU_WIDTH_DP = 520f

    fun maxMenuWidthDp(screenWidthDp: Int): Float {
        if (screenWidthDp <= 0) return MIN_MENU_WIDTH_DP
        val fromFraction = screenWidthDp * MAX_MENU_WIDTH_FRACTION_OF_SCREEN
        return fromFraction.coerceIn(MIN_MENU_WIDTH_DP, ABSOLUTE_MAX_MENU_WIDTH_DP)
    }

    // ---- minimum usable content width ----

    /**
     * Absolute floor for the EDITING column (dp). A floating window can be
     * drag-resized arbitrarily small; below this width the paper/editor is
     * unusable, so the surface must give side UI back to content before it can
     * starve the column — never the reverse.
     */
    const val MIN_CONTENT_WIDTH_DP = 300

    /**
     * Guaranteed content width once a [railWidthDp] side rail / panel is shown:
     * the rail is allowed to take width only while at least [MIN_CONTENT_WIDTH_DP]
     * stays with the content. Negative windows (degenerate) fall back to the floor.
     */
    fun effectiveContentWidthDp(windowWidthDp: Int, railWidthDp: Int): Int =
        (windowWidthDp - railWidthDp).coerceAtLeast(MIN_CONTENT_WIDTH_DP)

    /**
     * Below this content width the Editor / Markdown top chrome folds its
     * action row into a single overflow menu (the reported vertical-stack break)
     * instead of trying to lay every icon out on one line.
     */
    const val TOOLBAR_OVERFLOW_MAX_CONTENT_WIDTH_DP = 560

    fun chromeFoldsToOverflow(availableContentWidthDp: Int): Boolean =
        availableContentWidthDp < TOOLBAR_OVERFLOW_MAX_CONTENT_WIDTH_DP

    /** A side rail is worth its pixels only if content keeps its minimum width. */
    fun railFits(windowWidthDp: Int, railWidthDp: Int): Boolean =
        windowWidthDp - railWidthDp >= MIN_CONTENT_WIDTH_DP

    // ---- Markdown preview / editor split (Phase 238) ------------------------

    /**
     * Below this window width a two-pane editor/preview split (stacked or
     * side-by-side) has no chance of anything usable, so MarkdownPreviewScreen
     * coerces SPLIT mode to the single editing pane rather than crushing both
     * halves below [MIN_CONTENT_WIDTH_DP].
     */
    const val SPLIT_MODE_MIN_WIDTH_DP = 320

    fun splitModeUsable(widthDp: Int): Boolean = widthDp >= SPLIT_MODE_MIN_WIDTH_DP

    /**
     * A Left/Right split can host BOTH panes at [MIN_CONTENT_WIDTH_DP] each
     * (plus the divider) only when the window is this wide; below it the split
     * is forced Top/Bottom regardless of the user's HORIZONTAL selection — the
     * panes may be short but never crushed.
     */
    fun splitPanesFitSideBySide(widthDp: Int): Boolean =
        widthDp >= 2 * MIN_CONTENT_WIDTH_DP
}