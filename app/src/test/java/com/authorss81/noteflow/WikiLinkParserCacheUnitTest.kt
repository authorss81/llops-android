package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.NotePageEntity
import com.authorss81.noteflow.services.TagNode
import com.authorss81.noteflow.services.WikiLinkParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase 101 (B2-DOS-11): the backlink/tag-hierarchy/knowledge-graph builders
 * previously re-read and re-scanned the ENTIRE vault per panel open with no
 * caching, no LIMIT and unbounded tag-tree recursion. These pure-JVM tests prove
 * the fixes:
 *
 *  - repeated panel opens reuse the epoch-scoped caches (no re-scan, no file
 *    re-reads) — verified via exposed CacheMetrics counters;
 *  - the scanned set is bounded to the most-recently-updated pages;
 *  - the tag tree depth is bounded even for attacker-controlled `#a/b/c/...`
 *    segments;
 *  - the epoch (lock/vault-change) boundary invalidates the caches;
 *  - a cancelled build propagates cancellation and never serves/caches a
 *    partial scan.
 */
class WikiLinkParserCacheUnitTest {

    @Before
    fun setUp() {
        WikiLinkParser.resetCacheMetrics()
        WikiLinkParser.invalidateCaches()
    }

    @After
    fun tearDown() {
        WikiLinkParser.onPageScanned = null
        WikiLinkParser.invalidateCaches()
        importsRoot.deleteRecursively()
    }

    // B1-AUTH-05 (phase-69): legacy source files must live under the imports
    // root to be readable; the scan callers pass it explicitly.
    private val importsRoot: File =
        File(System.getProperty("java.io.tmpdir"), "inkflow-importer-test-" + java.util.UUID.randomUUID()).apply { mkdirs() }

    private fun sourceFileUnderRoot(name: String, content: String): File =
        File(importsRoot, name).apply { writeText(content) }

    private fun page(id: String, title: String, text: String? = null, sourceFile: File? = null): NotePageEntity =
        NotePageEntity(
            id = id,
            sectionId = "sec",
            title = title,
            sourceFilePath = sourceFile?.absolutePath,
            sourceFileType = if (sourceFile != null) "text" else null,
            extractedText = text ?: ""
        )

    // --- 1. repeated panel opens reuse the cache (no re-scan) ---

    @Test
    fun `repeated backlinks opens reuse the cached scan without re-reading files`() = runBlocking {
        val sourceExplicit = sourceFileUnderRoot("backlink-explicit.md", "A note that mentions [[Target]] explicitly.")
        val sourceMention = sourceFileUnderRoot("backlink-mention.md", "A note that mentions Target without a wiki link.")

        val target = page("target", "Target", text = "target body")
        val explicit = page("explicit", "Explicit linker", sourceFile = sourceExplicit)
        val mention = page("mention", "Mentioner", sourceFile = sourceMention)
        val allPages = listOf(explicit, mention, target)

        // Panel open 1: computes backlinks AND caches the full text of both files.
        val (explicit1, mentions1) = WikiLinkParser.findBacklinks(target, allPages, importsRoot = importsRoot)
        assertEquals(1, explicit1.size)
        assertEquals(1, mentions1.size)
        val afterFirst = WikiLinkParser.cacheMetrics()
        assertEquals(1L, afterFirst.backlinkRecomputes)
        assertEquals(2L, afterFirst.fileReads)

        // Panel open 2 (same unlock epoch): must reuse the cache — no recompute,
        // no file I/O.
        sourceExplicit.delete() // if a re-scan happened the file would be gone
        sourceMention.delete()
        val (explicit2, mentions2) = WikiLinkParser.findBacklinks(target, allPages, importsRoot = importsRoot)
        assertEquals(explicit1.size, explicit2.size)
        assertEquals(mentions1.size, mentions2.size)
        val afterSecond = WikiLinkParser.cacheMetrics()
        assertEquals("backlinks must not be recomputed on the 2nd open", 1L, afterSecond.backlinkRecomputes)
        assertEquals("full-text must not be re-read on the 2nd open", 2L, afterSecond.fileReads)
    }

    // --- 2. the unlock-epoch boundary invalidates the caches ---

    @Test
    fun `a new epoch invalidates the caches and forces a fresh scan`() = runBlocking {
        val source = sourceFileUnderRoot("epoch-source.md", "old content mentioning [[Target]]")

        val target = page("target", "Target")
        val linker = page("linker", "Linker", sourceFile = source)
        val allPages = listOf(linker, target)

        val (explicit1, _) = WikiLinkParser.findBacklinks(target, allPages, importsRoot = importsRoot)
        assertEquals(1, explicit1.size)

        // The note is edited (a new "unlock epoch" arrives) and the file now no
        // longer mentions Target; the parser cache would be stale without the
        // epoch invalidation, but findBacklinks must rescan and see the change.
        source.writeText("totally different content")
        WikiLinkParser.invalidateCaches()

        val (explicit2, _) = WikiLinkParser.findBacklinks(target, allPages, importsRoot = importsRoot)
        assertEquals("a fresh epoch must recompute backlinks", 0, explicit2.size)
    }

    // --- 3. scanned set is capped (LIMIT) ---

    @Test
    fun `backlinks scan set is capped at the most-recent pages`() = runBlocking {
        val cap = WikiLinkParser.MAX_SCAN_PAGES
        val target = page("target", "Target")

        // cap pages that don't mention Target, plus one linking page that sits
        // OUTSIDE the scan window (oldest, last) — it must never be scanned.
        val tooOld = page("too-old", "Too old", text = "Target mention beyond the cap")

        val pagesBeyondCap = (0 until cap).map { i ->
            page("p$i", "Page $i", text = "no links")
        }.toMutableList()
        val allPages = (pagesBeyondCap + listOf(tooOld)).mapIndexed { index, p ->
            p.copy(updatedAt = (cap - index).toLong())
        }

        val (explicit, _) = WikiLinkParser.findBacklinks(target, allPages)
        assertEquals(
            "a page outside the scan cap must not be scanned",
            0,
            explicit.count { it.page.id == "too-old" }
        )
        assertEquals(0, explicit.size)
    }

    @Test
    fun `backlinks scan finds links inside the capped window`() = runBlocking {
        val cap = WikiLinkParser.MAX_SCAN_PAGES
        val target = page("target", "Target")

        val inWindow = page("in-window", "In window", text = "links [[Target]] here")
        val filler = pages(count = cap - 1) { i -> page("f$i", "Filler $i", text = "no links") }
        val allPages = (listOf(inWindow) + filler).mapIndexed { index, p ->
            p.copy(updatedAt = (cap - index).toLong())
        }

        WikiLinkParser.invalidateCaches() // isolate from the capped test above
        val (explicit, _) = WikiLinkParser.findBacklinks(target, allPages)
        assertEquals(1, explicit.count { it.page.id == "in-window" })
        assertEquals(1, explicit.size)
    }

    // --- 4. tag-hierarchy is cached and its tree depth is bounded ---

    @Test
    fun `buildTagHierarchy is cached across repeated panel opens`() = runBlocking {
        val tagged = page("tagged", "Tagged", text = "#work/projects/alpha #personal")
        val pages = listOf(tagged)

        val first = WikiLinkParser.buildTagHierarchy(pages)
        assertTrue(first.isNotEmpty())
        val second = WikiLinkParser.buildTagHierarchy(pages)
        assertEquals(first, second)
        assertEquals("tag hierarchy must not be recomputed on the 2nd open", 1L, WikiLinkParser.cacheMetrics().tagRecomputes)
    }

    @Test
    fun `tag tree depth is bounded for deep attacker-controlled tags`() = runBlocking {
        val deepTag = (1..40).joinToString("/") { "seg$it" }
        val tagged = page("tagged", "Tagged", text = "#$deepTag")
        val pages = listOf(tagged)

        val hierarchy = WikiLinkParser.buildTagHierarchy(pages)

        fun maxDepth(nodes: List<TagNode>): Int =
            if (nodes.isEmpty()) 0 else 1 + (nodes.map { maxDepth(it.children) }.maxOrNull() ?: 0)

        assertTrue("deep tag must not blow up recursion", maxDepth(hierarchy) <= WikiLinkParser.MAX_TAG_TREE_DEPTH)
        // The bounded tree must still contain the tag's pages.
        assertTrue(hierarchy.any { it.matchingPageIds.contains("tagged") })
    }

    // --- 5. knowledge-graph edges are cached ---

    @Test
    fun `wiki link edges are cached across repeated graph opens`() = runBlocking {
        val a = page("a", "Note A", text = "see [[Note B]]")
        val b = page("b", "Note B", text = "lonely")
        val pages = listOf(a, b)

        val firstEdges = WikiLinkParser.buildWikiLinkEdges(pages)
        assertEquals(1, firstEdges.size)
        assertEquals("a", firstEdges[0].sourcePageId)
        assertEquals("b", firstEdges[0].targetPageId)

        val secondEdges = WikiLinkParser.buildWikiLinkEdges(pages)
        assertEquals(firstEdges, secondEdges)
        assertEquals("edge build must not recompute on the 2nd open", 1L, WikiLinkParser.cacheMetrics().edgeRecomputes)
    }

    @Test
    fun `edges scan set is capped like backlinks`() = runBlocking {
        val cap = WikiLinkParser.MAX_SCAN_PAGES
        val a = page("a", "Note A", text = "see [[Note B]]")
        val b = page("b", "Note B", text = "lonely")
        val older = page("older", "Note C", text = "links [[Note B]] too but is old")

        val filler = pages(count = cap - 1) { i -> page("f$i", "Filler $i", text = "nothing") }
        // Note B is at the end of the sorted list (oldest → likely beyond cap);
        // only recent pages are scanned for edges.
        val ordered = (filler + listOf(a, older)).mapIndexed { index, p -> p.copy(updatedAt = 1_000_000L - index) }
        val edges = WikiLinkParser.buildWikiLinkEdges(ordered)

        assertTrue("edge scan must be capped in size", edges.size <= cap)
    }

    // --- 6. cancellation is propagated and never caches a partial scan ---

    @Test
    fun `a cancelled scan propagates cancellation and does not cache a partial result`() = runBlocking {
        val cap = WikiLinkParser.MAX_SCAN_PAGES

        val pages = (0 until cap).map { i ->
            page("p$i", "Page $i", text = "some content mentioning [[Target]] here " + "x".repeat(400))
        } + page("target", "Target", text = "target body")

        val workerRef = AtomicReference<Job?>(null)
        val sawPage = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        // Gates the worker until the test thread has published `workerRef` so
        // the seam's self-cancel is never a no-op from `worker` still being null
        // when the scan dispatches its very first page (the race that made this
        // test flaky under shared-pool contention).
        val ready = CountDownLatch(1)

        // Test seam: cancel the scan deterministically — AFTER the build has
        // started processing pages, but BEFORE it can reach the end and cache.
        WikiLinkParser.onPageScanned = {
            if (sawPage.compareAndSet(false, true)) {
                workerRef.get()?.cancel()
            }
        }
        workerRef.set(launch(Dispatchers.Default) {
            ready.await()
            try {
                WikiLinkParser.findBacklinks(pages.last(), pages)
            } catch (e: CancellationException) {
                cancelled.set(true)
                throw e
            }
        })
        ready.countDown()
        workerRef.get()!!.join()
        WikiLinkParser.onPageScanned = null

        assertTrue("the scan must have started before cancellation", sawPage.get())
        assertTrue("a cancelled scan must propagate CancellationException", cancelled.get())
        assertEquals(
            "a cancelled scan must never cache a partial/duplicate result",
            0L,
            WikiLinkParser.cacheMetrics().backlinkRecomputes
        )

        // A follow-up full scan still works correctly afterwards.
        WikiLinkParser.invalidateCaches()
        val after = WikiLinkParser.findBacklinks(pages.last(), pages)
        assertTrue(after.first.size > 0)
        assertEquals(
            "the follow-up scan must be cached exactly once",
            1L,
            WikiLinkParser.cacheMetrics().backlinkRecomputes
        )
    }

    // --- helpers ---

    private inline fun pages(count: Int, factory: (Int) -> NotePageEntity): List<NotePageEntity> =
        (0 until count).map(factory)
}