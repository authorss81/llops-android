package com.authorss81.noteflow.services.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 38: command-palette ranking, tag-filter combination and plugin-action
 * routing. Pure JVM, deterministic — seeded inputs so ordering is assertable.
 */
class CommandPaletteMathTest {

    private fun doc(
        id: String,
        title: String,
        body: String = "",
        tags: Set<String> = emptySet(),
        updatedAt: Long = id.hashCode().toLong()
    ): CommandPaletteMath.PaletteDoc =
        CommandPaletteMath.PaletteDoc(id, title, body, tags, updatedAt)

    // ---- scoring -----------------------------------------------------------

    @Test
    fun `title prefix scores above title contains`() {
        val prefix = doc("a", "Groceries List", updatedAt = 2)
        val contains = doc("b", "My Groceries And Stuff", updatedAt = 5)
        val scP = CommandPaletteMath.score("groc", prefix)!!
        val scC = CommandPaletteMath.score("groc", contains)!!
        assertTrue(scP.first > scC.first)
        assertEquals(CommandPaletteMath.MatchKind.TITLE_PREFIX, scP.second)
        assertEquals(CommandPaletteMath.MatchKind.TITLE_CONTAINS, scC.second)
    }

    @Test
    fun `tag match scores above body match`() {
        val tagHit = doc("a", "Random", tags = setOf("shopping"), updatedAt = 1)
        val bodyHit = doc("b", "Random", body = "the shopping list has milk", updatedAt = 2)
        val scT = CommandPaletteMath.score("shop", tagHit)!!
        val scB = CommandPaletteMath.score("shop", bodyHit)!!
        assertTrue(scT.first > scB.first)
        assertEquals(CommandPaletteMath.MatchKind.TAG_MATCH, scT.second)
        assertEquals(CommandPaletteMath.MatchKind.BODY_CONTAINS, scB.second)
    }

    @Test
    fun `score is case-insensitive`() {
        assertTrue(CommandPaletteMath.score("GROC", doc("a", "groceries")) != null)
    }

    @Test
    fun `score returns null when nothing matches`() {
        assertNull(CommandPaletteMath.score("zzz", doc("a", "Alpha", body = "beta")))
    }

    // ---- ranking -----------------------------------------------------------

    @Test
    fun `rank tiebreaks by updatedAt desc then id asc`() {
        val docs = listOf(
            doc("a1", "Alpha", updatedAt = 10),
            doc("b2", "Alpha", updatedAt = 20),
            doc("c3", "Alpha Stuff", updatedAt = 5)
        )
        val ranked = CommandPaletteMath.rank("alpha", docs)
        assertEquals(listOf("b2", "a1", "c3"), ranked.map { it.doc.id })
    }

    @Test
    fun `rank only returns matching documents`() {
        val docs = listOf(
            doc("a", "Alpha", updatedAt = 1),
            doc("b", "Beta", updatedAt = 2)
        )
        val ranked = CommandPaletteMath.rank("zzz", docs)
        assertTrue(ranked.isEmpty())
    }

    // ---- R2-b2b5-FEA-05 (phase-152): lowercasing happens ONCE at index build ----

    @Test
    fun `PaletteDoc precomputes the lowercased corpus at construction`() {
        val d = CommandPaletteMath.PaletteDoc(
            "a", "My Groceries", "The QUICK brown Fox.", setOf("Shopping", "Home"), 1L
        )
        assertEquals("my groceries", d.lowerTitle)
        assertEquals("the quick brown fox.", d.lowerBody)
        assertEquals(setOf("shopping", "home"), d.lowerTags)
    }

    @Test
    fun `score stays case-insensitive against pre-lowercased docs`() {
        val d = doc("a", "My GROCERIES List", body = "contains MILK and eggs", tags = setOf("Shopping"))
        assertTrue(CommandPaletteMath.score("groceries", d) != null)
        assertTrue(CommandPaletteMath.score("GROC", d) != null)
        assertTrue(CommandPaletteMath.score("milk", d) != null)
        assertEquals(CommandPaletteMath.MatchKind.TAG_MATCH,
            CommandPaletteMath.score("shopping", d)?.second)
    }

    @Test
    fun `rank across repeated keystrokes keeps case-insensitive matching from the cached lowercase`() {
        val docs = listOf(
            doc("a", "Groceries", body = "buy MILK today", updatedAt = 1),
            doc("b", "Notes", body = "no mention", updatedAt = 2)
        )
        repeat(5) { CommandPaletteMath.rank("milK", docs) }
        val ranked = CommandPaletteMath.rank("milk", docs)
        assertEquals(listOf("a"), ranked.map { it.doc.id })
        // The snippet is cut from the ORIGINAL-case body (lowerBody is only used
        // for locating the hit), so it preserves "MILK".
        assertTrue(ranked[0].snippet.contains("MILK"))
    }

    @Test
    fun `rank with a tag filter uses the precomputed lowercase tags`() {
        val red = doc("a", "One", tags = setOf("RED"), updatedAt = 1)
        val both = doc("b", "Both", tags = setOf("Red", "blue"), updatedAt = 2)
        val ranked = CommandPaletteMath.rank("", listOf(red, both), setOf("red", "blue"), requireAllTags = true)
        assertEquals(listOf("b"), ranked.map { it.doc.id })
    }

    /**
     * R2-b2b5-FEA-05 (phase-152) corpus-lowercase count source pin: the ONLY
     * place the corpus title/body/tags are lowercased is the `PaletteDoc`
     * constructor defaults (index build); [rank]/[score]/[makeSnippet] consume
     * the precomputed `lowerTitle`/`lowerBody`/`lowerTags` and never re-lowercase
     * per keystroke. The per-keystroke `lowerLog` cache is gone too.
     */
    @Test
    fun `the corpus is lowercased once at index build, never per keystroke`() {
        val source = java.io.File(
            repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/graph/CommandPaletteMath.kt"
        ).readText()
        assertTrue("PaletteDoc must carry the precomputed lowerTitle", source.contains("val lowerTitle: String = title.lowercase()"))
        assertTrue("PaletteDoc must carry the precomputed lowerBody", source.contains("val lowerBody: String = body.lowercase()"))
        assertTrue("PaletteDoc must carry the precomputed lowerTags", source.contains("lowerTags"))
        assertTrue("score must consume lowerTitle", source.contains("val lt = doc.lowerTitle"))
        assertTrue("score must consume lowerBody", source.contains("doc.lowerBody.contains(lq)"))
        assertTrue("rank must consume lowerBody for the snippet", source.contains("makeSnippet(doc.body, doc.lowerBody, q.lowercase())"))
        assertFalse(
            "rank() must never re-lowercase the corpus body per keystroke",
            source.contains("doc.body.lowercase()")
        )
        assertFalse(
            "score() must never re-lowercase the corpus title per keystroke",
            source.contains("doc.title.lowercase()")
        )
        assertFalse(
            "the per-keystroke lowerLog cache must be gone",
            source.contains("lowerLog")
        )
    }

    @Test
    fun `rank builds a snippet around the body match`() {
        val d = doc("a", "Title", body = "the quick brown fox jumps over the lazy dog")
        val ranked = CommandPaletteMath.rank("brown", listOf(d))
        assertEquals(1, ranked.size)
        assertTrue("snippet must contain the hit", ranked[0].snippet.contains("brown"))
    }

    @Test
    fun `rank respects maxResults cap`() {
        val docs = (0 until 30).map { doc("n$it", "Cached Note", updatedAt = it.toLong()) }
        val ranked = CommandPaletteMath.rank("cache", docs, maxResults = 5)
        assertEquals(5, ranked.size)
    }

    // ---- tag filtering -------------------------------------------------------

    @Test
    fun `rank applies AND tag filter requiring every tag`() {
        val red = doc("a", "One", tags = setOf("red"), updatedAt = 1)
        val both = doc("b", "Both", tags = setOf("red", "blue"), updatedAt = 2)
        val ranked = CommandPaletteMath.rank("", listOf(red, both), setOf("red", "blue"), requireAllTags = true)
        assertEquals(listOf("b"), ranked.map { it.doc.id })
    }

    @Test
    fun `rank applies OR tag filter requiring any tag`() {
        val red = doc("a", "One", tags = setOf("red"), updatedAt = 1)
        val blue = doc("b", "Two", tags = setOf("blue"), updatedAt = 2)
        val ranked = CommandPaletteMath.rank("", listOf(red, blue), setOf("red", "blue"), requireAllTags = false)
        assertEquals(setOf("a", "b"), ranked.map { it.doc.id }.toSet())
    }

    @Test
    fun `matchesTagFilter AND requires every selected tag`() {
        val docTags = setOf("red", "blue")
        assertTrue(CommandPaletteMath.matchesTagFilter(docTags, setOf("red", "blue"), true))
        assertTrue(!CommandPaletteMath.matchesTagFilter(setOf("red"), setOf("red", "blue"), true))
    }

    @Test
    fun `matchesTagFilter OR accepts any selected tag and ignores case`() {
        assertTrue(CommandPaletteMath.matchesTagFilter(setOf("Red"), setOf("red", "blue"), false))
        assertTrue(!CommandPaletteMath.matchesTagFilter(setOf("green"), setOf("red", "blue"), false))
    }

    @Test
    fun `matchesTagFilter empty selection matches everything`() {
        assertTrue(CommandPaletteMath.matchesTagFilter(setOf(), emptySet(), true))
    }

    // ---- action routing ------------------------------------------------------

    @Test
    fun `bare keyword routes to an action with empty arg`() {
        val m = CommandPaletteMath.matchAction("ocr")
        assertEquals("ocr", m?.action?.id)
        assertEquals("", m?.arg)
    }

    @Test
    fun `keyword colon arg extracts the payload`() {
        val m = CommandPaletteMath.matchAction("web: css grid")
        assertEquals("web-search", m?.action?.id)
        assertEquals("css grid", m?.arg)
    }

    @Test
    fun `keyword space arg extracts the payload`() {
        val m = CommandPaletteMath.matchAction("web css grid")
        assertEquals("web-search", m?.action?.id)
        assertEquals("css grid", m?.arg)
    }

    @Test
    fun `tab-separated arg is extracted`() {
        val m = CommandPaletteMath.matchAction("convert\t2 km to mi")
        assertEquals("units", m?.action?.id)
        assertEquals("2 km to mi", m?.arg)
    }

    @Test
    fun `longest keyword wins over its prefix sibling`() {
        val catalog = listOf(
            CommandPaletteMath.PaletteActionDescriptor("short", "run", "Run", "cap", needsArg = true),
            CommandPaletteMath.PaletteActionDescriptor("longer", "runner", "Runner", "cap", needsArg = true)
        )
        val long = CommandPaletteMath.matchAction("runner: now", catalog)
        assertEquals("longer", long?.action?.id)
        assertEquals("now", long?.arg)
        val short = CommandPaletteMath.matchAction("run: me", catalog)
        assertEquals("short", short?.action?.id)
    }

    @Test
    fun `non-keyword query returns null`() {
        assertNull(CommandPaletteMath.matchAction("hello world"))
    }

    @Test
    fun `blank query returns null`() {
        assertNull(CommandPaletteMath.matchAction("   "))
    }

    @Test
    fun `action catalog ids are unique and map to capability keys`() {
        val ids = CommandPaletteMath.ACTION_CATALOG.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        CommandPaletteMath.ACTION_CATALOG.forEach { action ->
            assertTrue("action ${action.id} must have a capability key", action.capabilityKey.isNotBlank())
        }
    }

    /**
     * Phase 38 REPORT evidence: measure keystroke→result latency of the ranking
     * path on a 1,500-doc corpus (the NoteRepository cache ceiling) and the
     * force-directed layout cost, writing the numbers to
     * `build/phase38-bench.txt` for the report. No assertion — this is a
     * measurement capture, not a flakiness risk.
     */
    @Test
    fun `report benchmark numbers`() {
        val sb = StringBuilder()
        val docs = (0 until 1500).map { i ->
            CommandPaletteMath.PaletteDoc(
                id = "page$i",
                title = if (i % 7 == 0) "Groceries List $i" else "Note $i",
                body = "the quick brown fox jumps over the lazy dog ($i) with a groceries mention",
                tags = setOf(if (i % 3 == 0) "shopping" else "personal"),
                updatedAt = i.toLong()
            )
        }
        repeat(3) { CommandPaletteMath.rank("groc", docs) }
        val t0 = System.nanoTime()
        repeat(5) { CommandPaletteMath.rank("groc", docs) }
        val t1 = System.nanoTime()
        val rankAvgMs = (t1 - t0) / 5 / 1_000_000.0

        val nodes = (0 until 240).map {
            GraphVertex(id = "n$it", x = (it * 37) % 700 - 350f, y = (it * 53) % 700 - 350f)
        }.toMutableList()
        val edges = (0 until 239).map { GraphEdgeRef("n$it", "n${it + 1}") }.toMutableList()
        repeat(2) { GraphLayoutMath.layout(nodes, edges, 60) }
        val t2 = System.nanoTime()
        GraphLayoutMath.layout(nodes, edges, 60)
        val t3 = System.nanoTime()
        val layoutMs = (t3 - t2) / 1_000_000.0

        sb.append("rank_1500_docs_avg_ms=$rankAvgMs\n")
        sb.append("layout_240_nodes_60_iter_ms=$layoutMs\n")
        sb.append("rank_1500_docs_per_keystroke_plus_250ms_debounce_ms=${250 + rankAvgMs}\n")
        java.io.File("build/phase38-bench.txt").writeText(sb.toString())
        println("PHASE38_BENCH " + sb.toString().trim().replace('\n', ' '))
    }

    private fun repoRoot(): java.io.File {
        val cwd = java.io.File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (java.io.File(dir, "gradle/libs.versions.toml").isFile &&
                java.io.File(dir, "app").isDirectory
            ) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}