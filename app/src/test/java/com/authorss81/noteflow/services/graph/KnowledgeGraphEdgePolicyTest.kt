package com.authorss81.noteflow.services.graph

import com.authorss81.noteflow.services.WikiLinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 152 (R2-b2b5-FEA-01): the Knowledge Graph's rendered edge set is now
 * bounded — a crafted ~2k-page interlinked vault can no longer materialize ~10⁶
 * edges and iterate them every frame.
 *
 * Pure JVM + deterministic, so the caps are assertable exactly.
 */
class KnowledgeGraphEdgePolicyTest {

    private fun ref(src: String, tgt: String): GraphEdgeRef = GraphEdgeRef(src, tgt)

    // ---------- cullEdgesToSurvivors ----------

    @Test
    fun `edges whose either endpoint was culled are dropped`() {
        val survivors = setOf("a", "b")
        val culled = KnowledgeGraphEdgePolicy.cullEdgesToSurvivors(
            listOf(ref("a", "b"), ref("a", "c"), ref("c", "d"), ref("b", "c")),
            survivors,
            emptyMap(),
            1000
        )
        assertEquals(listOf(ref("a", "b")), culled)
    }

    @Test
    fun `self loops are dropped`() {
        val culled = KnowledgeGraphEdgePolicy.cullEdgesToSurvivors(
            listOf(ref("a", "a"), ref("a", "b")),
            setOf("a", "b"),
            emptyMap(),
            1000
        )
        assertEquals(listOf(ref("a", "b")), culled)
    }

    @Test
    fun `duplicate edges are deduped deterministically`() {
        val culled = KnowledgeGraphEdgePolicy.cullEdgesToSurvivors(
            listOf(ref("a", "b"), ref("a", "b"), ref("b", "a"), ref("a", "b")),
            setOf("a", "b"),
            emptyMap(),
            1000
        )
        assertEquals(2, culled.size)
        assertTrue(culled.contains(ref("a", "b")))
        assertTrue(culled.contains(ref("b", "a")))
    }

    @Test
    fun `empty survivor set yields no edges`() {
        assertTrue(
            KnowledgeGraphEdgePolicy.cullEdgesToSurvivors(
                listOf(ref("a", "b")),
                emptySet(),
                emptyMap(),
                1000
            ).isEmpty()
        )
    }

    @Test
    fun `empty edge input yields no edges`() {
        assertTrue(
            KnowledgeGraphEdgePolicy.cullEdgesToSurvivors(
                emptyList(),
                setOf("a", "b"),
                emptyMap(),
                1000
            ).isEmpty()
        )
    }

    @Test
    fun `non positive edge budget fails closed`() {
        assertTrue(
            KnowledgeGraphEdgePolicy.cullEdgesToSurvivors(
                listOf(ref("a", "b")),
                setOf("a", "b"),
                emptyMap(),
                0
            ).isEmpty()
        )
    }

    @Test
    fun `below the budget every survivor pair is kept unchanged`() {
        val edges = listOf(ref("a", "b"), ref("b", "c"), ref("c", "a"))
        val culled = KnowledgeGraphEdgePolicy.cullEdgesToSurvivors(edges, setOf("a", "b", "c"), emptyMap(), 10)
        assertEquals(3, culled.size)
        assertEquals(edges, culled)
    }

    @Test
    fun `over the budget the top K by endpoint recency survive`() {
        // Three edges among survivor pages; budget 2. Recency = max(updatedAt
        // of the two endpoints). b is newest (500) so every edge touching b wins.
        val updatedAt = mapOf("a" to 100L, "b" to 500L, "c" to 300L, "d" to 400L)
        val survivors = setOf("a", "b", "c", "d")
        val culled = KnowledgeGraphEdgePolicy.cullEdgesToSurvivors(
            listOf(ref("a", "c"), ref("b", "d"), ref("a", "d")),
            survivors,
            updatedAt,
            2
        )
        assertEquals(2, culled.size)
        // (b,d) recency 500, (a,d) recency 400, (a,c) recency 300 → top two.
        assertTrue(culled.contains(ref("b", "d")))
        assertTrue(culled.contains(ref("a", "d")))
    }

    @Test
    fun `top K tiebreak is deterministic on ids`() {
        // All three edges share recency 0 → tie-break by sourceId then targetId.
        val updatedAt = mapOf("a" to 0L, "b" to 0L, "c" to 0L, "d" to 0L)
        val survivors = setOf("a", "b", "c", "d")
        val culled = KnowledgeGraphEdgePolicy.cullEdgesToSurvivors(
            listOf(ref("d", "c"), ref("b", "c"), ref("a", "d")),
            survivors,
            updatedAt,
            2
        )
        assertEquals(listOf(ref("a", "d"), ref("b", "c")), culled)
    }

    // ---------- edgeCapFor ----------

    @Test
    fun `edge budget tracks the tier and node count`() {
        assertEquals(300, KnowledgeGraphEdgePolicy.edgeCapFor(lowEnd = true, nodeCount = 5000))
        assertEquals(300, KnowledgeGraphEdgePolicy.edgeCapFor(lowEnd = true, nodeCount = 1))
        assertEquals(1000, KnowledgeGraphEdgePolicy.edgeCapFor(lowEnd = false, nodeCount = 300))
        assertEquals(1000, KnowledgeGraphEdgePolicy.edgeCapFor(lowEnd = false, nodeCount = 5000))
        assertEquals(600, KnowledgeGraphEdgePolicy.edgeCapFor(lowEnd = false, nodeCount = 100))
    }

    @Test
    fun `the rendered edge set can never exceed the cap even with a huge input`() {
        val survivors = (0 until 300).map { "p$it" }.toSet()
        val updatedAt = survivors.associateWith { it.removePrefix("p").toLong() }
        val many = (0 until 5000).map { ref("p${it % 300}", "p${(it + 7) % 300}") }
        val cap = KnowledgeGraphEdgePolicy.edgeCapFor(false, 300)
        val culled = KnowledgeGraphEdgePolicy.cullEdgesToSurvivors(many, survivors, updatedAt, cap)
        assertTrue("culled size ${culled.size} must be <= cap $cap", culled.size <= cap)
    }

    // ---------- WikiLinkParser per-page + total edge caps ----------

    @Test
    fun `extractWikiLinks caps links per page`() {
        val dense = buildString {
            repeat(500) { append("[[Target $it]] ") }
        }
        val links = WikiLinkParser.extractWikiLinks(dense)
        assertEquals("a single page must contribute at most the per-page cap links",
            WikiLinkParser.MAX_LINKS_PER_PAGE, links.size)
    }

    @Test
    fun `extractWikiLinks below the cap is unaffected`() {
        val text = "[[A]] and [[B|alias]] and [[C]]"
        val links = WikiLinkParser.extractWikiLinks(text)
        assertEquals(3, links.size)
        assertEquals("A", links[0].targetTitle)
        assertEquals("B", links[1].targetTitle)
        assertEquals("alias", links[1].alias)
    }

    @Test
    fun `the per page cap is applied during discovery not after a full list is built`() {
        // 500 links on one page must NOT yield 500 edges in the index build.
        val source = "Page 0"
        val targets = (0 until 500).joinToString(" ") { "[[$it]]" }
        val page = com.authorss81.noteflow.data.model.NotePageEntity(
            id = "p0",
            sectionId = "s",
            title = source,
            sourceFilePath = null,
            sourceFileType = null,
            extractedText = targets
        )
        val pages = listOf(page) + (0 until 500).map {
            com.authorss81.noteflow.data.model.NotePageEntity(
                id = "t$it",
                sectionId = "s",
                title = "$it",
                sourceFilePath = null,
                sourceFileType = null,
                extractedText = ""
            )
        }
        kotlinx.coroutines.runBlocking {
            val edges = WikiLinkParser.buildWikiLinkEdges(pages)
            assertEquals(
                "one page's links must be capped to the per-page budget",
                WikiLinkParser.MAX_LINKS_PER_PAGE.toLong(),
                edges.size.toLong()
            )
        }
    }

    @Test
    fun `buildWikiLinkEdges never exceeds the total edge budget`() {
        // 600 pages each linking the same 200 targets → ~120k unique edges, which
        // is past the 100k total budget. The build must stop at the budget.
        val pages = (0 until 600).map { i ->
            com.authorss81.noteflow.data.model.NotePageEntity(
                id = "p$i",
                sectionId = "s",
                title = "Page $i",
                sourceFilePath = null,
                sourceFileType = null,
                extractedText = (0 until 200).joinToString(" ") { "[[Page $it]]" }
            )
        }
        kotlinx.coroutines.runBlocking {
            val edges = WikiLinkParser.buildWikiLinkEdges(pages)
            assertTrue(
                "edge index size ${edges.size} must be <= MAX_TOTAL_EDGES ${WikiLinkParser.MAX_TOTAL_EDGES}",
                edges.size <= WikiLinkParser.MAX_TOTAL_EDGES
            )
            assertEquals(
                "the dense vault must actually exceed the budget to prove the cap binds",
                WikiLinkParser.MAX_TOTAL_EDGES,
                edges.size
            )
            assertEquals(edges.size, edges.distinct().size)
        }
    }
}
