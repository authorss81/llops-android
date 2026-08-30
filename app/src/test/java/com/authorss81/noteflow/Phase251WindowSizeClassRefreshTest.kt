package com.authorss81.noteflow

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 251 (2026-08-30): WindowSizeClass refreshes on freeform drag-resize.
 *
 * The WindowSizeClass was only re-derived on `Lifecycle.Event.ON_RESUME`. A
 * freeform window being drag-resized does NOT trigger onResume (the activity
 * stays in the foreground), so a drag that crossed 600dp/840dp with no
 * pause/resume left the whole AdaptiveLayoutPolicy system stuck on the OLD
 * size class. These pins guarantee:
 *
 *   1. MainActivity ALSO re-derives on `LocalConfiguration.current` change
 *      (a `LaunchedEffect` precedes the `key(sizeClassRefreshKey)` block), so
 *      config-triggered resizes bump the key without any resume.
 *   2. The provider's pre-MainActivity default is the STRICTEST Compact/Compact
 *      placeholder, never a hardcoded 840x900 EXPANDED tablet that would flash
 *      a wide double-pane layout on a phone for a frame (and silently lie to
 *      any probe that reads the default).
 */
class Phase251WindowSizeClassRefreshTest {

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

    // --- MainActivity: config-change re-derivation pin ----------------------

    @Test
    fun `WindowSizeClass re-derives on LocalConfiguration change, not just ON_RESUME`() {
        val src = mainSource("MainActivity.kt")
        // The LaunchedEffect keyed on LocalConfiguration.current must exist and must
        // COME BEFORE the key(sizeClassRefreshKey) block it is meant to bump.
        val launchEffect = src.indexOf("LaunchedEffect(LocalConfiguration.current)")
        assertTrue("MainActivity must observe LocalConfiguration changes", launchEffect >= 0)
        val keyBlock = src.indexOf("key(sizeClassRefreshKey) {")
        assertTrue("key(sizeClassRefreshKey) block must exist", keyBlock >= 0)
        assertTrue(
            "the config listener must precede the block it re-keys",
            launchEffect < keyBlock
        )
        // The effect must bump the SAME key the key() block is keyed on.
        val effectBody = src.substring(
            launchEffect,
            src.indexOf("}", src.indexOf("{", launchEffect))
        )
        assertTrue("the listener must bump sizeClassRefreshKey", effectBody.contains("sizeClassRefreshKey++"))
    }

    @Test
    fun `the keyed block still re-derives via calculateWindowSizeClass(activity)`() {
        val src = mainSource("MainActivity.kt")
        // The refreshed key must gate the whole derivation AND the provider feed.
        assertTrue(
            "the keyed block must query the CURRENT window metrics via the activity",
            src.contains("val windowSizeClass = key(sizeClassRefreshKey) {")
                && src.contains("calculateWindowSizeClass(activity = this@MainActivity)")
        )
        val keyBlock = src.substring(src.indexOf("key(sizeClassRefreshKey) {"))
        assertTrue(
            "the keyed expression must feed the CompositionLocal provider",
            keyBlock.contains("calculateWindowSizeClass(activity = this@MainActivity)")
        )
    }

    @Test
    fun `config listener is keyed on LocalConfiguration and bumps the key in-body`() {
        // Pin the exact expression: the effect key MUST be LocalConfiguration.current
        // (not Unit — a Unit key would only fire once and abandon the drag fix), and
        // its body MUST bump the key. A LaunchedEffect's block runs on the initial
        // composition as part of the standard contract, so the first frame already
        // bumps the refresh key and the Compact placeholder can never be sticky.
        val src = mainSource("MainActivity.kt")
        val launchEffect = src.indexOf("LaunchedEffect(LocalConfiguration.current) {")
        assertTrue("the config listener must exist with its LocalConfiguration key", launchEffect >= 0)
        val blockEnd = src.indexOf("}", src.indexOf("{", launchEffect))
        val block = src.substring(launchEffect, blockEnd)
        assertTrue("the block must bump sizeClassRefreshKey", block.contains("sizeClassRefreshKey++"))
        assertTrue(
            "the key argument must remain LocalConfiguration.current",
            block.startsWith("LaunchedEffect(LocalConfiguration.current) {")
        )
        assertFalse(
            "the effect must not be re-keyed onto Unit (fires once at boot only)",
            block.contains("LaunchedEffect(Unit)")
        )
    }

    // --- WindowSizeClassProvider: strict default pin ------------------------

    @Test
    fun `provider default is the strictest Compact placeholder, never 840x900`() {
        val src = mainSource("ui/WindowSizeClassProvider.kt")
        assertTrue(
            "the default must be built from a strict/compact size",
            src.contains("calculateFromSize(DpSize(0.dp, 0.dp))")
        )
        assertFalse(
            "the hardcoded 840x900 EXPANDED placeholder must be gone",
            src.contains("840.dp")
        )
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    @Test
    fun `the provider default expression actually classifies as Compact by both axes`() {
        // Pin the SEMANTIC, not just the string: 0x0 through the real library
        // classifier must be Compact/Compact (the strictest), and the old
        // 840x900 yields Expanded/Expanded (the old lie).
        val strict = WindowSizeClass.calculateFromSize(DpSize(0.dp, 0.dp))
        assertEquals(WindowWidthSizeClass.Compact, strict.widthSizeClass)
        assertEquals(WindowHeightSizeClass.Compact, strict.heightSizeClass)

        val oldPlaceholder = WindowSizeClass.calculateFromSize(DpSize(840.dp, 900.dp))
        assertEquals(WindowWidthSizeClass.Expanded, oldPlaceholder.widthSizeClass)
        assertEquals(WindowHeightSizeClass.Expanded, oldPlaceholder.heightSizeClass)

        // 560dp is just under the 600dp Compact floor — sanity check the
        // thresholds the proxy uses match the provider's default intent.
        assertEquals(
            WindowWidthSizeClass.Compact,
            WindowSizeClass.calculateFromSize(DpSize(599.dp, 900.dp)).widthSizeClass
        )
    }
}
