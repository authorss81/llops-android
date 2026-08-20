package com.authorss81.noteflow

import com.authorss81.noteflow.services.InkCardPaperPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 187 — pure-JVM policy for the gallery ink-note paper texture.
 *
 * Verifies the honest label, the bounded dot-grid geometry (phase-188 draw
 * budget: the draw loop in GalleryView must stay ≤ 12×8 = 96 dots on ANY card
 * size — no unbounded `rows = height/spacing` loop) and the pattern constants,
 * failsafe behaviour for degenerate inputs.
 */
class InkCardPaperPolicyTest {

    // --- honest label ---

    @Test
    fun `label is honest - never claims OCR text exists`() {
        assertEquals("Handwritten note", InkCardPaperPolicy.HANDWRITTEN_LABEL)
        assertTrue(
            "the label must not pretend the page has readable text",
            !InkCardPaperPolicy.HANDWRITTEN_LABEL.contains("text", ignoreCase = true)
        )
    }

    // --- ink-card classification ---

    @Test
    fun `null import type is an ink canvas page`() {
        assertTrue(InkCardPaperPolicy.isInkCanvasPage(null))
    }

    @Test
    fun `pdf image and text pages are not ink canvas pages`() {
        assertTrue(!InkCardPaperPolicy.isInkCanvasPage("pdf"))
        assertTrue(!InkCardPaperPolicy.isInkCanvasPage("image"))
        assertTrue(!InkCardPaperPolicy.isInkCanvasPage("text"))
    }

    @Test
    fun `anything else is treated as ink canvas`() {
        assertTrue(InkCardPaperPolicy.isInkCanvasPage("ink"))
        assertTrue(InkCardPaperPolicy.isInkCanvasPage("canvas"))
        assertTrue(InkCardPaperPolicy.isInkCanvasPage(""))
        assertTrue(InkCardPaperPolicy.isInkCanvasPage("unknown"))
    }

    // --- bounded grid geometry ---

    @Test
    fun `typical phone card stays under the column cap`() {
        // 168dp cell at 3x density = 504px; 22dp pitch at 3x = 66px.
        assertEquals(8, InkCardPaperPolicy.gridColumns(504f, 66f))
    }

    @Test
    fun `huge tablet card columns are capped`() {
        val cols = InkCardPaperPolicy.gridColumns(4000f, 66f)
        assertEquals(InkCardPaperPolicy.MAX_GRID_COLUMNS, cols)
        assertTrue(cols <= InkCardPaperPolicy.MAX_GRID_COLUMNS)
    }

    @Test
    fun `huge tablet card rows are capped`() {
        val rows = InkCardPaperPolicy.gridRows(4000f, 66f)
        assertEquals(InkCardPaperPolicy.MAX_GRID_ROWS, rows)
        assertTrue(rows <= InkCardPaperPolicy.MAX_GRID_ROWS)
    }

    @Test
    fun `dot budget never exceeds MAX_DOT_COUNT on any card size`() {
        val sizes = listOf(
            100f to 100f,
            504f to 800f,
            2000f to 2000f,
            20000f to 20000f,
            1f to 1f
        )
        for ((w, h) in sizes) {
            val dots = InkCardPaperPolicy.totalDots(w, h, 66f)
            assertTrue("$w x $h card must respect the dot budget, got $dots", dots <= InkCardPaperPolicy.MAX_DOT_COUNT)
        }
    }

    @Test
    fun `degenerate sizes fail safe and stay bounded`() {
        assertEquals(1, InkCardPaperPolicy.gridColumns(0f, 66f))
        assertEquals(1, InkCardPaperPolicy.gridRows(-5f, 66f))
        assertEquals(1, InkCardPaperPolicy.gridColumns(Float.NaN, 66f))
        // non-positive pitch falls back to a 1px pitch; the coerce caps still
        // bound the result instead of letting the division blow up.
        assertTrue(InkCardPaperPolicy.gridRows(100f, 0f) <= InkCardPaperPolicy.MAX_GRID_ROWS)
        assertTrue(InkCardPaperPolicy.totalDots(504f, 504f, 0f) <= InkCardPaperPolicy.MAX_DOT_COUNT)
    }

    @Test
    fun `grid starts at one column and one row for tiny cards`() {
        assertEquals(1, InkCardPaperPolicy.gridColumns(1f, 66f))
        assertEquals(1, InkCardPaperPolicy.gridRows(1f, 66f))
        assertEquals(1, InkCardPaperPolicy.totalDots(1f, 1f, 66f))
    }

    @Test
    fun `alpha and pattern constants match the paper spec`() {
        assertEquals(0.7f, InkCardPaperPolicy.PAPER_BACKGROUND_ALPHA)
        assertEquals(0.3f, InkCardPaperPolicy.GRID_ALPHA)
        assertEquals(22f, InkCardPaperPolicy.GRID_SPACING_DP)
        assertEquals(1.5f, InkCardPaperPolicy.DOT_RADIUS_DP)
        assertEquals(96, InkCardPaperPolicy.MAX_DOT_COUNT)
    }
}