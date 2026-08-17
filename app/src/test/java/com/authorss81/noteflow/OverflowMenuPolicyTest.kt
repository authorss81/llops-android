package com.authorss81.noteflow

import com.authorss81.noteflow.services.OverflowMenuPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure JVM tests for the Phase-120 overflow-menu sizing policy plus source-level
 * wiring pins proving that EVERY overflow dropdown in the app (home ⋮,
 * editor ⋮, markdown plugin menu, kind/subject pickers, …) carries both a
 * max-height cap derived from the screen height and a vertical scroll state.
 */
class OverflowMenuPolicyTest {

    // ---- maxMenuHeightDp ------------------------------------------------

    @Test
    fun `typical portrait phone caps at 60 percent of screen height`() {
        // 800dp screen -> 480dp menu, above Material 3's internal 288dp default
        // so the default stays 288 and nothing changes on roomy screens.
        assertEquals(480f, OverflowMenuPolicy.maxMenuHeightDp(800), 1e-4f)
        assertEquals(288f, OverflowMenuPolicy.maxMenuHeightDp(480), 1e-4f)
    }

    @Test
    fun `small and landscape screens shrink below the fixed default`() {
        // The pre-fix M3 default was a FIXED 288dp cap — taller than the usable
        // space on a landscape/small window, which is how bottom entries became
        // unreachable. The policy must come out smaller there.
        assertTrue(OverflowMenuPolicy.maxMenuHeightDp(360) < OverflowMenuPolicy.MATERIAL_DROPDOWN_MAX_HEIGHT_DP)
        assertEquals(216f, OverflowMenuPolicy.maxMenuHeightDp(360), 1e-4f)
        assertTrue(OverflowMenuPolicy.maxMenuHeightDp(320) < OverflowMenuPolicy.MATERIAL_DROPDOWN_MAX_HEIGHT_DP)
    }

    @Test
    fun `height cap never exceeds the screen fraction`() {
        for (screen in listOf(320, 360, 480, 600, 640, 800, 1280)) {
            val cap = OverflowMenuPolicy.maxMenuHeightDp(screen)
            assertTrue(
                "cap $cap for screen $screen must not exceed screen",
                cap <= screen * OverflowMenuPolicy.MAX_MENU_FRACTION_OF_SCREEN
            )
        }
    }

    @Test
    fun `tiny degenerate screens fall back to the usable floor`() {
        assertEquals(120f, OverflowMenuPolicy.maxMenuHeightDp(100), 1e-4f)
        assertEquals(120f, OverflowMenuPolicy.maxMenuHeightDp(0), 1e-4f)
        assertEquals(120f, OverflowMenuPolicy.maxMenuHeightDp(-5), 1e-4f)
    }

    @Test
    fun `tablet portrait is kept under the absolute ceiling`() {
        assertEquals(560f, OverflowMenuPolicy.maxMenuHeightDp(1280), 1e-4f)
        assertTrue(OverflowMenuPolicy.maxMenuHeightDp(1280) == OverflowMenuPolicy.ABSOLUTE_MAX_MENU_HEIGHT_DP)
    }

    // ---- overflow / item-count math -------------------------------------

    @Test
    fun `content overflow is decided strictly`() {
        assertTrue(OverflowMenuPolicy.contentOverflows(contentHeightDp = 361f, maxHeightDp = 360f))
        assertFalse(OverflowMenuPolicy.contentOverflows(contentHeightDp = 360f, maxHeightDp = 360f))
        assertFalse(OverflowMenuPolicy.contentOverflows(contentHeightDp = 200f, maxHeightDp = 360f))
    }

    @Test
    fun `estimated content height counts uniform rows`() {
        assertEquals(768f, OverflowMenuPolicy.estimatedContentHeightDp(16), 1e-4f)
        assertEquals(0f, OverflowMenuPolicy.estimatedContentHeightDp(0), 1e-4f)
        assertEquals(0f, OverflowMenuPolicy.estimatedContentHeightDp(-3), 1e-4f)
    }

    @Test
    fun `maintenance menu overflows on every screen and must scroll`() {
        // HomeScreen MaintenanceMenu = 16 rows. On an 800dp phone it is 768dp of
        // content vs a 480dp cap -> overflow. On a landscape 360dp screen the cap
        // is 216dp and still overflows. Either way items must be reachable BY
        // SCROLLING, never cut off.
        val content = OverflowMenuPolicy.estimatedContentHeightDp(16)
        for (screen in listOf(360, 640, 800)) {
            val cap = OverflowMenuPolicy.maxMenuHeightDp(screen)
            assertTrue("16-item menu must overflow a ${screen}dp screen", OverflowMenuPolicy.contentOverflows(content, cap))
            val visible = OverflowMenuPolicy.visibleItemCount(16, cap)
            assertTrue(visible in 1..15)
            assertTrue("a scrolled menu must reveal what the cap hides", visible < 16)
        }
    }

    @Test
    fun `editor overflow menu overflows and stays scrollable`() {
        // EditorScreen ⋮ = 17 rows (exports + toggles + clear).
        val content = OverflowMenuPolicy.estimatedContentHeightDp(17)
        val cap = OverflowMenuPolicy.maxMenuHeightDp(360)
        assertTrue(OverflowMenuPolicy.contentOverflows(content, cap))
        val visible = OverflowMenuPolicy.visibleItemCount(17, cap)
        assertTrue(visible in 1..16)
        // remaining rows are reachable only after a scroll
        assertEquals(17 - visible, hiddenItemCount(17, cap))
    }

    @Test
    fun `plugin menu with many entries still fits the cap`() {
        // Markdown plugin menu can list a dozen+ capability entries; the cap must
        // still hold and scrolling must reveal the rest.
        val content = OverflowMenuPolicy.estimatedContentHeightDp(14)
        val cap = OverflowMenuPolicy.maxMenuHeightDp(800)
        assertTrue(OverflowMenuPolicy.contentOverflows(content, cap))
        assertEquals(10, OverflowMenuPolicy.visibleItemCount(14, cap))
        assertEquals(4, hiddenItemCount(14, cap))
    }

    @Test
    fun `short menus never scroll and show every row`() {
        // A 3-row menu fits even the smallest screen -> all rows visible.
        for (screen in listOf(360, 640, 800)) {
            val cap = OverflowMenuPolicy.maxMenuHeightDp(screen)
            assertFalse(OverflowMenuPolicy.contentOverflows(OverflowMenuPolicy.estimatedContentHeightDp(3), cap))
            assertEquals(3, OverflowMenuPolicy.visibleItemCount(3, cap))
        }
    }

    @Test
    fun `visible item count guards degenerate inputs`() {
        assertEquals(0, OverflowMenuPolicy.visibleItemCount(0, 480f))
        assertEquals(0, OverflowMenuPolicy.visibleItemCount(-1, 480f))
        // unknown row geometry (non-positive) never hides rows
        assertEquals(5, OverflowMenuPolicy.visibleItemCount(5, 480f, rowHeightDp = 0f))
        // never reports more rows than exist
        assertEquals(2, OverflowMenuPolicy.visibleItemCount(2, 400f, rowHeightDp = 48f))
    }

    // ---- source wiring pins ---------------------------------------------

    /**
     * Every overflow dropdown in the app must pass BOTH a max-height cap AND a
     * vertical scroll state, so lower entries are always reachable on small /
     * landscape / large-font screens. The pin is per-file equality: each menu
     * open is matched by exactly one [overflowMenuScrollModifier] and exactly one
     * [overflowMenuScrollState] wiring.
     */
    @Test
    fun `every app dropdown menu has a max height cap and vertical scroll`() {
        val files = listOf(
            "ui/screens/HomeScreen.kt",
            "ui/screens/EditorScreen.kt",
            "ui/screens/MarkdownPreviewScreen.kt",
            "ui/screens/KnowledgeGraphScreen.kt",
            "ui/components/UnifiedSidebar.kt",
            "ui/components/MediaEmbedComponents.kt",
            "ui/components/SpreadsheetTableView.kt",
            "ui/components/Dialogs.kt",
            "ui/components/KanbanBoardView.kt",
            "ui/components/Phase16PluginDialogs.kt",
            "ui/components/LocalSendSendDialog.kt"
        )
        val menuOpen = Regex("\\b(?:Exposed)?DropdownMenu\\(")
        val capWiring = "overflowMenuScrollModifier()"
        val scrollWiring = "scrollState = overflowMenuScrollState()"
        for (relative in files) {
            val source = source(relative)
            val menus = menuOpen.findAll(source).count()
            val caps = source.windowed(capWiring.length).count { it == capWiring }
            val scrolls = source.windowed(scrollWiring.length).count { it == scrollWiring }
            assertEquals(
                "$relative: every DropdownMenu must pass a max-height cap (menus=$menus caps=$caps)",
                menus, caps
            )
            assertEquals(
                "$relative: every DropdownMenu must pass a vertical scroll state (menus=$menus scrolls=$scrolls)",
                menus, scrolls
            )
        }
    }

    @Test
    fun `m3 default dropdown cap is documented as the roomy-screen bound`() {
        // Guards the policy's premise: the roomy-screen bound is the fixed M3 cap,
        // and the derived cap must always stay <= that fixed default on big phones.
        assertEquals(288f, OverflowMenuPolicy.MATERIAL_DROPDOWN_MAX_HEIGHT_DP, 1e-4f)
        assertTrue(OverflowMenuPolicy.maxMenuHeightDp(480) <= OverflowMenuPolicy.MATERIAL_DROPDOWN_MAX_HEIGHT_DP)
        assertTrue(OverflowMenuPolicy.maxMenuHeightDp(440) <= OverflowMenuPolicy.MATERIAL_DROPDOWN_MAX_HEIGHT_DP)
    }

    // ---- helpers ----

    private fun hiddenItemCount(itemCount: Int, maxHeightDp: Float): Int {
        val visible = OverflowMenuPolicy.visibleItemCount(itemCount, maxHeightDp)
        return (itemCount - visible).coerceAtLeast(0)
    }

    private fun source(relativeMainPath: String): String {
        val root = repoRoot()
        val path = File(root, "app/src/main/kotlin/com/authorss81/noteflow/" + relativeMainPath)
        assertTrue("expected source file $relativeMainPath to exist", path.isFile)
        return path.readText()
    }

    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (File(dir, "gradle/libs.versions.toml").isFile && File(dir, "app").isDirectory) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}