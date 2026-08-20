package com.authorss81.noteflow

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 183 review-fix regression pins (2026-08-20).
 *
 * Review finding 1: the step-2 commit added `hyphens = Hyphens.None` directly to
 * the material3 `Text(...)` call. The resolved M3 `Text` (Compose UI 1.7.6, BOM
 * 2024.12.01) has NO `hyphens` parameter — it is a `TextStyle` property — so that
 * intermediate build could not compile. The pins below force `Hyphens.None` to be
 * applied through the style, never as a direct `Text(...)` argument.
 */
class Phase183GalleryTypographyTest {

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

    @Test
    fun `forum gallery title applies hyphens via the style, never as a direct Text param`() {
        val src = mainSource("ui/components/GalleryView.kt")
        assertTrue(
            "Hyphens.None must be applied through the TextStyle style",
            src.contains("titleSmall.copy(")
        )
        assertTrue(
            "Hyphens.None must be declared on the style copy",
            src.contains("hyphens = Hyphens.None")
        )
        // The style.copy block must carry both the hyphens and the lineHeight so the
        // typography is one source of truth.
        val copyBlock = src.substring(
            src.indexOf("titleSmall.copy("),
            src.indexOf("titleSmall.copy(") + 160
        )
        assertTrue("copy must carry hyphens", copyBlock.contains("hyphens = Hyphens.None"))
        assertTrue("copy must carry lineHeight", copyBlock.contains("lineHeight = 18.sp"))
        assertFalse(
            "a direct Text(hyphens = ...) arg cannot compile against M3 1.3.1 — forbid it",
            src.contains("overflow = TextOverflow.Ellipsis,\n                        hyphens")
        )
    }

    @Test
    fun `gallery title renders the display policy result`() {
        val src = mainSource("ui/components/GalleryView.kt")
        assertTrue(
            "gallery title must be routed through GalleryTitleDisplayPolicy",
            src.contains("GalleryTitleDisplayPolicy.displayTitle(page.title)")
        )
    }
}