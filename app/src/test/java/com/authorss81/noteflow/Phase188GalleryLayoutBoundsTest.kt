package com.authorss81.noteflow

import com.authorss81.noteflow.services.GalleryCardLayoutPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 188 — gallery card layout-bounds + dark-theme border policy.
 *
 * Pure-JVM pin of the large-font guarantee (risk #2) and the dark-theme border
 * decision (risk #3):
 *
 * - [GalleryCardLayoutPolicy.measuredCardHeightDp] = `max(content, floor)`, an
 *   un-capped content-driven height, so the footer NEVER sits outside the card.
 * - [GalleryCardLayoutPolicy.footerAlwaysFits] is the decision exposed to the
 *   regression guard: every valid content height fits at every font scale.
 * - The border width/alpha are policy constants (no inline literals).
 * - Source-vs-policy agreement pins keep the composable's literals in sync with
 *   the policy line budgets (phase-184 requires the literal `maxLines = 3` in
 *   `GalleryView.kt`, so the composable spells the budget and this test proves
 *   the two stay equal).
 */
class Phase188GalleryLayoutBoundsTest {

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

    // ---- Pure-JVM layout-bounds math (risk #2) ----

    @Test
    fun `card height is content-driven with the floor so taller content always fits`() {
        val fontScale = 1.3f
        val floor = GalleryCardLayoutPolicy.minCardHeightDp(fontScale)
        // Content above the floor: card = content (never capped).
        assertEquals(350f, GalleryCardLayoutPolicy.measuredCardHeightDp(350f, fontScale), 0f)
        // Content below the floor: card = floor (footer still inside).
        assertEquals(floor, GalleryCardLayoutPolicy.measuredCardHeightDp(120f, fontScale), 0f)
        // Exactly at the floor: card = floor.
        assertEquals(floor, GalleryCardLayoutPolicy.measuredCardHeightDp(floor, fontScale), 0f)
    }

    @Test
    fun `large-font scale never clips a footer because the card grows with content`() {
        // The exact review scenario: 1.3x and 1.5x font scales with a modest body.
        for (scale in listOf(1f, 1.3f, 1.5f, 2f, 3f)) {
            for (body in listOf(140f, 200f, 260f, 420f, 1000f)) {
                assertTrue(
                    "scale=$scale body=${body}dp must fit",
                    GalleryCardLayoutPolicy.footerAlwaysFits(body, scale)
                )
                // The measured card is never smaller than the content itself.
                assertTrue(
                    GalleryCardLayoutPolicy.measuredCardHeightDp(body, scale) >= body
                )
            }
        }
    }

    @Test
    fun `footer guarantee fails safe on garbage content heights`() {
        assertFalse(GalleryCardLayoutPolicy.footerAlwaysFits(Float.NaN, 1f))
        assertFalse(GalleryCardLayoutPolicy.footerAlwaysFits(-5f, 1f))
        // Negative content also fails safe at the measured-card level.
        assertEquals(
            GalleryCardLayoutPolicy.minCardHeightDp(1f),
            GalleryCardLayoutPolicy.measuredCardHeightDp(-5f, 1f),
            0f
        )
    }

    @Test
    fun `line budgets are the pinned constants`() {
        assertEquals(2, GalleryCardLayoutPolicy.TITLE_MAX_LINES)
        assertEquals(3, GalleryCardLayoutPolicy.PREVIEW_MAX_LINES)
        assertEquals(1, GalleryCardLayoutPolicy.TAG_ROW_MAX_LINES)
        assertEquals(1, GalleryCardLayoutPolicy.FOOTER_DATE_MAX_LINES)
    }

    // ---- Dark-theme border decision (risk #3) ----

    @Test
    fun `dark-theme border decision is the 1dp outlineVariant border at 0_35 alpha`() {
        assertEquals(1f, GalleryCardLayoutPolicy.GALLERY_CARD_BORDER_WIDTH_DP, 0f)
        assertEquals(0.35f, GalleryCardLayoutPolicy.GALLERY_CARD_BORDER_ALPHA, 0f)
    }

    // ---- Source-vs-policy agreement ----

    @Test
    fun `composable preview and footer keep the policy line budgets`() {
        val src = mainSource("ui/components/GalleryView.kt")
        // Preview stays 3-line capped (literal required by phase-184 pin).
        assertTrue(src.contains("maxLines = 3"))
        assertEquals(3, GalleryCardLayoutPolicy.PREVIEW_MAX_LINES)
        // Footer date stays single-line.
        assertTrue(src.contains("maxLines = 1,"))
        assertEquals(1, GalleryCardLayoutPolicy.FOOTER_DATE_MAX_LINES)
    }

    @Test
    fun `composable seats the footer slack with a weight fill-false preview`() {
        val src = mainSource("ui/components/GalleryView.kt")
        // The prompt mechanism: Column(weight(1f, fill=false)) + heightIn(min).
        val first = src.indexOf("weight(1f, fill = false)")
        val second = src.indexOf("weight(1f, fill = false)", src.indexOf("weight(1f, fill = false)") + 1)
        assertTrue(
            "both preview paths (text + placeholder) must carry the slack seat",
            first >= 0 && second > first
        )
        assertTrue("the body column must share the min-height floor", src.contains(".heightIn(min = minCardHeight)"))
        assertTrue(
            "the floor input remains the font scale (accessibility rule)",
            src.contains("GalleryCardLayoutPolicy.minCardHeightDp(LocalDensity.current.fontScale)")
        )
    }

    @Test
    fun `composable border is the policy decision not an inline literal`() {
        val src = mainSource("ui/components/GalleryView.kt")
        assertTrue("card must carry a BorderStroke", src.contains("border = BorderStroke("))
        assertTrue(
            "width must come from the policy constant",
            src.contains("GalleryCardLayoutPolicy.GALLERY_CARD_BORDER_WIDTH_DP.dp")
        )
        assertTrue(
            "alpha must come from the policy constant",
            src.contains("outlineVariant.copy(alpha = GalleryCardLayoutPolicy.GALLERY_CARD_BORDER_ALPHA)")
        )
        // No inline 0.35f border literal anywhere in the gallery card.
        assertFalse("no inline border alpha literal", src.contains("outlineVariant.copy(alpha = 0.35f)"))
        // No aspect-ratio backsliding (phase-184 pin preserved).
        assertFalse(src.contains(".aspectRatio("))
    }
}