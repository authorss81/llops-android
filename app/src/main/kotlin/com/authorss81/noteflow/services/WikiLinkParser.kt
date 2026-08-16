package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.NotePageEntity
import com.authorss81.noteflow.data.repository.LruBoundedMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

data class WikiLink(
    val rawText: String,
    val targetTitle: String,
    val alias: String?,
    val startIndex: Int,
    val endIndex: Int
)

data class TagNode(
    val name: String,
    val fullTagPath: String,
    val noteCount: Int,
    val children: List<TagNode> = emptyList(),
    val matchingPageIds: Set<String> = emptySet()
)

data class BacklinkMatch(
    val page: NotePageEntity,
    val snippet: String,
    val isExplicitWikiLink: Boolean
)

data class WikiLinkEdge(
    val sourcePageId: String,
    val targetPageId: String
)

/**
 * WikiLink/tag scan + tree builders for the Backlinks inspector, Tag Explorer
 * and Knowledge Graph.
 *
 * B2-DOS-11 (phase-101): these builders previously re-read and re-scanned the
 * ENTIRE vault on every panel open — O(notes × avg-note-KB) file I/O + regex
 * on the caller's dispatcher, recomputed from scratch each visit, with a tag
 * tree whose recursion depth was attacker-controlled (`#a/b/c/...` segments).
 * All three hardening measures are now in place:
 *
 *  - every result is cached per unlock epoch ([cacheEpoch], bumped by
 *    [invalidateCaches]) AND per input page-list fingerprint ([pagesFingerprint]);
 *    repeated panel opens hit the cache and never rescan, and a different list is
 *    never served a result built for another list;
 *  - the scanned set is capped at [MAX_SCAN_PAGES] most-recently-updated pages;
 *  - [getFullTextForPage] results are cached in-memory with an LRU bound;
 *  - tag-tree depth is bounded to [MAX_TAG_TREE_DEPTH] `/`-segments;
 *  - all compute runs on [Dispatchers.Default] and checks cancellation
 *    ([ensureActive]) so closing the panel aborts mid-build and no partial
 *    result is cached.
 *
 * Staleness model: the epoch is bumped by every in-app note mutation, vault lock
 * and key replacement, so any change made through the repository is reflected on
 * the next build. Source files edited *outside* the repository (e.g. on disk via a
 * file manager) are only picked up after the next epoch bump — a direct edit that
 * bypasses the app must either call [invalidateCaches] (or [invalidateTextCache]
 * after the write) to force freshness.
 */
object WikiLinkParser {

    private val wikiLinkRegex = Regex("\\[\\[([^\\]|]+)(?:\\|([^\\]]+))?\\]\\]")
    private val tagRegex = Regex("(?:^|\\s)#([^\\s#\\[\\]{}()|.,!?:;\"]+)")

    // ---- B2-DOS-11 resource-exhaustion guards ----
    internal const val MAX_SCAN_PAGES = 2000
    internal const val MAX_TAG_TREE_DEPTH = 12
    internal const val MAX_TAGS = 20000
    private const val MAX_TEXT_CACHE_ENTRIES = 200
    private const val MAX_BACKLINK_CACHE_ENTRIES = 200

    private val cacheLock = Any()
    @Volatile
    private var cacheEpoch = 0L

    private val fullTextCache = LruBoundedMap<FullTextKey, String>(MAX_TEXT_CACHE_ENTRIES)
    private val backlinksCache =
        LruBoundedMap<BacklinkCacheKey, EpochEntry<Pair<List<BacklinkMatch>, List<BacklinkMatch>>>>(MAX_BACKLINK_CACHE_ENTRIES)
    private var tagHierarchyEntry: EpochEntry<List<TagNode>>? = null
    private var edgesEntry: EpochEntry<List<WikiLinkEdge>>? = null

    private data class EpochEntry<T>(val epoch: Long, val fingerprint: String, val value: T)
    private data class BacklinkCacheKey(val fingerprint: String, val targetPageId: String)

    // B1-AUTH-05 (phase-69): the full-text cache is keyed by (page id, imports
    // root) so a scan that may read confined legacy source files is never served
    // a cached body built for a different root (or the no-file null root).
    private data class FullTextKey(val pageId: String, val importsRootPath: String?)

    /**
     * Cheap, collision-free fingerprint of the page list a builder is asked to scan
     * (page id + updatedAt). Derived results are cached only for the exact input list
     * they were computed from, so a different subset/list within the same epoch is
     * never served a result built for another list. The epoch still guards lock/re-key
     * and in-app mutations (which bump `updatedAt`).
     */
    private fun pagesFingerprint(pages: List<NotePageEntity>): String {
        if (pages.isEmpty()) return ""
        val sb = StringBuilder(pages.size * 16)
        for (p in pages) {
            sb.append(p.id).append(':').append(p.updatedAt).append(';')
        }
        return sb.toString()
    }

    // Test seam so a test can deterministically cancel a backlink scan mid-build; null in release.
    @Volatile
    internal var onPageScanned: ((NotePageEntity) -> Unit)? = null

    // Exposed counters so pure-JVM tests can prove "no re-scan" objectively.
    internal data class CacheMetrics(
        val fullTextCacheHits: Long,
        val fileReads: Long,
        val textRecomputes: Long,
        val backlinkRecomputes: Long,
        val tagRecomputes: Long,
        val edgeRecomputes: Long
    )

    @Volatile
    private var metricsFullTextCacheHits = 0L
    @Volatile
    private var metricsFileReads = 0L
    @Volatile
    private var metricsTextRecomputes = 0L
    @Volatile
    private var metricsBacklinkRecomputes = 0L
    @Volatile
    private var metricsTagRecomputes = 0L
    @Volatile
    private var metricsEdgeRecomputes = 0L

    internal fun resetCacheMetrics() {
        metricsFullTextCacheHits = 0L
        metricsFileReads = 0L
        metricsTextRecomputes = 0L
        metricsBacklinkRecomputes = 0L
        metricsTagRecomputes = 0L
        metricsEdgeRecomputes = 0L
    }

    internal fun cacheMetrics(): CacheMetrics = CacheMetrics(
        fullTextCacheHits = metricsFullTextCacheHits,
        fileReads = metricsFileReads,
        textRecomputes = metricsTextRecomputes,
        backlinkRecomputes = metricsBacklinkRecomputes,
        tagRecomputes = metricsTagRecomputes,
        edgeRecomputes = metricsEdgeRecomputes
    )

    /**
     * Marks the beginning of a new unlock epoch and drops every derived cache.
     * Called from [com.authorss81.noteflow.data.repository.NoteRepository.invalidateSearchCorpus]
     * (which fires on vault lock, key replacement AND every page mutation), so
     * cached plaintext never survives a lock and edits are never masked by a stale
     * scan. In-flight builds started before the bump that finish after it are
     * discarded (epoch mismatch on store) — no decrypted content becomes resident
     * post-lock and no partial result is cached.
     */
    fun invalidateCaches() {
        synchronized(cacheLock) {
            cacheEpoch++
            fullTextCache.clear()
            backlinksCache.clear()
            tagHierarchyEntry = null
            edgesEntry = null
        }
    }

    /**
     * Invalidates the full-text cache after a direct file edit that bypasses the
     * repository. Because the page's file was rewritten, every derived result that
     * could embed its old text is suspect, so the whole cache epoch is bumped and
     * all caches dropped — an in-flight [getFullTextForPage] that finished just
     * after this call stores nothing (epoch mismatch), which closes the "stale text
     * re-added after invalidation" race.
     */
    fun invalidateTextCache(pageId: String) = invalidateCaches()

    fun extractWikiLinks(text: String): List<WikiLink> {
        if (text.isBlank()) return emptyList()
        return wikiLinkRegex.findAll(text).map { match ->
            val rawText = match.value
            val targetTitle = match.groupValues[1].trim()
            val alias = match.groupValues[2].takeIf { it.isNotBlank() }?.trim()
            WikiLink(
                rawText = rawText,
                targetTitle = targetTitle,
                alias = alias,
                startIndex = match.range.first,
                endIndex = match.range.last + 1
            )
        }.toList()
    }

    fun extractTags(text: String): List<String> = extractTagsBounded(text, Int.MAX_VALUE)

    /**
     * Bounded variant used by the tag-hierarchy builder: the per-page distinct-tag
     * intermediate list is capped *during* extraction (not after), so a single
     * pathological page cannot materialize an unbounded list before [MAX_TAGS]
     * protection applies.
     */
    private fun extractTagsBounded(text: String, maxTags: Int): List<String> {
        if (text.isBlank()) return emptyList()
        val tags = mutableListOf<String>()
        val seen = HashSet<String>(minOf(4096, maxTags))
        for (match in tagRegex.findAll(text)) {
            if (seen.size >= maxTags) break
            val tag = match.groupValues[1].lowercase().trim('/')
            if (seen.add(tag)) {
                tags.add(tag)
            }
        }
        return tags
    }

    /**
     * Full-text (title + extractedText + optional source file body) with a
     * bounded, epoch-scoped in-memory cache. The store is guarded by the epoch
     * captured at the start of this call: if the vault was locked/re-keyed while
     * the file was being read, the result is discarded (never cached) so
     * decrypted note content cannot become resident after a lock.
     */
    suspend fun getFullTextForPage(page: NotePageEntity, importsRoot: File? = null): String {
        val epoch = synchronized(cacheLock) { cacheEpoch }
        val key = FullTextKey(page.id, importsRoot?.absolutePath)
        val cached = synchronized(cacheLock) { fullTextCache[key] }
        if (cached != null) {
            metricsFullTextCacheHits++
            return cached
        }
        val text = readFullText(page, importsRoot)
        synchronized(cacheLock) {
            if (cacheEpoch == epoch) {
                fullTextCache[key] = text
                metricsTextRecomputes++
            }
        }
        return text
    }

    private suspend fun readFullText(page: NotePageEntity, importsRoot: File?): String =
        withContext(Dispatchers.IO) {
            val sb = StringBuilder()
            sb.append(page.title).append("\n")
            page.extractedText?.let { sb.append(it).append("\n") }
            // B1-DB-4 (phase-44): after the one-time body migration a text page
            // has no source file at all — its body lives only in the (decrypted)
            // extractedText column above. A legacy plaintext file is coalesced
            // only if it STILL exists (pre-migration vault, or a direct disk edit
            // made before the epoch bump); it is never a new storage location.
            page.sourceFilePath?.let { rawPath ->
                val isTextFile = NoteBodyVaultPolicy.isNoteTextBodySource(rawPath, page.sourceFileType)
                // B1-AUTH-05 (phase-69): only a legacy source path that is CONFINED
                // under the imports root may be read — a null root, a `..`
                // traversal, a relative path, or an absolute path outside the
                // imports subtree is refused before any file I/O.
                val path = SourceFilePathPolicy.confine(rawPath, importsRoot)
                if (isTextFile && path != null) {
                    val f = File(path)
                    if (f.exists() && f.canRead()) {
                        try {
                            metricsFileReads++
                            // B2-DOS-05 (phase-81): legacy body reads are head-bounded
                            // so a multi-GB plaintext file can never be fully
                            // `readText()`-ed into heap during vault scans.
                            sb.append(AttachmentIngestPolicy.readTextHead(f))
                        } catch (e: Exception) {
                            // Safe read fallback
                        }
                    }
                }
            }
            sb.toString()
        }

    /**
     * Returns (explicit [[WikiLink]] backlinks, unlinked plain-text mentions) that
     * point at [targetPage]. Cached per (epoch, target-page). Repeated panel opens
     * for the same note reuse the cache. [forceRefresh] bypasses the result cache
     * (used when a mention was just converted to a wikilink via an in-place file
     * edit) — the scan itself still runs on [Dispatchers.Default] and is
     * cancellable.
     */
    suspend fun findBacklinks(
        targetPage: NotePageEntity,
        allPages: List<NotePageEntity>,
        forceRefresh: Boolean = false,
        importsRoot: File? = null
    ): Pair<List<BacklinkMatch>, List<BacklinkMatch>> {
        val epoch = synchronized(cacheLock) { cacheEpoch }
        val key = BacklinkCacheKey(pagesFingerprint(allPages), targetPage.id)
        if (!forceRefresh) {
            val cached = synchronized(cacheLock) { backlinksCache[key] }
            if (cached != null && cached.epoch == epoch) {
                return cached.value
            }
        }
        val result = computeBacklinks(targetPage, allPages, epoch, importsRoot)
        synchronized(cacheLock) {
            if (cacheEpoch == epoch) {
                backlinksCache[key] = EpochEntry(epoch, key.fingerprint, result)
                metricsBacklinkRecomputes++
            }
        }
        return result
    }

    private suspend fun computeBacklinks(
        targetPage: NotePageEntity,
        allPages: List<NotePageEntity>,
        epoch: Long,
        importsRoot: File?
    ): Pair<List<BacklinkMatch>, List<BacklinkMatch>> =
        withContext(Dispatchers.Default) {
            val targetTitle = targetPage.title.replace(".md", "").replace(".txt", "").trim()
            if (targetTitle.isBlank()) return@withContext Pair(emptyList(), emptyList())

            val explicitLinks = mutableListOf<BacklinkMatch>()
            val unlinkedMentions = mutableListOf<BacklinkMatch>()
            val wordBoundaryRegex = Regex("(?i)\\b${Regex.escape(targetTitle)}\\b")

            val scanSet = allPages.take(MAX_SCAN_PAGES)
            for (page in scanSet) {
                currentCoroutineContext().ensureActive()
                onPageScanned?.invoke(page)
                if (page.id == targetPage.id) continue
                val fullText = getFullTextForPage(page, importsRoot)
                val wikiLinks = extractWikiLinks(fullText)

                val matchedWikiLink = wikiLinks.find {
                    it.targetTitle.equals(targetTitle, ignoreCase = true) ||
                    it.targetTitle.equals(targetPage.title, ignoreCase = true)
                }

                if (matchedWikiLink != null) {
                    val snippet = createSnippet(fullText, matchedWikiLink.startIndex, matchedWikiLink.endIndex)
                    explicitLinks.add(BacklinkMatch(page, snippet, isExplicitWikiLink = true))
                } else {
                    val match = wordBoundaryRegex.find(fullText)
                    if (match != null) {
                        val snippet = createSnippet(fullText, match.range.first, match.range.last + 1)
                        unlinkedMentions.add(BacklinkMatch(page, snippet, isExplicitWikiLink = false))
                    }
                }
            }

            if (synchronized(cacheLock) { cacheEpoch } != epoch) {
                return@withContext Pair(emptyList(), emptyList())
            }
            Pair(explicitLinks, unlinkedMentions)
        }

    private fun createSnippet(text: String, start: Int, end: Int, padding: Int = 40): String {
        val cleanText = text.replace("\n", " ")
        val snippetStart = (start - padding).coerceAtLeast(0)
        val snippetEnd = (end + padding).coerceAtMost(cleanText.length)
        var snippet = cleanText.substring(snippetStart, snippetEnd).trim()
        if (snippetStart > 0) snippet = "...$snippet"
        if (snippetEnd < cleanText.length) snippet = "$snippet..."
        return snippet
    }

    /**
     * Pure helper: flatten a [buildTagHierarchy] tree into `pageId → tags`
     * (every full tag path the page belongs to, deduped and sorted). Cheap —
     * no I/O, no re-scan; the caller feeds it the cached hierarchy. Used by the
     * KnowledgeGraph tag chips/filters and the Phase 38 command palette.
     */
    fun flattenPageTags(hierarchy: List<TagNode>): Map<String, Set<String>> {
        if (hierarchy.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableSet<String>>(hierarchy.sumOf { it.matchingPageIds.size }.coerceAtLeast(8))
        fun visit(node: TagNode) {
            for (pageId in node.matchingPageIds) {
                out.getOrPut(pageId) { mutableSetOf() }.add(node.fullTagPath)
            }
            for (child in node.children) visit(child)
        }
        for (root in hierarchy) visit(root)
        return out.mapValues { (_, v) -> v.sorted().toSet() }
    }

    /**
     * Builds the hierarchical #tag tree (B2-DOS-11: recursion depth = number of
     * `/`-segments is attacker-controlled, so [MAX_TAG_TREE_DEPTH] caps both the
     * tree depth and the recursive [MutableTagNodeBuilder.toTagNode] walk).
     * Cached per unlock epoch, scanned set capped, cancellable.
     */
    suspend fun buildTagHierarchy(allPages: List<NotePageEntity>, importsRoot: File? = null): List<TagNode> {
        val epoch = synchronized(cacheLock) { cacheEpoch }
        val fingerprint = pagesFingerprint(allPages)
        val cached = synchronized(cacheLock) { tagHierarchyEntry }
        if (cached != null && cached.epoch == epoch && cached.fingerprint == fingerprint) {
            return cached.value
        }
        val result = computeTagHierarchy(allPages, epoch, importsRoot)
        synchronized(cacheLock) {
            if (cacheEpoch == epoch) {
                tagHierarchyEntry = EpochEntry(epoch, fingerprint, result)
                metricsTagRecomputes++
            }
        }
        return result
    }

    private suspend fun computeTagHierarchy(
        allPages: List<NotePageEntity>,
        epoch: Long,
        importsRoot: File?
    ): List<TagNode> =
        withContext(Dispatchers.Default) {
            val tagToPagesMap = mutableMapOf<String, MutableSet<String>>()

            val scanSet = allPages.take(MAX_SCAN_PAGES)
            for (page in scanSet) {
                currentCoroutineContext().ensureActive()
                val fullText = getFullTextForPage(page, importsRoot)
                val tags = extractTagsBounded(fullText, MAX_TAGS)
                for (tag in tags) {
                    if (tagToPagesMap.size >= MAX_TAGS) break
                    tagToPagesMap.getOrPut(tag) { mutableSetOf() }.add(page.id)
                }
            }

            if (synchronized(cacheLock) { cacheEpoch } != epoch || tagToPagesMap.isEmpty()) {
                return@withContext emptyList()
            }

            // Build hierarchical tree from tags with '/'; depth bounded.
            val rootNodes = mutableMapOf<String, MutableTagNodeBuilder>()

            for ((fullTag, pageIds) in tagToPagesMap) {
                currentCoroutineContext().ensureActive()
                val parts = fullTag.split('/').filter { it.isNotBlank() }.take(MAX_TAG_TREE_DEPTH)
                if (parts.isEmpty()) continue

                var currentMap = rootNodes
                var currentPath = ""

                for (i in parts.indices) {
                    val part = parts[i]
                    currentPath = if (currentPath.isEmpty()) part else "$currentPath/$part"

                    val node = currentMap.getOrPut(part) {
                        MutableTagNodeBuilder(name = part, fullTagPath = currentPath)
                    }
                    node.matchingPageIds.addAll(pageIds)
                    currentMap = node.children
                }
            }

            rootNodes.values.map { it.toTagNode() }.sortedBy { it.name }
        }

    /**
     * Cached per unlock epoch; used by KnowledgeGraphScreen so the force-directed
     * edge index is never re-scanned on repeated panel opens. Cancellable + capped.
     */
    suspend fun buildWikiLinkEdges(allPages: List<NotePageEntity>, importsRoot: File? = null): List<WikiLinkEdge> {
        val epoch = synchronized(cacheLock) { cacheEpoch }
        val fingerprint = pagesFingerprint(allPages)
        val cached = synchronized(cacheLock) { edgesEntry }
        if (cached != null && cached.epoch == epoch && cached.fingerprint == fingerprint) {
            return cached.value
        }
        val result = withContext(Dispatchers.Default) {
            val edgeList = mutableListOf<WikiLinkEdge>()
            // Prebuilt title->page lookup (first-in-list wins) so resolving each
            // link target is O(1) instead of the old per-link O(N) full-list scan.
            val pagesByTitle = HashMap<String, NotePageEntity>()
            for (p in allPages) {
                pagesByTitle.putIfAbsent(p.title.lowercase(), p)
                pagesByTitle.putIfAbsent(p.title.replace(".md", "").lowercase(), p)
            }
            for (page in allPages.take(MAX_SCAN_PAGES)) {
                currentCoroutineContext().ensureActive()
                val text = getFullTextForPage(page, importsRoot)
                val wikiLinks = extractWikiLinks(text)
                for (link in wikiLinks) {
                    val targetPage = pagesByTitle[link.targetTitle.lowercase()]
                    if (targetPage != null && targetPage.id != page.id) {
                        edgeList.add(WikiLinkEdge(page.id, targetPage.id))
                    }
                }
            }
            if (synchronized(cacheLock) { cacheEpoch } != epoch) {
                emptyList()
            } else {
                edgeList.distinct()
            }
        }
        synchronized(cacheLock) {
            if (cacheEpoch == epoch) {
                edgesEntry = EpochEntry(epoch, fingerprint, result)
                metricsEdgeRecomputes++
            }
        }
        return result
    }

    private class MutableTagNodeBuilder(
        val name: String,
        val fullTagPath: String,
        val matchingPageIds: MutableSet<String> = mutableSetOf(),
        val children: MutableMap<String, MutableTagNodeBuilder> = mutableMapOf()
    ) {
        fun toTagNode(): TagNode {
            val childNodes = children.values.map { it.toTagNode() }.sortedBy { it.name }
            return TagNode(
                name = name,
                fullTagPath = fullTagPath,
                noteCount = matchingPageIds.size,
                children = childNodes,
                matchingPageIds = matchingPageIds
            )
        }
    }
}
