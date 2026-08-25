package com.authorss81.noteflow.services.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 210 — neighborhood focus math. The Knowledge Graph's Focus button keeps
 * only the selected node + N-hop neighborhood visible and dims everything else;
 * this suite pins the BFS, the hop sanitizer, the edge scoping reuse of
 * [GraphSubgraphFilter.edgesWithin] and the defensive frontier cap.
 */
class GraphNeighborhoodFocusPolicyTest {

    private fun e(a: String, b: String) = GraphEdgeRef(a, b)

    // ---------- sanitizeHops ----------

    @Test
    fun `hop depth clamps into 1 dotdot 3`() {
        assertEquals(1, GraphNeighborhoodFocusPolicy.sanitizeHops(-5))
        assertEquals(1, GraphNeighborhoodFocusPolicy.sanitizeHops(0))
        assertEquals(1, GraphNeighborhoodFocusPolicy.sanitizeHops(1))
        assertEquals(2, GraphNeighborhoodFocusPolicy.sanitizeHops(2))
        assertEquals(3, GraphNeighborhoodFocusPolicy.sanitizeHops(3))
        assertEquals(3, GraphNeighborhoodFocusPolicy.sanitizeHops(4))
        assertEquals(3, GraphNeighborhoodFocusPolicy.sanitizeHops(99))
    }

    @Test
    fun `default hop depth is 1`() {
        assertEquals(1, GraphNeighborhoodFocusPolicy.DEFAULT_HOPS)
    }

    // ---------- gating ----------

    @Test
    fun `disabled focus yields null`() {
        assertNull(
            GraphNeighborhoodFocusPolicy.focus("a", enabled = false, edges = listOf(e("a", "b")), hops = 1)
        )
    }

    @Test
    fun `enabled focus without a selection yields null`() {
        assertNull(
            GraphNeighborhoodFocusPolicy.focus(null, enabled = true, edges = listOf(e("a", "b")), hops = 1)
        )
        assertNull(
            GraphNeighborhoodFocusPolicy.focus("", enabled = true, edges = listOf(e("a", "b")), hops = 1)
        )
    }

    @Test
    fun `isolated node focuses to itself alone`() {
        val result = GraphNeighborhoodFocusPolicy.focus(
            "solo", enabled = true, edges = listOf(e("x", "y")), hops = 2
        )!!
        assertEquals(setOf("solo"), result.focusedIds)
        assertTrue(result.focusedEdges.isEmpty())
    }

    // ---------- BFS neighborhoods ----------

    @Test
    fun `one hop keeps direct neighbors only`() {
        val edges = listOf(e("a", "b"), e("b", "c"), e("c", "d"))
        val result = GraphNeighborhoodFocusPolicy.focus("a", true, edges, hops = 1)!!
        assertEquals(setOf("a", "b"), result.focusedIds)
    }

    @Test
    fun `two and three hops expand along the chain`() {
        val edges = listOf(e("a", "b"), e("b", "c"), e("c", "d"))
        assertEquals(setOf("a", "b", "c"),
            GraphNeighborhoodFocusPolicy.focus("a", true, edges, hops = 2)!!.focusedIds)
        assertEquals(setOf("a", "b", "c", "d"),
            GraphNeighborhoodFocusPolicy.focus("a", true, edges, hops = 3)!!.focusedIds)
    }

    @Test
    fun `edges are treated as undirected`() {
        val edges = listOf(e("b", "a")) // reversed direction still connects a-b
        assertEquals(setOf("a", "b"),
            GraphNeighborhoodFocusPolicy.focus("a", true, edges, hops = 1)!!.focusedIds)
    }

    @Test
    fun `unreachable components stay out of the focus set`() {
        val edges = listOf(e("a", "b"), e("x", "y"), e("y", "z"))
        val result = GraphNeighborhoodFocusPolicy.focus("a", true, edges, hops = 3)!!
        assertEquals(setOf("a", "b"), result.focusedIds)
    }

    @Test
    fun `self loops never join nodes`() {
        val edges = listOf(e("a", "a"), e("a", "b"))
        assertEquals(setOf("a", "b"),
            GraphNeighborhoodFocusPolicy.focus("a", true, edges, hops = 1)!!.focusedIds)
    }

    // ---------- edge scoping ----------

    @Test
    fun `focused edges keep only pairs fully inside the neighborhood`() {
        val edges = listOf(e("a", "b"), e("b", "c"), e("c", "d"))
        val oneHop = GraphNeighborhoodFocusPolicy.focus("a", true, edges, hops = 1)!!
        assertEquals(listOf(e("a", "b")), oneHop.focusedEdges)
        val twoHop = GraphNeighborhoodFocusPolicy.focus("a", true, edges, hops = 2)!!
        assertEquals(listOf(e("a", "b"), e("b", "c")), twoHop.focusedEdges)
    }

    @Test
    fun `duplicate edges dedupe exactly like the notebook subgraph`() {
        // edgesWithin dedupes EXACT refs (GraphEdgeRef is directed): a reversed
        // duplicate survives, an identical triple collapses to one.
        val edges = listOf(e("a", "b"), e("b", "a"), e("a", "b"))
        val oneHop = GraphNeighborhoodFocusPolicy.focus("a", true, edges, hops = 1)!!
        assertEquals(2, oneHop.focusedEdges.size)
        assertEquals(edges.distinct(), oneHop.focusedEdges)
    }

    @Test
    fun `edge scoping reuses GraphSubgraphFilter edgesWithin`() {
        // Parity proof: the focus pipeline must return exactly what the shared
        // notebook-subgraph rule would return for the same kept-id set.
        val edges = listOf(e("a", "b"), e("b", "c"), e("c", "d"), e("a", "d"))
        val focus = GraphNeighborhoodFocusPolicy.focus("b", true, edges, hops = 2)!!
        val shared = GraphSubgraphFilter.edgesWithin(focus.focusedIds, edges)
        assertEquals(shared, focus.focusedEdges)
    }

    // ---------- defensive frontier cap ----------

    @Test
    fun `a pathological hub cannot blow past the focused-node cap`() {
        val edges = (0 until 500).map { e("hub", "leaf-%03d".format(it)) }
        val result = GraphNeighborhoodFocusPolicy.focus("hub", true, edges, hops = 1)!!
        assertTrue(result.focusedIds.size <= GraphNeighborhoodFocusPolicy.MAX_FOCUSED_NODES)
        assertEquals(GraphNeighborhoodFocusPolicy.MAX_FOCUSED_NODES, result.focusedIds.size)
        assertTrue("the selected node itself always stays focused", "hub" in result.focusedIds)
        // Deterministic trim: the lexicographically smallest leaves survive.
        assertTrue("leaf-000" in result.focusedIds)
        assertFalse("leaf-499" in result.focusedIds)
    }

    // ---------- copy ----------

    @Test
    fun `focus notice names the focused count non-alarmingly`() {
        val notice = GraphNeighborhoodFocusPolicy.focusNotice(7)
        assertTrue(notice.contains("7"))
        assertFalse(notice.contains("!"))
    }
}
