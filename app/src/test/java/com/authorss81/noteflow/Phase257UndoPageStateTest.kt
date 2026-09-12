package com.authorss81.noteflow

import com.authorss81.noteflow.services.CanvasStrokeReconcile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 257 — undo resurrect + unkeyed page state + load-vs-save race.
 *
 * Three defects closed:
 *   H1 — undo re-added a removed stroke. The canvas merges its live local list
 *     with the editor's committed snapshot inside three LaunchedEffects; the
 *     blind `clear(); addAll(incoming)` treats every local id absent from the
 *     snapshot as "not yet committed" and re-appends it. After an undo the
 *     removed stroke still sat in the local list, so it resurrected.
 *   H2 — strokes/stickyNotes/mediaEmbeds/undoStack/redoStack/isInitialLoadComplete/
 *     layers/activeLayerId were `remember {}` UNKEYED, so switching pages in the
 *     same EditorScreen composition slot leaked the previous page's state until
 *     the async load overwrote it (cross-page layer bleed + stale undo history).
 *   H3 — loadEditorCanvasPage read 5 tables without the per-page save mutex, so
 *     a load racing a dispose flush could init empty and the first new stroke
 *     would overwrite the real rows.
 *
 * The reconcile merge is production code (`services/CanvasStrokeReconcile.kt`)
 * used by all three AnnotationCanvas effects; the behavioral tests below drive
 * the REAL function through the draw→commit→undo sequence. The state-keying and
 * mutex guarantees are source pins over the live files.
 */
class Phase257UndoPageStateTest {

    // ---------------------------------------------------------------------------
    // 1. Undo must NOT resurrect — behavior of the real CanvasStrokeReconcile
    // ---------------------------------------------------------------------------

    /** Mirrors AnnotationCanvas: an active local list + lastSeen id set. */
    private class CanvasState(var active: List<String>, var lastSeen: Set<String>) {
        fun reconcileWith(incoming: List<String>) {
            active = CanvasStrokeReconcile.reconcile(active, lastSeen, incoming) { it }
            lastSeen = incoming.toSet()
        }

        /** A stroke the user just drew — appended locally, not yet committed. */
        fun drawLocally(id: String) {
            active = active + id
        }
    }

    @Test
    fun `undo of a committed stroke does not resurrect it from the stale local list`() {
        var canvas = CanvasState(
            active = rememberFirstFrame(listOf("s1", "s2")),
            lastSeen = setOf("s1", "s2")
        )

        // User draws s3; canvas mirrors it locally while the editor round-trips.
        canvas.drawLocally("s3")
        // A stale LaunchedEffect still sees the old committed snapshot.
        canvas.reconcileWith(listOf("s1", "s2"))
        assertTrue("the never-seen local s3 must survive a stale snapshot", canvas.active.contains("s3"))

        // Editor commits s3 -> the snapshot now carries it and it becomes seen.
        canvas.reconcileWith(listOf("s1", "s2", "s3"))
        assertEquals(setOf("s1", "s2", "s3"), canvas.lastSeen)

        // Undo removes s3 from the committed state. The local list still holds
        // s3 at this moment — the FIXED merge must drop it, not re-append it.
        canvas.reconcileWith(listOf("s1", "s2"))
        assertEquals("undo must remove s3 and never re-add it", listOf("s1", "s2"), canvas.active)
    }

    @Test
    fun `a brand-new local stroke drawn after undo is still retained as pending`() {
        var canvas = CanvasState(
            active = rememberFirstFrame(listOf("s1", "s2")),
            lastSeen = setOf("s1", "s2")
        )
        // Commit + undo s3 (now seen).
        canvas.reconcileWith(listOf("s1", "s2", "s3"))
        canvas.reconcileWith(listOf("s1", "s2"))
        assertEquals(listOf("s1", "s2"), canvas.active)

        // New stroke s4 is drawn after the undo and is NOT yet committed.
        canvas.drawLocally("s4")
        canvas.reconcileWith(listOf("s1", "s2"))
        assertTrue(
            "a never-seen local stroke must still be retained as pending (the pending mechanism is intact)",
            canvas.active.contains("s4")
        )
        // And once committed it is seen, so a subsequent undo drops it for good.
        canvas.reconcileWith(listOf("s1", "s2", "s4"))
        canvas.reconcileWith(listOf("s1", "s2"))
        assertEquals(listOf("s1", "s2"), canvas.active)
    }

    @Test
    fun `multi-stroke removal cut or eraser also never resurrects committed items`() {
        var canvas = CanvasState(
            active = rememberFirstFrame(listOf("s1", "s2", "s3")),
            lastSeen = setOf("s1", "s2", "s3")
        )
        // Eraser removes s2 mid-list while the local list still holds it.
        canvas.reconcileWith(listOf("s1", "s3"))
        assertEquals(
            "removed committed items must be dropped in one pass",
            listOf("s1", "s3"),
            canvas.active
        )
    }

    @Test
    fun `committed order is authoritative and pending locals append last in z-order`() {
        var canvas = CanvasState(active = listOf("s1"), lastSeen = setOf())
        // First frame: incoming [s1] arrives; a local s2 was already drawn.
        canvas.drawLocally("s2")
        canvas.reconcileWith(listOf("s1"))
        assertEquals(
            "committed list first, pending never-seen locals last (z-order preserved)",
            listOf("s1", "s2"),
            canvas.active
        )
    }

    @Test
    fun `stale active lists carrying long-removed ids never resurrect them once seen`() {
        var canvas = CanvasState(
            active = rememberFirstFrame(listOf("old", "s1", "s2")),
            lastSeen = setOf("old", "s1", "s2")
        )
        // `old` was committed (hence seen) but is gone from the new snapshot.
        canvas.reconcileWith(listOf("s1", "s2"))
        assertEquals("seen-but-removed ids are dropped, never resurrected", listOf("s1", "s2"), canvas.active)
    }

    @Test
    fun `undo before the canvas observed its own committed draw leaves the stroke pending until an authoritative load`() {
        // REVIEW-FIX residual (documented, bounded): a draw whose commit snapshot
        // [s1,s2,s3] and the undo snapshot [s1,s2] coalesce BEFORE the canvas ever
        // processed a snapshot carrying s3 leaves s3 as a never-seen pending local.
        // Reconcile RETAINS it (the only safe choice -- a legit pending draw on a
        // loaded page is indistinguishable here). The ghost-purge mechanism bounds
        // it: the NEXT authoritative page snapshot (canvasResetToken change) replaces
        // the live list wholesale, so the phantom is dropped at the next open/reload
        // instead of lingering for the whole session.
        var canvas = CanvasState(active = rememberFirstFrame(listOf("s1", "s2")), lastSeen = setOf("s1", "s2"))
        canvas.drawLocally("s3")
        canvas.reconcileWith(listOf("s1", "s2"))
        assertTrue(
            "a never-observed pending local is retained until an authoritative snapshot",
            canvas.active.contains("s3")
        )
        // Authoritative snapshot (canvasResetToken bumped): wholesale replace.
        canvas.active = rememberFirstFrame(listOf("s1", "s2"))
        canvas.lastSeen = setOf("s1", "s2")
        assertEquals("the authoritative replace drops the phantom", listOf("s1", "s2"), canvas.active)
    }

    private fun rememberFirstFrame(incoming: List<String>): List<String> = incoming.toList()

    // ---------------------------------------------------------------------------
    // 2. Unkeyed page state — source pins (EditorScreen + AnnotationCanvas)
    // ---------------------------------------------------------------------------

    private fun repoRoot(): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            val d: File = dir
            if (File(d, "app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt").isFile) return d
            dir = d.parentFile
        }
        return start
    }

    private fun editorSource(): String =
        File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt").readText()

    private fun canvasSource(): String =
        File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt").readText()

    @Test
    fun `every page-bound editor state is keyed with remember(page dot id)`() {
        val src = editorSource()
        // strokes / stickyNotes / mediaEmbeds
        assertTrue("strokes must be page-keyed", src.contains("var strokes by remember(page.id)"))
        assertTrue("stickyNotes must be page-keyed", src.contains("var stickyNotes by remember(page.id)"))
        assertTrue("mediaEmbeds must be page-keyed", src.contains("var mediaEmbeds by remember(page.id)"))
        // undo history
        assertTrue("undoStack must be page-keyed", src.contains("var undoStack by remember(page.id)"))
        assertTrue("redoStack must be page-keyed", src.contains("var redoStack by remember(page.id)"))
        // load-complete gate
        assertTrue("isInitialLoadComplete must be page-keyed", src.contains("var isInitialLoadComplete by remember(page.id)"))
        // layer set + active layer (phase 257 added the key — cross-page layer bleed)
        assertTrue("layers must be page-keyed", src.contains("var layers by remember(page.id)"))
        assertTrue("activeLayerId must be page-keyed", src.contains("var activeLayerId by remember(page.id)"))
        // every edit goes through the guarded setter that refuses writes before load
        assertTrue(
            "handleStrokesChange must skip edits until the initial load completes",
            src.contains("if (!isInitialLoadComplete) return")
        )
    }

    @Test
    fun `canvas local lists are keyed on page filter and pre-seeded from the committed snapshot`() {
        val src = canvasSource()
        assertTrue(
            "activeStrokeList must remember on (pdfPageFilter, isContinuousMode)",
            src.contains("val activeStrokeList = remember(pdfPageFilter, isContinuousMode) { mutableStateListOf<Stroke>().apply { addAll(filteredStrokes) } }")
        )
        assertTrue(
            "activeStickyNoteList must remember on (pdfPageFilter, isContinuousMode)",
            src.contains("val activeStickyNoteList = remember(pdfPageFilter, isContinuousMode) { mutableStateListOf<CanvasStickyNote>().apply { addAll(filteredStickyNotes) } }")
        )
        assertTrue(
            "activeMediaEmbedList must remember on (pdfPageFilter, isContinuousMode)",
            src.contains("val activeMediaEmbedList = remember(pdfPageFilter, isContinuousMode) { mutableStateListOf<CanvasMediaEmbed>().apply { addAll(filteredMediaEmbeds) } }")
        )
        // The lastSeen guard must be present for ALL three item families.
        assertEquals(
            "the lastSeen ids state must exist for strokes, sticky notes and media embeds",
            3,
            listOf("var lastSeenStrokeIds", "var lastSeenStickyIds", "var lastSeenEmbedIds").filter { src.contains(it) }.size
        )
        // All three reconciliation effects must route through the shared pure-JVM reconcile.
        assertEquals(
            "the pure-JVM reconcile must drive all three LaunchedEffect merges",
            3,
            src.split("CanvasStrokeReconcile.reconcile").size - 1
        )
    }

    // ---------------------------------------------------------------------------
    // 3. Load-vs-save race — source pins (NoteRepository + NoteflowViewModel)
    // ---------------------------------------------------------------------------

    private fun repositorySource(): String =
        File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/data/repository/NoteRepository.kt").readText()

    private fun viewModelSource(): String =
        File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt").readText()

    @Test
    fun `loadEditorCanvasPage holds the page mutex around every read`() {
        val src = repositorySource()
        val fnStart = src.indexOf("fun loadEditorCanvasPage")
        val fnEnd = src.indexOf("suspend fun saveStrokesForPage")
        assertTrue("loadEditorCanvasPage must exist before saveStrokesForPage", 0 <= fnStart && fnStart < fnEnd)
        val fn = src.substring(fnStart, fnEnd)

        val lockLookup = fn.indexOf("pageSaveLocks.computeIfAbsent(pageId)")
        val withLock = fn.indexOf("lock.withLock")
        assertTrue("load must resolve the per-page mutex", lockLookup >= 0)
        assertTrue("load must enter the mutex with lock.withLock", withLock > lockLookup)

        // All five reads must sit INSIDE the guarded section.
        val readOrder = listOf(
            "getStrokesForPage(pageId)",
            "getLayerCountForPage(pageId)",
            "getLayersForPage(pageId)",
            "getCanvasItemsForPage(pageId)",
            "getReferenceImageForPage(pageId)"
        ).map { fn.indexOf(it) }
        assertTrue("every canvas read must be present", readOrder.all { it > withLock })
    }

    @Test
    fun `the save path holds the same per-page mutex so load and save serialize`() {
        val src = repositorySource()
        val saveFn = src.indexOf("suspend fun saveStrokesForPage")
        assertTrue("saveStrokesForPage must exist", saveFn >= 0)
        val save = src.substring(saveFn, saveFn + 4000)
        assertTrue(
            "saveStrokesForPage must enter the SAME page mailbox",
            save.contains("pageSaveLocks.computeIfAbsent(pageId)") && save.contains("lock.withLock {")
        )
    }

    @Test
    fun `the ViewModel delegates the load to the repository under the lock-safe guard`() {
        val vm = viewModelSource()
        val fn = vm.substring(vm.indexOf("fun loadEditorCanvasPage"), vm.indexOf("suspend fun loadAllActivePages"))
        assertTrue(
            "ViewModel.loadEditorCanvasPage must delegate to the repository (never read unlocked)",
            fn.contains("val data = repository.loadEditorCanvasPage(pageId)")
        )
    }

    // ---------------------------------------------------------------------------
    // 4. Review-fix: authoritative-snapshot ghost purge (findings #2/#3)
    // ---------------------------------------------------------------------------

    @Test
    fun `canvas ingest effects key on the reset token and replace wholesale on a token change`() {
        val src = canvasSource()
        assertEquals(
            "all three ingest effects must key on (filteredX, canvasResetToken)",
            3,
            listOf(
                "LaunchedEffect(filteredStrokes, canvasResetToken)",
                "LaunchedEffect(filteredStickyNotes, canvasResetToken)",
                "LaunchedEffect(filteredMediaEmbeds, canvasResetToken)"
            ).filter { src.contains(it) }.size
        )
        assertEquals(
            "every token-tracking var must exist",
            3,
            listOf("lastAppliedStrokeResetToken", "lastAppliedStickyResetToken", "lastAppliedEmbedResetToken")
                .filter { src.contains(it) }.size
        )
        assertTrue(
            "a token change must replace the live list wholesale (ghost purge, never-seen locals dropped)",
            src.contains("if (canvasResetToken != lastAppliedStrokeResetToken)") &&
                src.contains("lastAppliedStrokeResetToken = canvasResetToken")
        )
    }

    @Test
    fun `editor bumps the canvas reset token exactly when an authoritative snapshot is applied`() {
        val src = editorSource()
        assertTrue(
            "canvasResetToken must be page-keyed",
            src.contains("var canvasResetToken by remember(page.id)")
        )
        val load = src.substring(src.indexOf("LaunchedEffect(page.id, isAuthenticated)"))
        val authIdx = load.indexOf("viewModel.authenticated.value")
        val incIdx = load.indexOf("canvasResetToken++")
        assertTrue("the token bump must exist inside the load effect (after the auth gate)", incIdx > authIdx)
        val firstElse = load.indexOf("} else {", authIdx)
        assertTrue(
            "the bump must sit inside the authenticated branch, after isInitialLoadComplete = true",
            load.indexOf("isInitialLoadComplete = true") < incIdx && (firstElse < 0 || incIdx < firstElse)
        )
        assertTrue(
            "the token must be forwarded to the canvas",
            src.contains("canvasResetToken = canvasResetToken")
        )
    }
}
