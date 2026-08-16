package com.authorss81.noteflow

import com.authorss81.noteflow.data.repository.LruBoundedMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.HashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * B2-UI-3 (phase-73) behavioral + wiring tests for the unsynchronized shared
 * `lastSavedStrokeHash` + overlapping-debounce fix.
 *
 * Finding: `lastSavedStrokeHash` was a plain map shared by ALL pages, mutated
 * from the stroke-load path (no transaction) AND the stroke-save path (inside
 * `withTransaction`) on different coroutines with no synchronization. Two
 * concurrent saves for the same page could interleave the HashMap
 * read-modify-write (an older snapshot's hash commit landing last ⇒ the newer
 * stroke is silently lost / rows dropped by interleaved delete+upsert rounds)
 * or corrupt the map itself (ConcurrentModificationException). `EditorScreen`'s
 * dispose flush did not cancel/await the 1s debounced autosave, so a stale
 * snapshot could fire after the final flush and land last.
 *
 * What is provable on the pure JVM (no Room/SQLCipher): a faithful model of the
 * repository's diff + delete + upsert save path driven by the SAME primitives
 * the fix uses — `Collections.synchronizedMap(LruBoundedMap)` for the diff
 * cache and a per-page fair [Mutex] serializing every full-page write — proves
 * (1) the map survives heavy concurrent access without losing/corrupting
 * entries, (2) a later-issued (newer) full snapshot always commits last for the
 * same page, (3) the delete+upsert rounds can never interleave, (4) different
 * pages stay concurrent, and (5) the dispose-flush cancel+await semantics close
 * the stale-debounce-last race. The Android wiring is pinned at source level.
 */
class B2Ui3StrokeSaveConcurrencyTest {

    // ------------------------------------------------------------------
    // 1. The diff-cache map is thread-safe under concurrent access
    // ------------------------------------------------------------------

    @Test
    fun `the synchronized LRU diff cache survives concurrent access without losing or corrupting entries`() {
        val cache: MutableMap<String, Int> = Collections.synchronizedMap(
            LruBoundedMap<String, Int>(MAX_HASH_ENTRIES)
        )
        val writers = 8
        val perWriter = 2_000
        val failures = AtomicInteger(0)
        val threads = (0 until writers).map { w ->
            Thread {
                try {
                    repeat(perWriter) { i ->
                        val key = "stroke-$w-$i"
                        val value = w * 1_000_000 + i
                        // Mimic the save path's read-modify-write pattern: diff
                        // first (get), then commit (put); plus removes like the
                        // deleted-stroke sweep.
                        if ((i % 7) == 0) cache.remove(key)
                        if (i % 2 == 0) cache[key] = value
                        cache.containsKey(key)
                        if ((i % 5) == 0) cache[key] = value + 1
                    }
                } catch (t: Throwable) {
                    failures.incrementAndGet()
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // A race must never throw out of the map (no ConcurrentModificationException).
        assertEquals("no concurrent access may throw", 0, failures.get())

        // The writer pattern only ever PUTs keys where i%2==0 || i%5==0 (~1200 per
        // writer), well under the 10k cap — so the LRU never evicts and EVERY
        // written key must still be present: the concurrency may not lose entries.
        val expectedWritten = (0 until writers).sumOf { w ->
            (0 until perWriter).count { i -> i % 2 == 0 || i % 5 == 0 }
        }
        val size = cache.size
        assertTrue(
            "the cap is never exceeded even under contention ($size > $MAX_HASH_ENTRIES)",
            size <= MAX_HASH_ENTRIES
        )
        assertEquals(
            "no entry written under concurrency may be lost or duplicated ($size != $expectedWritten)",
            expectedWritten,
            size
        )
        // Every present value must be a value this test actually wrote — a
        // corrupted/lost entry shows up as a stale or foreign value.
        for ((key, value) in cache) {
            val w = key.removePrefix("stroke-").substringBefore("-").toInt()
            val i = key.removePrefix("stroke-").substringAfter("-").toInt()
            val base = w * 1_000_000 + i
            assertTrue(
                "no corrupted/stale value may survive ($key=$value)",
                value == base || value == base + 1
            )
        }
    }

    @Test
    fun `the synchronized wrapper stays safe under concurrent read and write through the LRU eviction path`() {
        val wrapped: MutableMap<String, Int> = Collections.synchronizedMap(LruBoundedMap<String, Int>(64))
        val done = CompletableDeferred<Unit>()
        val writer = Thread {
            for (i in 0 until 10_000) wrapped["k$i"] = i
            done.complete(Unit)
        }
        val reader = Thread {
            try {
                while (!done.isCompleted) {
                    for (i in 0 until 10_000) wrapped["k$i"]
                    wrapped["hot"] = 1
                    wrapped.remove("hot")
                }
            } catch (t: Throwable) {
                throw AssertionError("wrapped map must not throw under concurrent read/write", t)
            }
        }
        writer.start()
        reader.start()
        writer.join()
        reader.join()
        assertTrue("writer completed", done.isCompleted)
        assertTrue("LRU cap holds at the boundary", wrapped.size in 63..64)
    }

    // ------------------------------------------------------------------
    // 2. Per-page FIFO mutex: the newest full snapshot always lands last
    // ------------------------------------------------------------------

    @Test
    fun `a later issued newer snapshot always commits after an in-flight older one - newest stroke never silently dropped`() = runBlocking {
        val model = StrokePageModel()

        // snapshot N is a STRICT superset of snapshot N-1 (a user keeps drawing):
        val oldSnapshot = linkedMapOf("s1" to "c1", "s2" to "c2")
        val newSnapshot = linkedMapOf("s1" to "c1", "s2" to "c2", "s3" to "c3")

        // The OLDER save acquires the page lock first and stalls mid-write.
        val oldHoldsLock = CompletableDeferred<Unit>()
        val letOldFinish = CompletableDeferred<Unit>()
        val oldJob = launch(Dispatchers.Default) {
            model.saveSnapshot(
                "p1", oldSnapshot, label = "old",
                onLockHeld = { oldHoldsLock.complete(Unit) },
                gateBeforeCommit = letOldFinish // simulate a slow disk/encryption write
            )
        }
        oldHoldsLock.await()

        // The NEWER save is issued AFTER while the older write is still in flight.
        val newJob = launch(Dispatchers.Default) {
            model.saveSnapshot("p1", newSnapshot, label = "new")
        }
        yield()
        letOldFinish.complete(Unit)

        oldJob.join()
        newJob.join()

        // The FIFO per-page mutex serializes: the older commit lands FIRST, the
        // newer commit LAST — the newest stroke (s3) is never silently dropped.
        assertEquals("the older snapshot commits before the newer one", listOf("old", "new"), model.commitOrder)
        assertEquals("the newest stroke survives the interleaved older write", "c3", model.db["s3"])
        assertEquals("the full newest snapshot is the final on-disk state", newSnapshot, model.db)
        assertEquals("the diff cache reflects the newest commit", newSnapshot.size, model.hashCacheSize)
    }

    @Test
    fun `two concurrent same-page saves never interleave the delete plus upsert rounds`() = runBlocking {
        val model = StrokePageModel()

        // snapshot A added s2; snapshot B (newer) changed s2 — the dangerous
        // interleave would be A's delete+reinsert running INSIDE B's transaction.
        val snapshotA = linkedMapOf("s1" to "c1", "s2" to "cA")
        val snapshotB = linkedMapOf("s1" to "c1", "s2" to "cB")

        val aHolds = CompletableDeferred<Unit>()
        val letAFinalize = CompletableDeferred<Unit>()
        val a = launch(Dispatchers.Default) {
            model.saveSnapshot(
                "p1", snapshotA, label = "A",
                onLockHeld = { aHolds.complete(Unit) },
                gateBeforeCommit = letAFinalize
            )
        }
        aHolds.await()
        val b = launch(Dispatchers.Default) { model.saveSnapshot("p1", snapshotB, label = "B") }
        yield()
        letAFinalize.complete(Unit)
        a.join()
        b.join()

        // Under the per-page lock B can only ever run entirely after A: no
        // partial delete+upsert interleave can drop rows and the newer content
        // for s2 wins.
        assertEquals("s1 survives both rounds", "c1", model.db["s1"])
        assertEquals("the newer content for s2 wins - nothing dropped", "cB", model.db["s2"])
        assertEquals("both strokes present", setOf("s1", "s2"), model.db.keys)
    }

    @Test
    fun `different pages are not serialized by the per-page lock`() = runBlocking {
        val model = StrokePageModel()

        val p1Holds = CompletableDeferred<Unit>()
        val releaseP1 = CompletableDeferred<Unit>()
        val p1 = launch(Dispatchers.Default) {
            model.saveSnapshot(
                "p1", linkedMapOf("s1" to "c1"), label = "p1",
                onLockHeld = { p1Holds.complete(Unit) },
                gateBeforeCommit = releaseP1
            )
        }
        p1Holds.await()

        // While p1 stalls holding its own lock, p2 must be able to save fully.
        val p2 = launch(Dispatchers.Default) { model.saveSnapshot("p2", linkedMapOf("x1" to "x"), label = "p2") }
        p2.join()
        assertEquals("page 2 saved while page 1 held its lock", "x", model.db["x1"])
        releaseP1.complete(Unit)
        p1.join()
        assertEquals("page 1 saved too", "c1", model.db["s1"])
    }

    // ------------------------------------------------------------------
    // 3. Dispose flush: cancel the pending debounce, await it, then flush
    // ------------------------------------------------------------------

    @Test
    fun `dispose flush cancels a pending debounce so a stale snapshot can never fire after the final flush`() = runBlocking {
        val persists = mutableListOf<String>()
        var staleFired = false

        // The pending debounce would, after its delay, persist the STALE snapshot:
        val pending = launch(Dispatchers.Default) {
            delay(60_000) // the debounce window — never reached before dispose
            staleFired = true
            persists += "stale"
        }

        // Editor leaves composition while the debounce is still inside its window;
        // the dispose flush cancels then awaits it, then persists the FINAL snapshot:
        disposeEditorPageFlush("p1", mapOf("s1" to "newest"), pending) { persists += it.values.joinToString() }
        assertFalse("the stale debounce never fired or persisted", staleFired)
        assertEquals("exactly the newest snapshot persisted", listOf("newest"), persists)
    }

    @Test
    fun `dispose flush awaits an already-started debounce write before persisting the newest`() = runBlocking {
        val model = StrokePageModel()
        val staleInFlightStarted = CompletableDeferred<Unit>()
        val letStaleFinish = CompletableDeferred<Unit>()

        // The debounce ALREADY fired — its write (older snapshot) is in flight:
        val inFlight = launch(Dispatchers.Default) {
            model.saveSnapshot(
                "p1", linkedMapOf("s1" to "old"), label = "stale",
                onLockHeld = { staleInFlightStarted.complete(Unit) },
                gateBeforeCommit = letStaleFinish // slow disk write still running
            )
        }
        staleInFlightStarted.await()

        // Dispose fires while the stale write is mid-flight; its cancel+join must
        // settle the stale write BEFORE the final flush is issued:
        val dispose = launch(Dispatchers.Default) {
            disposeEditorPageFlush("p1", linkedMapOf("s1" to "new", "s2" to "new2"), inFlight) {
                model.saveSnapshot("p1", it, label = "flush")
            }
        }
        yield()
        letStaleFinish.complete(Unit)
        inFlight.join()
        dispose.join()

        // The final on-disk state is the newest snapshot — never the stale one.
        assertEquals("newest content lands last", mapOf("s1" to "new", "s2" to "new2"), model.db)
    }

    @Test
    fun `dispose flush with no pending debounce simply flushes the newest snapshot`() = runBlocking {
        val persists = mutableListOf<String>()
        disposeEditorPageFlush("p3", mapOf("s1" to "fresh"), null) { persists += it.values.joinToString() }
        assertEquals(listOf("fresh"), persists)
    }

    // ------------------------------------------------------------------
    // 4. Source-level wiring pins (no Room/Compose needed)
    // ------------------------------------------------------------------

    @Test
    fun `NoteRepository diff cache is synchronized and every page write acquires the per-page lock`() {
        val source = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/data/repository/NoteRepository.kt").readText()

        // The shared stroke-hash diff cache is no longer a plain mutableMapOf.
        assertTrue(
            "lastSavedStrokeHash must be backed by a synchronized map",
            source.contains("Collections.synchronizedMap(LruBoundedMap")
        )
        assertTrue(
            "the per-page lock store must exist (ConcurrentHashMap of Mutex)",
            source.contains("private val pageSaveLocks = ConcurrentHashMap<String, Mutex>()")
        )

        // Every full-page write path is serialized per page via the SAME lock.
        for (fn in listOf("saveStrokesForPage", "saveMediaEmbedsForPage", "saveLayersForPage")) {
            val block = source.substringAfter("suspend fun $fn", "END")
                .substringBefore("\n    suspend fun ", "END")
            assertTrue("$fn must acquire the per-page lock", block.contains("pageSaveLocks.computeIfAbsent(pageId) { Mutex() }"))
            assertTrue("$fn must run its transaction under the lock", block.contains("lock.withLock {"))
        }
    }

    @Test
    fun `EditorScreen dispose flush cancels and nulls the debounce job before the ViewModel flush`() {
        val source = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt").readText()

        val dispose = source.substringAfter("DisposableEffect(page.id) {", "END")
            .substringBefore("\n    // PDF Page Count", "END")
        assertTrue("dispose must capture the pending debounce job", dispose.contains("val pending = saveJob"))
        assertTrue("dispose must null the debounce slot", dispose.contains("saveJob = null"))
        assertTrue(
            "dispose must route through the VM cancel+await+flush helper",
            dispose.contains("viewModel.disposeEditorPageFlush(page.id, strokes, stickyNotes, mediaEmbeds, layers, pending)")
        )
    }

    @Test
    fun `ViewModel autosave is suspended and dispose flush cancels then joins the debounce before flushing`() {
        val source = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt").readText()

        // The debounced autosave must be suspended so the write runs inline in
        // the debounce job (cancellable + awaitable by the dispose flush).
        assertTrue("autosaveStrokes must be suspended", source.contains("suspend fun autosaveStrokes("))

        val dispose = source.substringAfter("fun disposeEditorPageFlush(", "END")
            .substringBefore("\n    suspend fun autosaveStrokes(", "END")
        assertTrue("dispose flush must cancel the pending debounce", dispose.contains("pendingDebounce?.cancel()"))
        assertTrue("dispose flush must await settlement of the debounce", dispose.contains("pendingDebounce?.join()"))
        assertTrue(
            "dispose flush must persist the final snapshot after the await",
            dispose.contains("flushEditorPageSave(pageId, strokes, stickyNotes, embeds, layers)")
        )
    }

    // ------------------------------------------------------------------
    // Model of the repository save path driven by the fix's primitives
    // ------------------------------------------------------------------

    private class StrokePageModel {
        private val pageSaveLocks = ConcurrentHashMap<String, Mutex>()
        val db = Collections.synchronizedMap(HashMap<String, String>())
        private val lastSavedHash: MutableMap<String, Int> =
            Collections.synchronizedMap(LruBoundedMap<String, Int>(256))
        val commitOrder = Collections.synchronizedList(mutableListOf<String>())
        private fun strokeHash(id: String, content: String): Int = id.hashCode() * 31 + content.hashCode()

        val hashCacheSize: Int get() = lastSavedHash.size

        /**
         * Faithful model of NoteRepository.saveStrokesForPage's
         * lock + diff + delete + upsert. [onLockHeld] fires right after the
         * per-page lock is acquired (still inside the critical section) and
         * [gateBeforeCommit] stalls the write BEFORE the commit while STILL
         * holding the lock — letting tests simulate a slow in-flight save.
         */
        suspend fun saveSnapshot(
            pageId: String,
            strokes: LinkedHashMap<String, String>,
            label: String,
            onLockHeld: (() -> Unit)? = null,
            gateBeforeCommit: CompletableDeferred<Unit>? = null
        ) {
            val lock = pageSaveLocks.computeIfAbsent(pageId) { Mutex() }
            lock.withLock {
                onLockHeld?.invoke()
                gateBeforeCommit?.await()

                val storedIds = db.keys.toHashSet()
                val incoming = strokes.keys.toHashSet()
                val removed = storedIds - incoming
                if (removed.isNotEmpty()) {
                    removed.forEach { db.remove(it); lastSavedHash.remove(it) }
                }
                val changed = strokes.filter { (id, content) -> strokeHash(id, content) != lastSavedHash[id] }
                for ((id, content) in changed) {
                    db[id] = content
                    lastSavedHash[id] = strokeHash(id, content)
                }
                commitOrder += label
            }
        }
    }

    /** Faithful model of NoteflowViewModel.disposeEditorPageFlush. */
    private suspend fun disposeEditorPageFlush(
        pageId: String,
        newest: Map<String, String>,
        pending: Job?,
        persist: suspend (LinkedHashMap<String, String>) -> Unit
    ) {
        pending?.cancel()
        pending?.join()
        persist(LinkedHashMap(newest))
    }

    private fun repoRoot(): String {
        // The test runs with cwd = <module>/ (app); walk up to the repo root.
        // Same convention as SecurityCryptoAbsenceTest / PluginBytecodeIsolationTest.
        val cwd = java.io.File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        while (dir.parentFile != null && !isRepoRoot(dir)) {
            dir = dir.parentFile ?: break
        }
        return dir.absolutePath
    }

    private fun isRepoRoot(dir: java.io.File): Boolean =
        java.io.File(dir, "gradle/libs.versions.toml").isFile && java.io.File(dir, "app").isDirectory

    private companion object {
        const val MAX_HASH_ENTRIES = 10_000
    }
}