package com.authorss81.noteflow

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 167 (2026-08-19): bottom-navigation-bar overlay regression guard.
 *
 * User feedback: on mobile "some messages and the calendar go below it (hidden
 * behind it)" — content rendered UNDER the device's bottom navigation bar
 * (gesture pill / 3-button bar) because the app draws edge-to-edge but a
 * surface anchored to the window bottom did not account for the navigation-bar
 * inset. The fix is DYNAMIC (reads the real system inset) — never a hard-coded
 * pixel height — so it is correct in portrait/landscape, gesture/3-button nav
 * and on small (360dp) and large screens.
 *
 * Pinned here so a future edit cannot silently regress any surface:
 *   - Root `SnackbarHost` (MainActivity) is the ONLY transient-message surface
 *     that lives OUTSIDE a Scaffold; it must carry `navigationBarsPadding()` so
 *     snackbars float ABOVE the nav bar, not under it.
 *   - The four content screens apply the Scaffold innerPadding (which includes
 *     the M3 default `contentWindowInsets` = system bars) to their content root,
 *     so page lists / Calendar / canvas / graph always end above the bar.
 *   - CalendarView's pages list is `weight(1f)`-bounded inside that padded
 *     content, so its LAST row scrolls above the bar rather than under it.
 *   - The three edge-to-edge recovery screens (no Scaffold) carry
 *     `navigationBarsPadding()` so their bottom buttons/error text scroll above
 *     the bar.
 */
class Phase167BottomNavOverlayTest {

    private fun mainSource(rel: String): String {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            val d: File = dir
            File(d, "src/main/kotlin/com/authorss81/noteflow/$rel").takeIf { it.isFile }?.let { return it.readText() }
            File(d, "src/main/kotlin/$rel").takeIf { it.isFile }?.let { return it.readText() }
            File(d, "app/src/main/kotlin/com/authorss81/noteflow/$rel").takeIf { it.isFile }?.let { return it.readText() }
            dir = d.parentFile
        }
        throw AssertionError("could not locate app/src/main/kotlin/$rel from ${start.path}")
    }

    // --- Root SnackbarHost (messages never under the bar) -------------

    @Test
    fun `root SnackbarHost is inset above the navigation bar`() {
        val src = mainSource("MainActivity.kt")
        val hostStart = src.indexOf("SnackbarHost(")
        val hostEnd = src.indexOf("B1-PLAT-2 (phase-58)", hostStart)
        val host = src.substring(hostStart, hostEnd + 40)
        assertTrue("root SnackbarHost must exist", hostStart >= 0)
        assertTrue(
            "root SnackbarHost must carry the dynamic navigation-bar inset",
            host.contains("navigationBarsPadding()")
        )
        assertTrue(
            "root SnackbarHost must stay bottom-centered",
            host.contains("align(Alignment.BottomCenter)")
        )
        // Review-fix: the inset must sit INSIDE the bottom alignment (host is
        // bottom-centered first, THEN lifted by the dynamic inset). Pinning the
        // order stops a future edit from moving `navigationBarsPadding()` onto a
        // surface that no longer sits at the window bottom.
        val alignIdx = host.indexOf("align(Alignment.BottomCenter)")
        val insetIdx = host.indexOf("navigationBarsPadding()")
        assertTrue("align must precede the inset in the modifier chain", alignIdx in 0 until insetIdx)
        // The inset must be dynamic — no hard-coded pixel bottom for the bar.
        assertFalse(
            "the snackbar offset must not be a hard-coded pixel height",
            host.contains("60.dp") || host.contains("50.dp")
        )
    }

    // --- Screen content roots apply Scaffold innerPadding -------------

    @Test
    fun `HomeScreen content applies Scaffold innerPadding (inset-aware)`() {
        val src = mainSource("ui/screens/HomeScreen.kt")
        val before = src.substring(0, src.indexOf(") { padding ->"))
        val after = src.substring(src.indexOf(") { padding ->"))
        assertTrue("HomeScreen must use a Scaffold", before.contains("Scaffold("))
        assertTrue(
            "HomeScreen content root must apply innerPadding",
            after.substring(0, 400).contains(".padding(padding)")
        )
        // The calendar lints the same padded container as every page list.
        assertTrue(
            "Calendar page list must live inside the padded Scaffold content",
            after.contains("CalendarView(pages = activePageList")
        )
    }

    @Test
    fun `EditorScreen content applies Scaffold innerPadding (inset-aware)`() {
        val src = mainSource("ui/screens/EditorScreen.kt")
        assertTrue("EditorScreen must use a Scaffold", src.contains("Scaffold("))
        val contentSlot = src.substring(src.indexOf(") { padding ->"), src.indexOf(") { padding ->") + 220)
        assertTrue(
            "EditorScreen content root must apply innerPadding beneath the bar",
            contentSlot.contains(".padding(padding)")
        )
    }

    @Test
    fun `MarkdownPreviewScreen content applies Scaffold innerPadding (inset-aware)`() {
        val src = mainSource("ui/screens/MarkdownPreviewScreen.kt")
        assertTrue("preview must use a Scaffold", src.contains("Scaffold("))
        val contentSlot = src.substring(src.indexOf(") { padding ->"), src.indexOf(") { padding ->") + 220)
        assertTrue(
            "preview content root must apply innerPadding beneath the bar",
            contentSlot.contains(".padding(padding)")
        )
        // The editor pane also lifts the IME so the on-screen keyboard cannot
        // cover the row being typed.
        assertTrue("preview editor pane must respect the IME inset", src.contains(".imePadding()"))
    }

    @Test
    fun `KnowledgeGraphScreen content applies Scaffold innerPadding (inset-aware)`() {
        val src = mainSource("ui/screens/KnowledgeGraphScreen.kt")
        assertTrue("graph must use a Scaffold", src.contains("Scaffold("))
        val contentSlot = src.substring(src.indexOf(") { padding ->"), src.indexOf(") { padding ->") + 220)
        assertTrue(
            "graph content root must apply innerPadding beneath the bar",
            contentSlot.contains(".padding(padding)")
        )
    }

    // --- CalendarView pages list stays above the bar -------------------

    @Test
    fun `CalendarView pages list is weight-bounded inside the padded content`() {
        val src = mainSource("ui/components/CalendarView.kt")
        val listStart = src.lastIndexOf("LazyColumn(")
        assertTrue(
            "calendar pages list must be weight-bounded so its last row stays in the padded container",
            src.substring(listStart, listStart + 200).contains("Modifier.weight(1f)")
        )
        // The month grid is a fixed-height non-scroll region; the *pages* list is
        // the scrollable one — it must never be a fixed height that could run off
        // the bottom of the padded container.
        assertFalse(
            "calendar pages list must not be a fixed pixel height",
            src.substring(listStart, listStart + 200).contains(".height(")
        )
    }

    // --- Edge-to-edge recovery screens (no Scaffold) --------------------

    @Test
    fun `recovery screens carry the navigation-bar inset on their scroll content`() {
        val src = mainSource("MainActivity.kt")
        listOf("RestoreBlockedScreen", "CorruptionRecoveryScreen", "KeystoreKeyLostScreen").forEach { name ->
            val start = src.indexOf("private fun $name")
            assertTrue("$name must exist in MainActivity", start >= 0)
            val nextFun = src.indexOf("private fun", start + 1).let { if (it > 0) it else src.length }
            val block = src.substring(start, nextFun)
            assertTrue(
                "$name must be inset above the navigation bar",
                block.contains("navigationBarsPadding()")
            )
            // Phase-167 review fix: the same Scaffold-less screens draw edge-to-
            // edge at the TOP too (transparent status bar), so the scroll content
            // must consume the status-bar inset as well — else the first row
            // (title) slides under the status bar instead of under the nav bar.
            assertTrue(
                "$name must be inset below the status bar (review-fix)",
                block.contains("statusBarsPadding()")
            )
        }
    }

    // --- No hard-coded fixed-pixel bottom bar --------------------------

    @Test
    fun `no surface hard-codes a pixel height for the bottom bar`() {
        val main = mainSource("MainActivity.kt")
        // Search for a fixed-px bottom spacer/pad attached to a bottom-anchored
        // overlay: any `height(48.dp)`/`50.dp`-style constant next to Alignment.Bottom.
        assertFalse(
            "the root overlays must not be offset by a fixed pixel height",
            main.contains("bottom = 50.dp") || main.contains("bottom = 60.dp")
        )
        val calendar = mainSource("ui/components/CalendarView.kt")
        assertFalse(
            "calendar list bottom must not be a hard-coded pixel offset",
            calendar.contains("bottom = 48.dp")
        )
    }
}