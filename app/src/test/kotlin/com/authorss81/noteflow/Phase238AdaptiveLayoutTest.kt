package com.authorss81.noteflow

import com.authorss81.noteflow.services.AdaptiveLayoutPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 238 — adaptive-layout decision-table regression guard.
 *
 * Pins the thresholds and shape rules the responsive layout (tablet / floating
 * window / landscape) is built on, so a future edit cannot silently regress the
 * exact failure modes this phase fixed:
 *
 *  - a 360dp phone stays single-pane with the sidebar in a drawer,
 *  - a 640dp floating window no longer renders the crushing dual 260+240dp
 *    panels (it routes to the unified 240dp rail so content keeps a usable width),
 *  - a 800x360 landscape phone collapses the rail into a drawer,
 *  - a 1280x800 tablet landscape gets the full unified rail,
 *  - the ink-bar posture follows the window SHAPE (a 700x700 floating window is
 *    NOT "landscape").
 */
class Phase238AdaptiveLayoutTest {

    // ---- width classes ----

    @Test
    fun `360dp phone is compact`() {
        assertTrue(AdaptiveLayoutPolicy.isCompactWidth(360))
        assertFalse(AdaptiveLayoutPolicy.isExpandedWidth(360))
    }

    @Test
    fun `600-839 dp window is medium`() {
        assertTrue(AdaptiveLayoutPolicy.isMediumWidth(600))
        assertTrue(AdaptiveLayoutPolicy.isMediumWidth(799))
        assertTrue(AdaptiveLayoutPolicy.isMediumWidth(839))
        assertFalse(AdaptiveLayoutPolicy.isMediumWidth(599))
        assertFalse(AdaptiveLayoutPolicy.isMediumWidth(840))
    }

    @Test
    fun `840dp and above is expanded`() {
        assertTrue(AdaptiveLayoutPolicy.isExpandedWidth(840))
        assertTrue(AdaptiveLayoutPolicy.isExpandedWidth(1280))
        assertFalse(AdaptiveLayoutPolicy.isExpandedWidth(839))
    }

    // ---- shape rules ----

    @Test
    fun `landscape phone 800x360 hides the rail`() {
        assertTrue(AdaptiveLayoutPolicy.isLandscapePhone(800, 360))
        assertTrue(AdaptiveLayoutPolicy.sidebarIsDrawer(800, 360))
        assertFalse(AdaptiveLayoutPolicy.useSideRail(800, 360))
    }

    @Test
    fun `square 700x700 floating window is neither compact nor landscape`() {
        assertFalse(AdaptiveLayoutPolicy.isCompactWidth(700))
        assertFalse(AdaptiveLayoutPolicy.isLandscapePhone(700, 700))
        assertTrue(AdaptiveLayoutPolicy.useSideRail(700, 700))
    }

    @Test
    fun `phone portrait 360x800 keeps the drawer`() {
        assertTrue(AdaptiveLayoutPolicy.sidebarIsDrawer(360, 800))
        assertFalse(AdaptiveLayoutPolicy.useSideRail(360, 800))
    }

    // ---- the floating-window regression: dual panels must fit ----

    @Test
    fun `medium 620x700 window never renders dual panels`() {
        // The reported bug: NotebookPanel(260) + SectionPanel(240) on a ~640dp
        // floating window left the content column a hair-line "Sections" strip.
        assertFalse("dual panels must not render below 840dp", AdaptiveLayoutPolicy.useDualSidePanels(620, 700, sidebarLayoutPreferred = false))
        assertTrue("the unified rail must absorb medium widths", AdaptiveLayoutPolicy.useUnifiedSidebarRail(620, 700, sidebarLayoutPreferred = false))
    }

    @Test
    fun `expanded tablet preference picks dual panels vs unified rail`() {
        assertTrue(AdaptiveLayoutPolicy.useDualSidePanels(1280, 800, sidebarLayoutPreferred = false))
        assertFalse("unified-sidebar preference keeps a single rail", AdaptiveLayoutPolicy.useDualSidePanels(1280, 800, sidebarLayoutPreferred = true))
        assertTrue(AdaptiveLayoutPolicy.useUnifiedSidebarRail(1280, 800, sidebarLayoutPreferred = true))
        assertFalse("classic-dual preference renders the dual stack, not the rail", AdaptiveLayoutPolicy.useUnifiedSidebarRail(1280, 800, sidebarLayoutPreferred = false))
    }

    @Test
    fun `medium windows always use the unified rail`() {
        assertTrue(AdaptiveLayoutPolicy.useUnifiedSidebarRail(620, 700, sidebarLayoutPreferred = true))
        assertTrue("too narrow for the dual stack even when classic-preferred", AdaptiveLayoutPolicy.useUnifiedSidebarRail(620, 700, sidebarLayoutPreferred = false))
    }

    // ---- rail geometry ----

    @Test
    fun `rail shrinks on medium windows`() {
        assertEquals(240, AdaptiveLayoutPolicy.unifiedRailWidthDp(620, 700))
        assertEquals(280, AdaptiveLayoutPolicy.unifiedRailWidthDp(1280, 800))
        assertEquals(0, AdaptiveLayoutPolicy.unifiedRailWidthDp(360, 800))
        assertEquals(0, AdaptiveLayoutPolicy.unifiedRailWidthDp(800, 360))
    }

    // ---- ink bar posture follows window shape ----

    @Test
    fun `ink bar is horizontal on portrait and square windows`() {
        assertFalse("taller-than-wide keeps the pill", AdaptiveLayoutPolicy.inkBarIsLandscape(360, 800))
        assertFalse("a square floating window is not landscape", AdaptiveLayoutPolicy.inkBarIsLandscape(700, 700))
    }

    @Test
    fun `ink bar is vertical only on wider-than-tall windows`() {
        assertTrue(AdaptiveLayoutPolicy.inkBarIsLandscape(800, 360))
        assertTrue(AdaptiveLayoutPolicy.inkBarIsLandscape(1280, 800))
    }

    // ---- overflow menu width cap ----

    @Test
    fun `menu width cap follows window width`() {
        val narrow = AdaptiveLayoutPolicy.maxMenuWidthDp(360)
        assertTrue("narrow window must cap menu width", narrow < 360f)
        assertEquals(0.9f * 360f, narrow, 0.5f)
        // Roomier windows keep Material 3's natural spread (the absolute ceiling).
        assertEquals(AdaptiveLayoutPolicy.ABSOLUTE_MAX_MENU_WIDTH_DP, AdaptiveLayoutPolicy.maxMenuWidthDp(1000), 0.5f)
        assertEquals("degenerate input falls back to the floor", AdaptiveLayoutPolicy.MIN_MENU_WIDTH_DP, AdaptiveLayoutPolicy.maxMenuWidthDp(0), 0.5f)
    }

    // ---- Markdown split pane usability (Phase 238) ----

    @Test
    fun `split mode coerces to the single editor below 320dp`() {
        assertFalse(AdaptiveLayoutPolicy.splitModeUsable(319))
        assertTrue(AdaptiveLayoutPolicy.splitModeUsable(320))
        assertTrue(AdaptiveLayoutPolicy.splitModeUsable(1280))
    }

    @Test
    fun `side-by-side split forces stacked panes under the two-pane floor`() {
        // F4 review-fix: the split Row consumes 8dp gap + ~1dp divider + 8dp gap
        // = 17dp of chrome, so the side-by-side floor is 17 + 2*300 = 617dp.
        assertEquals(617, AdaptiveLayoutPolicy.SPLIT_SIDE_BY_SIDE_MIN_WIDTH_DP)
        assertEquals(17, AdaptiveLayoutPolicy.SPLIT_PANE_HORIZONTAL_CHROME_DP)
        assertFalse("600dp cannot keep both weighted panes at MIN_CONTENT_WIDTH_DP",
            AdaptiveLayoutPolicy.splitPanesFitSideBySide(600))
        assertFalse("one dp under the floor still coerces stacked",
            AdaptiveLayoutPolicy.splitPanesFitSideBySide(616))
        assertTrue(AdaptiveLayoutPolicy.splitPanesFitSideBySide(617))
        assertTrue(AdaptiveLayoutPolicy.splitPanesFitSideBySide(1280))
    }

    // ---- dual-panel rail floor (F1/F7 review-fixes) ----

    @Test
    fun `dual panels need both the 840dp class floor and the content floor`() {
        // On a >=840dp window the stack fits, but a future width/panel change must
        // still not starve content: the rail floor (window - 260 - 240 >= 300)
        // gates dual panels on top of the expanded class.
        assertTrue(AdaptiveLayoutPolicy.useDualSidePanels(840, 800, sidebarLayoutPreferred = false))
        assertTrue(AdaptiveLayoutPolicy.useDualSidePanels(1280, 800, sidebarLayoutPreferred = false))
        assertFalse("classic preference is overridden (documented) on medium windows",
            AdaptiveLayoutPolicy.useDualSidePanels(700, 800, sidebarLayoutPreferred = false))
    }
}
