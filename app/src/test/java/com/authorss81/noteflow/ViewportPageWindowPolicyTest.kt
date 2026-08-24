package com.authorss81.noteflow

import com.authorss81.noteflow.services.ViewportPageWindowPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 198 (PERF 2.5): the closed-form O(visiblePages) page window that
 * replaced the iterate-and-skip culling loop in AnnotationCanvas' paginated
 * branch.
 *
 * Parity contract: the policy must select EXACTLY the pages the pre-198
 * per-page predicate (`pageBottom < top || pageTop > bottom -> skip`)
 * selected — boundary-touching pages count as visible, pure-gap viewports
 * draw nothing, and windows above/below the document are EMPTY (never
 * clamped back onto page 0 / the last page).
 *
 * Fail-safe contract: non-finite or structurally degenerate inputs over-draw
 * (full range) rather than under-draw — a math edge case can never blank
 * user ink.
 */
class ViewportPageWindowPolicyTest {

    // Matches the canvas' portrait geometry: slab 1528px + 64px gap.
    private val stride = 1592f
    private val slab = 1528f

    @Test
    fun `first page fills the viewport at rest`() {
        assertEquals(0..0, ViewportPageWindowPolicy.visiblePageRange(0f, 1528f, stride, slab, 20))
    }

    @Test
    fun `window spanning two slabs and the gap selects three touching pages`() {
        // Viewport [1500, 3200]: page 0 bottom edge (1528) is inside,
        // page 1 [1592..3120] is inside, page 2 top edge (3184) is inside.
        assertEquals(0..2, ViewportPageWindowPolicy.visiblePageRange(1500f, 3200f, stride, slab, 20))
    }

    @Test
    fun `deep scroll touches only two pages of a long document`() {
        // 200-page doc, viewport deep in the tail: only pages 62-63 intersect.
        val range = ViewportPageWindowPolicy.visiblePageRange(100_000f, 101_000f, stride, slab, 200)
        assertEquals(62..63, range)
        assertTrue("window must be far smaller than the document", range.count() < 200)
    }

    @Test
    fun `boundary-touching viewport counts both flanking pages`() {
        // The gap band itself [1528, 1592]: page 0's bottom edge and page 1's
        // top edge both TOUCH it — the old strict-inequality skip drew both.
        assertEquals(0..1, ViewportPageWindowPolicy.visiblePageRange(1528f, 1592f, stride, slab, 20))
    }

    @Test
    fun `pure-gap viewport draws nothing`() {
        // Strictly inside the gap between pages 0 and 1.
        assertEquals(IntRange.EMPTY, ViewportPageWindowPolicy.visiblePageRange(1530f, 1590f, stride, slab, 20))
    }

    @Test
    fun `viewport entirely above page zero is empty - no clamp onto page zero`() {
        assertEquals(
            IntRange.EMPTY,
            ViewportPageWindowPolicy.visiblePageRange(-5000f, -4000f, stride, slab, 20)
        )
    }

    @Test
    fun `viewport entirely below the last page is empty`() {
        // 10 pages end at 9*1592+1528 = 15856.
        assertEquals(
            IntRange.EMPTY,
            ViewportPageWindowPolicy.visiblePageRange(16000f, 17000f, stride, slab, 10)
        )
    }

    @Test
    fun `zoomed-out whole-document viewport selects every page`() {
        val pageCount = 12
        val worldBottom = pageCount * stride
        val range = ViewportPageWindowPolicy.visiblePageRange(0f, worldBottom, stride, slab, pageCount)
        assertEquals(0, range.first)
        assertEquals(pageCount - 1, range.last)
    }

    @Test
    fun `single page document always resolves to page zero when touched`() {
        assertEquals(0..0, ViewportPageWindowPolicy.visiblePageRange(0f, 1528f, stride, slab, 1))
        assertEquals(0..0, ViewportPageWindowPolicy.visiblePageRange(100f, 1400f, stride, slab, 1))
    }

    @Test
    fun `non positive page count is empty`() {
        assertEquals(IntRange.EMPTY, ViewportPageWindowPolicy.visiblePageRange(0f, 1528f, stride, slab, 0))
        assertEquals(IntRange.EMPTY, ViewportPageWindowPolicy.visiblePageRange(0f, 1528f, stride, slab, -3))
    }

    @Test
    fun `degenerate math inputs fail safe to the full range`() {
        val full = 0..4
        assertEquals(full, ViewportPageWindowPolicy.visiblePageRange(Float.NaN, 1528f, stride, slab, 5))
        assertEquals(full, ViewportPageWindowPolicy.visiblePageRange(0f, Float.POSITIVE_INFINITY, stride, slab, 5))
        assertEquals(full, ViewportPageWindowPolicy.visiblePageRange(2000f, 100f, stride, slab, 5))
        assertEquals(full, ViewportPageWindowPolicy.visiblePageRange(0f, 1528f, 0f, slab, 5))
        assertEquals(full, ViewportPageWindowPolicy.visiblePageRange(0f, 1528f, -stride, slab, 5))
        assertEquals(full, ViewportPageWindowPolicy.visiblePageRange(0f, 1528f, stride, -1f, 5))
    }

    @Test
    fun `exhaustive sweep matches the pre-198 per-page skip predicate`() {
        // Brute-force parity check across many window positions: for every
        // candidate band, the closed form must equal the set produced by the
        // original per-page predicate.
        fun legacyVisible(top: Float, bottom: Float, pageCount: Int): List<Int> =
            (0 until pageCount).filter { page ->
                val pageTop = page * stride
                val pageBottom = pageTop + slab
                !(pageBottom < top || pageTop > bottom)
            }
        var checked = 0
        for (top in -3000..18000 step 211) {
            for (height in listOf(0f, 64f, 800f, 1528f, 1592f, 3200f)) {
                val bottom = top + height
                val expected = legacyVisible(top.toFloat(), bottom, 14)
                val actual = ViewportPageWindowPolicy
                    .visiblePageRange(top.toFloat(), bottom, stride, slab, 14)
                    .toList()
                assertEquals("band [$top, $bottom]", expected, actual)
                checked++
            }
        }
        assertTrue(checked > 500)
    }
}
