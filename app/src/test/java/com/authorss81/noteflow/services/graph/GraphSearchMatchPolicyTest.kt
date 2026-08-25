package com.authorss81.noteflow.services.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 210 — graph search navigation math: deterministic ranked matching for
 * the Enter-cycle, and the pan translation that centers a settled node at the
 * current zoom (`screen = center + (world − center)·zoom + pan`, solved for
 * `screen(world) = center`).
 */
class GraphSearchMatchPolicyTest {

    // ---------- ranking ----------

    @Test
    fun `prefix matches rank above word starts above plain contains`() {
        assertEquals(GraphSearchMatchPolicy.RANK_PREFIX,
            GraphSearchMatchPolicy.rankOf("Meeting notes", "mee"))
        assertEquals(GraphSearchMatchPolicy.RANK_WORD_START,
            GraphSearchMatchPolicy.rankOf("Weekly meeting", "meet"))
        assertEquals(GraphSearchMatchPolicy.RANK_CONTAINS,
            GraphSearchMatchPolicy.rankOf("Postmortemeeting", "meet"))
    }

    @Test
    fun `ranking is case-insensitive`() {
        assertEquals(GraphSearchMatchPolicy.RANK_PREFIX,
            GraphSearchMatchPolicy.rankOf("Zebra", "zeb"))
        assertEquals(GraphSearchMatchPolicy.RANK_WORD_START,
            GraphSearchMatchPolicy.rankOf("the Zebra", "zeb"))
    }

    @Test
    fun `non-matches and blank queries never rank`() {
        assertEquals(Int.MAX_VALUE, GraphSearchMatchPolicy.rankOf("Cat", "dog"))
        assertEquals(Int.MAX_VALUE, GraphSearchMatchPolicy.rankOf("Cat", ""))
        assertEquals(Int.MAX_VALUE, GraphSearchMatchPolicy.rankOf("", "cat"))
    }

    // ---------- ordered matching ----------

    @Test
    fun `orderedMatches puts prefix hits first and drops non-matches`() {
        val entries = listOf(
            "a2" to "Weekly meeting",
            "b3" to "postmortemeeting",
            "c1" to "Meeting notes",
            "d4" to "hello world"
        )
        assertEquals(listOf("c1", "a2", "b3"),
            GraphSearchMatchPolicy.orderedMatches("meet", entries))
    }

    @Test
    fun `equal-tier matches tie-break by id`() {
        val entries = listOf(
            "b" to "zebra",
            "a" to "Zebra crossing"
        )
        // Both are prefix matches of "zebra" — id order decides.
        assertEquals(listOf("a", "b"),
            GraphSearchMatchPolicy.orderedMatches("zebra", entries))
    }

    @Test
    fun `blank query matches nothing - never everything`() {
        val entries = listOf("a" to "Anything")
        assertTrue(GraphSearchMatchPolicy.orderedMatches("", entries).isEmpty())
        assertTrue(GraphSearchMatchPolicy.orderedMatches("   ", entries).isEmpty())
    }

    @Test
    fun `ties break by id so traversal order is stable`() {
        val entries = listOf(
            "z9" to "alpha note",
            "a1" to "alpha note",
            "m5" to "alpha note"
        )
        assertEquals(listOf("a1", "m5", "z9"),
            GraphSearchMatchPolicy.orderedMatches("alpha", entries))
    }

    @Test
    fun `query whitespace is trimmed before matching`() {
        val entries = listOf("a" to "target")
        assertEquals(listOf("a"), GraphSearchMatchPolicy.orderedMatches("  target ", entries))
    }

    // ---------- Enter cycling ----------

    @Test
    fun `nextIndex cycles and wraps`() {
        assertEquals(1, GraphSearchMatchPolicy.nextIndex(0, 3))
        assertEquals(2, GraphSearchMatchPolicy.nextIndex(1, 3))
        assertEquals(0, GraphSearchMatchPolicy.nextIndex(2, 3))
    }

    @Test
    fun `nextIndex is safe on empty lists and negative indices`() {
        assertEquals(0, GraphSearchMatchPolicy.nextIndex(0, 0))
        assertEquals(0, GraphSearchMatchPolicy.nextIndex(5, 0))
        assertEquals(1, GraphSearchMatchPolicy.nextIndex(-1, 4))
    }

    // ---------- pan targets ----------

    @Test
    fun `a node already at the viewport center needs no pan`() {
        val pan = GraphSearchMatchPolicy.panToCenter(
            worldX = 400f, worldY = 300f, viewportW = 800f, viewportH = 600f, zoom = 2.5f
        )
        assertEquals(0f, pan.panX, 0.001f)
        assertEquals(0f, pan.panY, 0.001f)
    }

    @Test
    fun `pan centers an off-center node at the current zoom`() {
        val pan = GraphSearchMatchPolicy.panToCenter(
            worldX = 100f, worldY = 50f, viewportW = 800f, viewportH = 600f, zoom = 2f
        )
        assertEquals(600f, pan.panX, 0.001f)
        assertEquals(500f, pan.panY, 0.001f)
        // Round-trip through the screen's own transform identity:
        // screen = center + (world − center)·zoom + pan must equal center.
        val screenX = 800f / 2f + (100f - 800f / 2f) * 2f + pan.panX
        val screenY = 600f / 2f + (50f - 600f / 2f) * 2f + pan.panY
        assertEquals(400f, screenX, 0.001f)
        assertEquals(300f, screenY, 0.001f)
    }

    @Test
    fun `zoom scales the required pan magnitude`() {
        val atOne = GraphSearchMatchPolicy.panToCenter(0f, 0f, 800f, 600f, zoom = 1f)
        val atFour = GraphSearchMatchPolicy.panToCenter(0f, 0f, 800f, 600f, zoom = 4f)
        assertEquals(atFour.panX, atOne.panX * 4f, 0.001f)
        assertEquals(atFour.panY, atOne.panY * 4f, 0.001f)
    }

    @Test
    fun `degenerate inputs fail safe to a zero pan`() {
        for (bad in sequenceOf(
            floatArrayOf(0f, 0f, 800f, 600f, 0f),      // zero zoom
            floatArrayOf(0f, 0f, 800f, 600f, -2f),     // negative zoom
            floatArrayOf(Float.NaN, 0f, 800f, 600f, 1f),
            floatArrayOf(0f, Float.POSITIVE_INFINITY, 800f, 600f, 1f),
            floatArrayOf(0f, 0f, Float.NaN, 600f, 1f),
            floatArrayOf(0f, 0f, -10f, 600f, 1f)       // degenerate viewport
        )) {
            val pan = GraphSearchMatchPolicy.panToCenter(bad[0], bad[1], bad[2], bad[3], bad[4])
            assertEquals(0f, pan.panX, 0f)
            assertEquals(0f, pan.panY, 0f)
        }
    }
}
