package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.NotePageEntity
import com.authorss81.noteflow.services.TagNode
import com.authorss81.noteflow.services.WikiLinkParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Phase 164: the tag vault/explorer must scope to the CURRENTLY selected
 * notebook — tags on that notebook's pages (both `#tag` full-text mentions and
 * the explicit CSV `tags` field) plus the notebook's OWN tag list; tags from
 * pages/notebooks OUTSIDE the selected notebook must never surface.
 *
 * The page↔notebook link is implicit (page.sectionId → section.notebookId) and
 * is handled in the query/ViewModel layer ([NoteflowViewModel.loadScopedTagHierarchy]
 * → `repository.getPagesForNotebookOnce`) — no DB schema change. These pure-JVM
 * tests exercise [WikiLinkParser.buildScopedTagHierarchy] (the aggregation the
 * vault now calls) plus source pins proving the production wiring is scoped.
 */
class Phase164TagVaultScopingTest {

    @Before
    fun setUp() {
        WikiLinkParser.resetCacheMetrics()
        WikiLinkParser.invalidateCaches()
    }

    @After
    fun tearDown() {
        WikiLinkParser.invalidateCaches()
    }

    private fun page(id: String, title: String, text: String? = null, tagsCsv: String = ""): NotePageEntity =
        NotePageEntity(
            id = id,
            sectionId = "sec-$id",
            title = title,
            extractedText = text ?: "",
            tags = tagsCsv
        )

    /** Every full tag path in the tree (parent paths + leaves). */
    private fun allFullTags(nodes: List<TagNode>): Set<String> {
        val out = mutableSetOf<String>()
        fun visit(node: TagNode) {
            out.add(node.fullTagPath)
            node.children.forEach(::visit)
        }
        nodes.forEach(::visit)
        return out
    }

    // --- 1. tags from notebook A never appear in notebook B's vault ---

    @Test
    fun `notebook A tags never surface in notebook B vault`() = runBlocking {
        val aPages = listOf(
            page("a1", "A note", text = "work item #alpha #project/x"),
            page("a2", "A2", text = "plain")
        )
        val bPages = listOf(
            page("b1", "B note", text = "personal #beta #project/y")
        )

        val aVault = WikiLinkParser.buildScopedTagHierarchy(aPages, listOf("house-style"))
        val bVault = WikiLinkParser.buildScopedTagHierarchy(bPages, listOf("garden"))

        val aTags = allFullTags(aVault)
        val bTags = allFullTags(bVault)

        // A's vault must contain A's tags (text + notebook tag list).
        assertTrue("A's #alpha must show in A's vault", "alpha" in aTags)
        assertTrue("A's #project/x must show in A's vault", "project/x" in aTags)
        assertTrue("A's notebook tag must show in A's vault", "house-style" in aTags)

        // B's vault must contain B's tags.
        assertTrue("B's #beta must show in B's vault", "beta" in bTags)
        assertTrue("B's #project/y must show in B's vault", "project/y" in bTags)
        assertTrue("B's notebook tag must show in B's vault", "garden" in bTags)

        // Cross-scope: nothing from A may leak into B, and vice versa.
        assertFalse("A's #alpha must NOT appear in B's vault", "alpha" in bTags)
        assertFalse("A's notebook tag must NOT appear in B's vault", "house-style" in bTags)
        assertFalse("A's #project/x must NOT appear in B's vault", "project/x" in bTags)
        assertFalse("B's #beta must NOT appear in A's vault", "beta" in aTags)
        assertFalse("B's notebook tag must NOT appear in A's vault", "garden" in aTags)
        assertFalse("B's #project/y must NOT appear in A's vault", "project/y" in aTags)
    }

    // --- 2. switching notebooks changes the vault ---

    @Test
    fun `switching notebooks changes the vault and re-scopes the cache`() = runBlocking {
        val aPages = listOf(page("a1", "A", text = "#alpha"))
        val bPages = listOf(page("b1", "B", text = "#beta"))

        val first = WikiLinkParser.buildScopedTagHierarchy(aPages, listOf("tag-a"))
        val sameAgain = WikiLinkParser.buildScopedTagHierarchy(aPages, listOf("tag-a"))
        assertEquals(
            "an unchanged notebook scope must reuse the per-scope cache",
            1L, WikiLinkParser.cacheMetrics().scopedTagRecomputes
        )

        val switched = WikiLinkParser.buildScopedTagHierarchy(bPages, listOf("tag-b"))
        assertEquals(
            "switching to a different notebook must rebuild the vault",
            2L, WikiLinkParser.cacheMetrics().scopedTagRecomputes
        )

        val firstTags = allFullTags(first)
        val switchedTags = allFullTags(switched)
        assertEquals(setOf("alpha", "tag-a"), firstTags)
        assertEquals(setOf("beta", "tag-b"), switchedTags)
        assertTrue("vault contents must differ across notebooks", firstTags != switchedTags)
    }

    // --- 3. a page moved to another notebook carries its tags with it ---

    @Test
    fun `a page moved to another notebook's vault has its tags follow it`() = runBlocking {
        val movedPage = page("shared", "Shared", text = "#itinerary")

        val beforeMoveA = WikiLinkParser.buildScopedTagHierarchy(listOf(movedPage), emptyList())
        assertTrue("pre-move the tag lives in notebook A's vault", "itinerary" in allFullTags(beforeMoveA))

        // The page is MOVED: it now belongs to notebook B's scope only.
        val afterMoveA = WikiLinkParser.buildScopedTagHierarchy(emptyList(), emptyList())
        val afterMoveB = WikiLinkParser.buildScopedTagHierarchy(listOf(movedPage), emptyList())

        assertFalse("post-move the tag no longer appears in notebook A's vault", "itinerary" in allFullTags(afterMoveA))
        assertTrue("post-move the tag follows into notebook B's vault", "itinerary" in allFullTags(afterMoveB))
    }

    // --- 4. CSV page tags + the notebook's own tag list are included ---

    @Test
    fun `page CSV tags and the notebook's own tag list show in the vault`() = runBlocking {
        val pages = listOf(
            page("p1", "P1", tagsCsv = "explicit,shared/child"),
            page("p2", "P2", text = "#hashtag")
        )
        val vault = WikiLinkParser.buildScopedTagHierarchy(pages, listOf("notebook-tag"))
        val tags = allFullTags(vault)
        assertTrue("a page's CSV tag must appear", "explicit" in tags)
        assertTrue("a nested page CSV tag must stay hierarchical", "shared/child" in tags)
        assertTrue("a page's #hashtag must appear", "hashtag" in tags)
        assertTrue("the notebook's own tag must appear", "notebook-tag" in tags)
    }

    @Test
    fun `notebook tag that also tags a page maps to that page`() = runBlocking {
        val pages = listOf(page("p1", "P1", tagsCsv = "status"))
        val vault = WikiLinkParser.buildScopedTagHierarchy(pages, listOf("status"))
        val status = vault.firstOrNull { it.name == "status" }
        assertNotNull(status)
        assertEquals(
            "a notebook tag borne by a page must list that page for filtering",
            setOf("p1"),
            status!!.matchingPageIds
        )
    }

    // --- 5. empty state ---

    @Test
    fun `a notebook with no tags yields the empty vault`() = runBlocking {
        val empty = WikiLinkParser.buildScopedTagHierarchy(emptyList(), emptyList())
        assertTrue("an unscoped/empty notebook must show the empty vault state", empty.isEmpty())
    }

    // --- 6. source pins: the production wiring is scoped, never the global read ---

    @Test
    fun `source pin - TagExplorerView and ViewModel use the scoped notebook accessor`() {
        val explorer = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/components/TagExplorerView.kt").readText()
        assertTrue("TagExplorerView must load the scoped vault", explorer.contains("viewModel.loadScopedTagHierarchy"))
        assertFalse("TagExplorerView must NOT load the whole-vault page list", explorer.contains("viewModel.loadAllActivePages()"))

        val vm = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt").readText()
        val scopedFun = snipFunction(vm, "suspend fun loadScopedTagHierarchy")
        assertTrue("the scoped accessor must query pages by notebookId", scopedFun.contains("getPagesForNotebookOnce"))
        assertTrue("the scoped accessor must guard on the selected notebook", scopedFun.contains("selectedNotebook.value"))
        assertFalse(
            "the scoped accessor must never CALL the whole-vault read",
            scopedFun.contains("repository.getAllActivePages")
        )
    }

    /** Extracts a top-level function's source from its signature up to its closing brace. */
    private fun snipFunction(source: String, signature: String): String {
        val start = source.indexOf(signature)
        val bodyStart = source.indexOf("{", start)
        require(start >= 0 && bodyStart >= 0) { "signature not found: $signature" }
        var depth = 0
        var i = bodyStart
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
            i++
        }
        return source.substring(start)
    }

    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (File(dir, "gradle/libs.versions.toml").isFile && File(dir, "app").isDirectory) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}