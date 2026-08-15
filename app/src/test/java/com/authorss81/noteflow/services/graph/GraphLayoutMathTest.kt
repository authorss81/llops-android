package com.authorss81.noteflow.services.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Phase 38: deterministic force-directed layout math — repulsion, spring,
 * collision bounding, cluster assignment and tier culling. All inputs are
 * explicitly seeded so the assertions are reproducible.
 */
class GraphLayoutMathTest {

    private fun dist(nodes: List<GraphVertex>, i: Int, j: Int): Float {
        val a = nodes[i]
        val b = nodes[j]
        val dx = b.x - a.x
        val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
    }

    // ---- forces ------------------------------------------------------------

    @Test
    fun `coulomb repulsion separates two overlapping nodes`() {
        val nodes = mutableListOf(
            GraphVertex(id = "a", x = 0f, y = 0f),
            GraphVertex(id = "b", x = 4f, y = 0f)
        )
        repeat(12) { GraphLayoutMath.step(nodes, emptyList()) }
        assertTrue(nodes[0].x < nodes[1].x)
        assertTrue("nodes should fly apart, d=${dist(nodes, 0, 1)}", dist(nodes, 0, 1) > 4f)
    }

    @Test
    fun `repulsion never produces non-finite velocities on coincident nodes`() {
        val nodes = mutableListOf(
            GraphVertex(id = "a", x = 0f, y = 0f),
            GraphVertex(id = "b", x = 0f, y = 0f)
        )
        repeat(20) { GraphLayoutMath.step(nodes, emptyList()) }
        nodes.forEach { v ->
            assertTrue(v.x.isFinite())
            assertTrue(v.y.isFinite())
            assertTrue(v.vx.isFinite())
            assertTrue(v.vy.isFinite())
        }
    }

    @Test
    fun `spring pulls far-apart linked nodes toward rest length`() {
        val nodes = mutableListOf(
            GraphVertex(id = "a", x = -1000f, y = 0f),
            GraphVertex(id = "b", x = 1000f, y = 0f)
        )
        val edges = listOf(GraphEdgeRef("a", "b"))
        repeat(120) { GraphLayoutMath.step(nodes, edges) }
        val d = dist(nodes, 0, 1)
        assertTrue("edge should pull nodes together, d=$d", d < 700f)
    }

    // ---- collision bounding -------------------------------------------------

    @Test
    fun `collision resolution separates overlapping node pair`() {
        val nodes = mutableListOf(
            GraphVertex(id = "a", x = 0f, y = 0f, radius = 30f),
            GraphVertex(id = "b", x = 4f, y = 0f, radius = 30f)
        )
        GraphLayoutMath.resolveCollisionsAndBounds(nodes)
        val minDist = (30f + 30f) * 0.5f + GraphPhysicsConfig.COLLISION_PADDING
        assertTrue("overlap unresolved: d=${dist(nodes, 0, 1)}", dist(nodes, 0, 1) >= minDist - 1e-3f)
    }

    @Test
    fun `already separated pair is untouched by collision resolution`() {
        val nodes = mutableListOf(
            GraphVertex(id = "a", x = 0f, y = 0f, radius = 30f),
            GraphVertex(id = "b", x = 500f, y = 500f, radius = 30f)
        )
        val before = listOf(nodes[0].x, nodes[0].y, nodes[1].x, nodes[1].y)
        GraphLayoutMath.resolveCollisionsAndBounds(nodes)
        assertEquals(before[0], nodes[0].x, 1e-4f)
        assertEquals(before[1], nodes[0].y, 1e-4f)
        assertEquals(before[2], nodes[1].x, 1e-4f)
        assertEquals(before[3], nodes[1].y, 1e-4f)
    }

    @Test
    fun `collision bounds clamp nodes inside the world box`() {
        val nodes = mutableListOf(
            GraphVertex(id = "a", x = 5000f, y = 0f),
            GraphVertex(id = "b", x = 0f, y = -5000f)
        )
        GraphLayoutMath.resolveCollisionsAndBounds(nodes)
        assertTrue(nodes[0].x <= GraphPhysicsConfig.BOUNDS_HALF_EXTENT)
        assertTrue(nodes[0].y >= -GraphPhysicsConfig.BOUNDS_HALF_EXTENT)
        assertTrue(abs(nodes[0].x) <= GraphPhysicsConfig.BOUNDS_HALF_EXTENT)
    }

    // ---- determinism ---------------------------------------------------------

    @Test
    fun `layout is deterministic for identical inputs`() {
        fun run(): List<GraphVertex> {
            val nodes = (0 until 12).map {
                GraphVertex(id = "n$it", x = (it * 50f) % 300f, y = (it * 37f) % 300f)
            }.toMutableList()
            val edges = listOf(
                GraphEdgeRef("n0", "n1"),
                GraphEdgeRef("n1", "n2"),
                GraphEdgeRef("n2", "n0")
            )
            return GraphLayoutMath.layout(nodes, edges, 40)
        }
        val first = run()
        val second = run()
        for (i in first.indices) {
            assertEquals(first[i].x.toDouble(), second[i].x.toDouble(), 1e-4)
            assertEquals(first[i].y.toDouble(), second[i].y.toDouble(), 1e-4)
        }
    }

    @Test
    fun `layout reports progress through its callback`() {
        val nodes = listOf(
            GraphVertex(id = "a", x = 0f, y = 0f),
            GraphVertex(id = "b", x = 10f, y = 0f)
        ).toMutableList()
        var lastStep = -1
        var lastTotal = -1
        var calls = 0
        GraphLayoutMath.layout(nodes, emptyList(), 30) { p ->
            calls++
            lastStep = p.step
            lastTotal = p.totalSteps
        }
        assertTrue("progress beats must be reported", calls >= 3)
        assertEquals(30, lastStep)
        assertEquals(30, lastTotal)
    }

    @Test
    fun `layout keeps every node finite and inside bounds`() {
        val nodes = (0 until 8).map {
            GraphVertex(id = "n$it", x = it * 40f - 500f, y = it * 13f - 100f)
        }.toMutableList()
        val edges = (0 until 7).map { GraphEdgeRef("n$it", "n${it + 1}") }.toMutableList()
        val out = GraphLayoutMath.layout(nodes, edges, 50)
        for (v in out) {
            assertTrue(v.x.isFinite())
            assertTrue(v.y.isFinite())
            assertTrue(abs(v.x) <= GraphPhysicsConfig.BOUNDS_HALF_EXTENT)
            assertTrue(abs(v.y) <= GraphPhysicsConfig.BOUNDS_HALF_EXTENT)
        }
    }

    // ---- clusters -------------------------------------------------------------

    @Test
    fun `assignClusters groups pages sharing a tag`() {
        val pages = listOf(
            "p1" to setOf("proj"),
            "p2" to setOf("proj"),
            "p3" to setOf("personal")
        )
        val map = GraphLayoutMath.assignClusters(pages, emptyList())
        assertEquals(map["p1"], map["p2"])
        assertTrue(map["p1"] != map["p3"])
    }

    @Test
    fun `assignClusters groups pages linked by a wikilink even without shared tags`() {
        val pages = listOf(
            "a" to setOf<String>(),
            "b" to setOf<String>(),
            "c" to setOf<String>()
        )
        val edges = listOf(GraphEdgeRef("a", "b"))
        val map = GraphLayoutMath.assignClusters(pages, edges)
        assertEquals(map["a"], map["b"])
        assertTrue(map["a"] != map["c"])
    }

    @Test
    fun `assignClusters numbering is independent of input order`() {
        val pagesA = listOf(
            "x" to setOf("t"),
            "y" to setOf("t"),
            "z" to setOf("zz")
        )
        val pagesB = listOf(
            "z" to setOf("zz"),
            "x" to setOf("t"),
            "y" to setOf("t")
        )
        val ma = GraphLayoutMath.assignClusters(pagesA, emptyList())
        val mb = GraphLayoutMath.assignClusters(pagesB, emptyList())
        assertEquals(ma["x"], mb["x"])
        assertEquals(ma["y"], mb["y"])
        assertEquals(ma["z"], mb["z"])
    }

    @Test
    fun `isolated page forms its own cluster`() {
        val pages = listOf("alone" to setOf<String>())
        val map = GraphLayoutMath.assignClusters(pages, emptyList())
        assertEquals(0, map.getValue("alone"))
    }

    // ---- tier culling ---------------------------------------------------------

    @Test
    fun `cullToCap keeps the most recently updated pages`() {
        val pages = listOf(
            "old" to 100L,
            "mid" to 200L,
            "new" to 300L
        )
        val kept = GraphTierSelector.cullToCap(pages, 2)
        assertEquals(setOf("mid", "new"), kept)
    }

    @Test
    fun `cullToCap is a no-op below the cap`() {
        val pages = listOf("a" to 1L, "b" to 2L)
        val kept = GraphTierSelector.cullToCap(pages, 10)
        assertEquals(setOf("a", "b"), kept)
    }

    @Test
    fun `profileFor honours the low-end flag and node count`() {
        assertEquals(GraphTierProfile.LOW_END, GraphTierSelector.profileFor(true, 10))
        assertEquals(GraphTierProfile.LOW_END, GraphTierSelector.profileFor(true, 1000))
        assertEquals(GraphTierProfile.MID_RANGE, GraphTierSelector.profileFor(false, 100))
        assertEquals(GraphTierProfile.DEFAULT, GraphTierSelector.profileFor(false, 500))
    }
}