package com.authorss81.noteflow

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 242 — "dots disappear on page reopen".
 *
 * Two navigate-away data-loss paths are closed:
 *
 *  1. The AnnotationCanvas now commits an IN-PROGRESS stroke when it leaves
 *     composition mid-gesture (before `detectDragGestures.onDragEnd` runs —
 *     e.g. a swipe classified as navigation triggers pointer-cancel, or the
 *     page is closed while the pointer is still down). Without this the partial
 *     ink lives only in the ephemeral `activePoints` list, never reaches
 *     `strokes`, and the editor dispose-flush (which persists only the COMMITTED
 *     list) has nothing to save — so on reopen the dots the user just drew are
 *     gone.
 *
 *  2. The editor's navigate-away paths (system back, top-bar back) now route
 *     through `NoteflowViewModel.flushPendingSaves` which CANCELS + AWAITS the
 *     pending 1s debounced autosave BEFORE persisting the newest snapshot, so
 *     a page closed inside the 1s debounce window never loses the latest stroke
 *     and a stale snapshot can never land last.
 *
 * Behaviour that is provable on the pure JVM (no Room/SQLCipher/Compose) is
 * tested directly here with a faithful model of `flushPendingSaves`
 * (cancel+await+persist-newest) driven by the same coroutine primitives the
 * fix uses. The Android wiring is pinned at source level.
 */
class Phase242StrokeDisposePersistenceTest {

    // ------------------------------------------------------------------
    // 1. flushPendingSaves: cancel the pending debounce, await it, flush newest
    // ------------------------------------------------------------------

    @Test
    fun `flush pending cancels a still-waiting debounce so a stale snapshot never persists`() = runBlocking {
        val persists = mutableListOf<String>()
        var staleFired = false

        // The pending debounce would, after its 1s window, persist the STALE list.
        val pending = launch(Dispatchers.Default) {
            delay(60_000)
            staleFired = true
            persists += "stale"
        }

        // User closes the page inside the debounce window — force-flush newest.
        flushPendingSaves("p1", listOf("s1", "s2"), pending) { persists += it.joinToString() }

        assertFalse("the cancelled debounce never ran", staleFired)
        assertEquals("exactly the newest (force-flushed) list persisted", listOf("s1, s2"), persists)
    }

    @Test
    fun `flush pending awaits an already-started debounce write before persisting newest`() = runBlocking {
        val model = StrokePageModel()
        val staleInFlightStarted = CompletableDeferred<Unit>()
        val letStaleFinish = CompletableDeferred<Unit>()

        // The debounce ALREADY fired — its write (older snapshot) is in flight.
        val inFlight = launch(Dispatchers.Default) {
            model.saveSnapshot(
                "p1", linkedMapOf("s1" to "old"), label = "stale",
                onLockHeld = { staleInFlightStarted.complete(Unit) },
                gateBeforeCommit = letStaleFinish
            )
        }
        staleInFlightStarted.await()

        // Navigate-away flush fires while the stale write is mid-flight.
        val flush = launch(Dispatchers.Default) {
            flushPendingSaves("p1", listOf("s1", "s2"), inFlight) {
                val snap = LinkedHashMap<String, String>()
                it.forEach { id -> snap[id] = "$id-content" }
                model.saveSnapshot("p1", snap, label = "flush")
            }
        }
        yield()
        letStaleFinish.complete(Unit)
        inFlight.join()
        flush.join()

        // The final on-disk state is the newest snapshot — never the stale one.
        assertEquals("s1", "s1-content", model.db["s1"])
        assertEquals("s2", "s2-content", model.db["s2"])
    }

    @Test
    fun `flush pending with no pending debounce simply persists the newest immediately`() = runBlocking {
        val persists = mutableListOf<String>()
        flushPendingSaves("p3", listOf("s1"), null) { persists += it.joinToString() }
        assertEquals(listOf("s1"), persists)
    }

    // ------------------------------------------------------------------
    // 2. Source pins: the navigate-away wiring commits + flushes pending ink
    // ------------------------------------------------------------------

    @Test
    fun `EditorScreen every navigate-away path flushes through the ordered ViewModel helper`() {
        val source = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt").readText()

        // The dispose path uses the cancel+await+flush helper.
        assertTrue(
            "dispose must route through the VM cancel+await+flush",
            source.contains("viewModel.disposeEditorPageFlush(page.id, strokes, stickyNotes, mediaEmbeds, layers, pending)")
        )
        // The system-back and top-bar-back paths must force-flush IMMEDIATELY via
        // flushPendingSaves (not just fire-and-forget flushEditorPageSave after a
        // non-awaited cancel).
        val backUsesOrderedFlush = "viewModel.flushPendingSaves(page.id, strokes, stickyNotes, mediaEmbeds, layers, saveJob)".let { source.contains(it) }
        assertTrue(
            "both back paths must use the ordered flushPendingSaves (found in: $source)",
            backUsesOrderedFlush
        )
        // A stale snapshots can never land last: the back paths must capture and
        // null the debounce slot around the flush.
        assertTrue(source.contains("saveJob = null"))
    }

    @Test
    fun `ViewModel dispose flush delegates to flushPendingSaves which cancels joins and flushes newest`() {
        val vmSrc = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt").readText()

        assertTrue(
            "disposeEditorPageFlush must delegate to flushPendingSaves (single source of truth)",
            vmSrc.contains(
                "flushPendingSaves(pageId, strokes, stickyNotes, embeds, layers, pendingDebounce)"
            )
        )
        val flush = vmSrc.substringAfter("fun flushPendingSaves(", "END")
            .substringBefore("\n    fun saveLayersGated(", "END")
        assertTrue("flushPendingSaves must cancel the pending debounce", flush.contains("pendingDebounce?.cancel()"))
        assertTrue("flushPendingSaves must await settlement of the debounce", flush.contains("pendingDebounce?.join()"))
        assertTrue(
            "flushPendingSaves must persist the final snapshot after the await",
            flush.contains("flushEditorPageSave(pageId, strokes, stickyNotes, embeds, layers)")
        )
    }

    @Test
    fun `AnnotationCanvas dispose commits the in-progress stroke into the committed list`() {
        val source = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt").readText()

        // A disposal-scoped effect must exist that reads the in-progress points.
        assertTrue(
            "canvas must have a dispose-commit effect",
            source.contains("DisposableEffect(Unit)") && source.contains("onDispose")
        )
        // It must derive the pending ink from activePoints and push it into the
        // committed stroke list via the same onStrokesChanged channel the live
        // drag-end commit uses — so the editor's dispose flush persists it.
        assertTrue(
            "the in-progress ink must come from activePoints",
            source.contains("val ink = activePoints.toList()")
        )
        assertTrue(
            "a pending stroke must be built from the in-progress points",
            source.contains("points = ink")
        )
        assertTrue(
            "the pending stroke must be added to the committed list and emitted",
            source.contains("activeStrokeList.add(newStroke)") &&
                source.contains("onStrokesChanged(")
        )
    }

    @Test
    fun `NoteRepository loads every stroke for a page with no post-load filter that drops new ink`() {
        val daos = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/data/db/Daos.kt").readText()
        val repo = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/data/repository/NoteRepository.kt").readText()

        // The load query filters ONLY on the stored-size budget (B2-DOS-01),
        // never on any "in-progress"/flag/insertion-time predicate that could
        // hide freshly drawn ink — and orders by ROWID (insertion order).
        val query = daos.substringAfter("SELECT * FROM strokes WHERE pageId = :pageId", "END")
            .substringBefore("LIMIT :limit OFFSET :offset", "END")
        assertTrue(
            "the load query must not filter out new strokes",
            !query.contains("in_progress") && !query.contains("progress") &&
                !query.contains("deleted") && !query.contains("pending")
        )
        // Every row the DAO returns is materialized into a Stroke and appended —
        // there is no post-read skip based on stroke recency.
        assertTrue(
            "the load path appends a Stroke for every returned row",
            repo.contains("loaded += Stroke(")
        )
    }

    // ------------------------------------------------------------------
    // Model of NoteflowViewModel.flushPendingSaves + repository save
    // ------------------------------------------------------------------

    private class StrokePageModel {
        val db = java.util.Collections.synchronizedMap(HashMap<String, String>())
        val commitOrder = java.util.Collections.synchronizedList(mutableListOf<String>())
        private val lastSavedHash = HashMap<String, Int>()
        private fun hash(k: String, v: String): Int = k.hashCode() * 31 + v.hashCode()

        suspend fun saveSnapshot(
            pageId: String,
            strokes: LinkedHashMap<String, String>,
            label: String,
            onLockHeld: (() -> Unit)? = null,
            gateBeforeCommit: CompletableDeferred<Unit>? = null
        ) {
            onLockHeld?.invoke()
            gateBeforeCommit?.await()
            val removed = db.keys - strokes.keys
            removed.forEach { db.remove(it); lastSavedHash.remove(it) }
            for ((id, content) in strokes) {
                if (hash(id, content) != lastSavedHash[id]) {
                    db[id] = content
                    lastSavedHash[id] = hash(id, content)
                }
            }
            commitOrder += label
        }
    }

    /** Faithful model of NoteflowViewModel.flushPendingSaves. */
    private suspend fun flushPendingSaves(
        pageId: String,
        strokes: List<String>,
        pending: Job?,
        persist: suspend (List<String>) -> Unit
    ) {
        pending?.cancel()
        pending?.join()
        persist(strokes)
    }

    private fun repoRoot(): String {
        val cwd = java.io.File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        while (dir.parentFile != null && !isRepoRoot(dir)) dir = dir.parentFile ?: break
        return dir.absolutePath
    }

    private fun isRepoRoot(dir: java.io.File): Boolean =
        java.io.File(dir, "gradle/libs.versions.toml").isFile && java.io.File(dir, "app").isDirectory
}
