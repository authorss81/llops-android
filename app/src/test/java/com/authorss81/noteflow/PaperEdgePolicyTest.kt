package com.authorss81.noteflow

import com.authorss81.noteflow.services.PaperEdgePolicy
import com.authorss81.noteflow.services.PaperEdgePolicy.PaperEdge
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 227 — deckled paper edge decision table: persistence keys, enum round
 * trips, the appeared-wave math and the DETERMINISTIC node stream shared by the
 * on-canvas card and the exporters. Pure JVM.
 */
class PaperEdgePolicyTest {

    @Test
    fun `persisted default preserves the legacy rounded card`() {
        assertEquals("rounded", PaperEdgePolicy.DEFAULT_KEY)
        assertEquals(PaperEdge.ROUNDED, PaperEdgePolicy.fromKey(null))
        assertEquals(PaperEdge.ROUNDED, PaperEdgePolicy.fromKey("rounded"))
    }

    @Test
    fun `corrupt or unknown keys sanitize to the default`() {
        for (bad in listOf(null, "", "square", "WAVY", " deckled ", "rounded;drop", "1", "Şattach")) {
            assertEquals("key '$bad' must sanitize", "rounded", PaperEdgePolicy.sanitizeKey(bad))
            assertEquals(PaperEdge.ROUNDED, PaperEdgePolicy.fromKey(bad))
        }
    }

    @Test
    fun `valid keys round trip through persistence`() {
        for (key in listOf(PaperEdgePolicy.KEY_RECT, PaperEdgePolicy.KEY_ROUNDED, PaperEdgePolicy.KEY_DECKLED)) {
            assertEquals("$key sanitizes to itself", key, PaperEdgePolicy.sanitizeKey(key))
            val edge = PaperEdgePolicy.fromKey(key)
            assertEquals("persistenceKey round-trips $key", key, PaperEdgePolicy.persistenceKey(edge))
        }
    }

    @Test
    fun `the wave is bounded to the declared peak deviation at every sample`() {
        val peak = PaperEdgePolicy.peakDeviationPx()
        assertEquals(3f, peak, 1e-6f)
        for (seed in listOf(0, 1, 0x1D6E81)) {
            var x = -1000f
            while (x <= 4000f) {
                val v = PaperEdgePolicy.wavyOffsetAt(x, seed)
                assertTrue("deviation out of [-$peak, +$peak]: $v", abs(v) <= peak + 1e-4f)
                x += 7.3f
            }
        }
    }

    @Test
    fun `the wave is deterministic and family-dependent`() {
        assertEquals(
            PaperEdgePolicy.wavyOffsetAt(1234.5f, 0),
            PaperEdgePolicy.wavyOffsetAt(1234.5f, 0),
            0f
        )
        // light vs dark stock: DIFFERENT seed -> the sheet families differ, yet
        // the same stock is stable across calls.
        assertNotEquals(
            PaperEdgePolicy.seedFor(isDarkPaper = false),
            PaperEdgePolicy.seedFor(isDarkPaper = true)
        )
        assertEquals(
            PaperEdgePolicy.seedFor(false),
            PaperEdgePolicy.seedFor(false)
        )
    }

    @Test
    fun `non finite samples fail safe to zero deviation`() {
        assertEquals(0f, PaperEdgePolicy.wavyOffsetAt(Float.NaN, 0), 0f)
        assertEquals(0f, PaperEdgePolicy.wavyOffsetAt(Float.POSITIVE_INFINITY, 1), 0f)
        assertEquals(0f, PaperEdgePolicy.wavyOffsetAt(Float.NEGATIVE_INFINITY, 2), 0f)
    }

    @Test
    fun `physical amplitude scales with density and degrades to nominal`() {
        assertEquals(PaperEdgePolicy.peakDeviationPx(), PaperEdgePolicy.amplitudePx(1f), 1e-6f)
        assertEquals(PaperEdgePolicy.peakDeviationPx() * 3f, PaperEdgePolicy.amplitudePx(3f), 1e-4f)
        // degenerate densities fall back to the nominal dp@1 scale.
        assertEquals(PaperEdgePolicy.peakDeviationPx(), PaperEdgePolicy.amplitudePx(0f), 1e-6f)
        assertEquals(PaperEdgePolicy.peakDeviationPx(), PaperEdgePolicy.amplitudePx(Float.NaN), 1e-6f)
    }

    @Test
    fun `deckleNodes returns a bounded closed clockwise sheet`() {
        val width = 1080f
        val height = 1528f
        val amp = PaperEdgePolicy.amplitudePx(3f)
        val nodes = PaperEdgePolicy.deckleNodes(0f, 0f, width, height, amp, PaperEdgePolicy.seedFor(false))
        assertTrue("node count must stay within the perceptual budget", nodes.size in 16..100)
        for ((nx, ny) in nodes) {
            assertTrue("node x out of bounds: $nx", nx >= -amp - 1e-3f && nx <= width + amp + 1e-3f)
            assertTrue("node y out of bounds: $ny", ny >= -amp - 1e-3f && ny <= height + amp + 1e-3f)
            assertTrue("node must be finite", nx.isFinite() && ny.isFinite())
        }
        // corners stay sharp: every deckle corner fades to zero deviation.
        val corners = listOf(
            Pair(0f, 0f), Pair(width, 0f), Pair(width, height), Pair(0f, height)
        )
        for (corner in corners) {
            assertTrue(
                "corner $corner must be present with zero deviation",
                nodes.any { abs(it.first - corner.first) < 1e-3f && abs(it.second - corner.second) < 1e-3f }
            )
        }
        // deterministic: the SAME sheet is generated every time.
        assertEquals(
            nodes,
            PaperEdgePolicy.deckleNodes(0f, 0f, width, height, amp, PaperEdgePolicy.seedFor(false))
        )
    }

    @Test
    fun `deckle fades deviations near corners but ripples mid-edge`() {
        val amp = PaperEdgePolicy.amplitudePx(2f)
        val nodes = PaperEdgePolicy.deckleNodes(0f, 0f, 1080f, 1528f, amp, 99)
        var peakDev = 0f
        for ((nx, ny) in nodes) {
            // top edge nodes: (x, y + d), where y = 0
            if (ny < 20f) peakDev = maxOf(peakDev, Math.abs(ny).toFloat())
        }
        assertTrue("the mid-edge wave must visibly exceed the corner fade", peakDev > amp * 0.3f)
    }

    @Test
    fun `smoothed midpoints polygon matches the node count`() {
        val amp = PaperEdgePolicy.amplitudePx(3f)
        val nodes = PaperEdgePolicy.deckleNodes(10f, 4f, 720f, 960f, amp, 7)
        val mid = PaperEdgePolicy.smoothedDeckleMidpoints(nodes)
        assertEquals("one midpoint per node keeps the closed silhouette", nodes.size, mid.size)
        // the first midpoint is the seam between the last and first node.
        val expected = Pair(
            (nodes.last().first + nodes.first().first) / 2f,
            (nodes.last().second + nodes.first().second) / 2f
        )
        assertEquals(expected.first, mid.first().first, 1e-4f)
        assertEquals(expected.second, mid.first().second, 1e-4f)
    }
}