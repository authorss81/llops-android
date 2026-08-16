package com.authorss81.noteflow

import com.authorss81.noteflow.services.MarkdownBodySaveCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2-UI-5 (phase-74) behavioral + wiring tests for the markdown note-body save
 * serialization fix.
 *
 * Finding: the editor body was produced with `File(readText)` while saves ran
 * `File.writeText` (truncate+write, no temp+rename); the dispose flush ran
 * concurrently with the next screen's read, so a reader could observe a
 * partially-written file and a re-flush of that truncated body permanently
 * dropped the note tail; two overlapping flush writes could leave a torn file.
 *
 * Phase-44 (B1-DB-4) already removed the plaintext FILE from the body path (the
 * body lives in the field-encrypted `pages.extractedText` column, written by a
 * transactional DAO update — no torn/partial write is semantically possible on
 * the DB). What the finding's concurrency half STILL maps to in the DB world is:
 *  1. unsynchronized saves ⇒ an OLDER body write can commit AFTER a NEWER one,
 *     losing the newer tail ("two overlapping flush writes leave a torn file");
 *  2. the page read came from a possibly-stale Room flow snapshot, so a body
 *     just saved could be re-opened stale, then edited + re-saved over the newer
 *     content ("readText observes truncated text, written back permanently").
 *
 * This phase serializes every body save through [MarkdownBodySaveCoordinator] so
 * the LAST-ISSUED body always wins and a superseded request never touches the
 * store, and coordinates the body READ (await settle, then fresh repository read)
 * via [com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel.readMarkdownNoteBody].
 * Everything below is provable on the pure JVM; the Android wiring is pinned at
 * source level in the wiring tests.
 */
class B2Ui5MarkdownSaveSerializationTest {

    // ---------- latest-wins serialization behavior ----------

    @Test
    fun `a save superseded by a newer one never touches the store`() = runBlocking {
        val coordinator = MarkdownBodySaveCoordinator()
        var store = "initial"
        var supersededWriteRan = false

        val oldRequest = coordinator.issue("p1", "old tail", null, null)
        val newRequest = coordinator.issue("p1", "new tail is longer", null, null)

        val oldCommitted = coordinator.commitLatest(oldRequest) {
            supersededWriteRan = true
            store = oldRequest.body
        }
        val newCommitted = coordinator.commitLatest(newRequest) { store = newRequest.body }

        assertFalse("the superseded save must not commit", oldCommitted)
        assertFalse("a superseded save must not run its write at all", supersededWriteRan)
        assertTrue("the newest save commits", newCommitted)
        assertEquals("the newest body wins", "new tail is longer", store)
    }

    @Test
    fun `a slow older write can never land after a newer one`() = runBlocking {
        val coordinator = MarkdownBodySaveCoordinator()
        var store = "initial"

        // Issues happen in UI order on the calling thread BEFORE commits launch:
        val oldRequest = coordinator.issue("p2", "older slow save", null, null)
        val newRequest = coordinator.issue("p2", "newer fast save", null, null)

        var oldCommitted = false
        var newCommitted = false
        launch {
            val c = coordinator.commitLatest(oldRequest) {
                delay(40) // the OLDER write is slow (disk/encryption)
                store = oldRequest.body
            }
            if (c) oldCommitted = true
        }
        launch {
            val c = coordinator.commitLatest(newRequest) {
                store = newRequest.body // the NEWER write lands immediately
            }
            if (c) newCommitted = true
        }.join()

        assertFalse("the older save was already superseded", oldCommitted)
        assertTrue(newCommitted)
        assertEquals("the newer body must be the final on-disk value", "newer fast save", store)
    }

    @Test
    fun `even an in-flight write is serialized - the latest issued body is final`() = runBlocking {
        val coordinator = MarkdownBodySaveCoordinator()
        var store = ""

        // The older request is current when it starts (nothing newer yet)...
        val oldRequest = coordinator.issue("p3", "first", null, null)
        val oldWentLast = CompletableDeferred<Unit>()
        val jobOld = launch {
            coordinator.commitLatest(oldRequest) {
                delay(30) // ...but it is SLOW: mid-write the user saves again
                store = oldRequest.body
                oldWentLast.complete(Unit)
            }
        }
        delay(5) // let the old write acquire the mutex and begin
        val newRequest = coordinator.issue("p3", "second", null, null)
        launch {
            coordinator.commitLatest(newRequest) { store = newRequest.body }
        }.join()
        oldWentLast.await()
        jobOld.join()

        assertEquals("the write is serialized: newest body must be the final value", "second", store)
    }

    @Test
    fun `torn-write simulator - concurrent flushes leave only a complete body, never a partial one`() = runBlocking {
        val coordinator = MarkdownBodySaveCoordinator()
        val bodies = List(24) { "body-$it-" + "x".repeat(it) }
        var store = ""
        val issued = bodies.map { body ->
            coordinator.issue("p4", body, null, null)
        }
        // Fire every commit concurrently; a naive File.writeText-style write would
        // truncate + write a partial value mid-way (torn on a concurrent read).
        val jobs = issued.map { req ->
            launch {
                coordinator.commitLatest(req) {
                    store = ""        // truncate
                    store += "partial"
                    store = req.body  // completion
                }
            }
        }
        jobs.joinAll()

        // The observable store after the dust settles is a COMPLETE body — never
        // the torn 'partial'/'' intermediate state.
        assertTrue("stored value must be one of the issued bodies, never a torn/partial write",
            bodies.contains(store))
        assertEquals("the LAST issued body is the final committed one", bodies.last(), store)
    }

    // ---------- settle coordination for the read side ----------

    @Test
    fun `a reader awaiting settle sees the fully committed value, never a partial one`() = runBlocking {
        val coordinator = MarkdownBodySaveCoordinator()
        var store = ""
        val releaseWrite = CompletableDeferred<Unit>()
        val writeCommitted = CompletableDeferred<Unit>()

        val request = coordinator.issue("p5", "complete-body", null, null)
        val jobWrite = launch {
            coordinator.commitLatest(request) {
                store = ""          // truncate, as File.writeText does
                store += "complete" // a racing reader would see a torn tail here
                releaseWrite.await() // hold the torn state so the race is real
                store = request.body
            }
            writeCommitted.complete(Unit)
        }

        delay(10) // the write is now mid-flight, holding the per-page mutex
        var settled = false
        val jobRead = launch {
            settled = coordinator.awaitSettled("p5")
        }
        delay(20)
        assertFalse("a read blocked on an in-flight save must not observe the torn value", settled)

        releaseWrite.complete(Unit) // the write finishes; the settle fires
        writeCommitted.await()
        jobRead.join()

        assertTrue("the reader's settle await completes once the save settles", settled)
        assertEquals("after awaitSettled the store holds the complete body", "complete-body", store)
    }

    @Test
    fun `awaitSettled is immediate when the page has no pending save`() = runBlocking {
        val coordinator = MarkdownBodySaveCoordinator()
        assertTrue("no request issued -> nothing to await", coordinator.awaitSettled("p-absent"))
    }

    @Test
    fun `awaitSettled is bounded when a request never settles`() = runBlocking {
        val coordinator = MarkdownBodySaveCoordinator(settleTimeoutMs = 100L)
        coordinator.issue("p6", "never-committed", null, null)
        // No commitLatest ever runs for this request (e.g. its owner coroutine was
        // cancelled before dispatch). The read must NOT hang — it falls back to
        // reading the current store after the bounded wait.
        val started = System.nanoTime()
        val settled = coordinator.awaitSettled("p6")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertFalse("a never-settling request must not be reported as settled", settled)
        assertTrue("the wait must be bounded (~settleTimeoutMs, not forever)", elapsedMs in 100..5_000)
    }

    @Test
    fun `two pages serialize independently - no cross-page blocking`() = runBlocking {
        val coordinator = MarkdownBodySaveCoordinator()
        var a = ""
        var b = ""

        val ra2 = coordinator.issue("pa", "a2", null, null)
        val rb1 = coordinator.issue("pb", "b1", null, null)
        val jobA = launch { coordinator.commitLatest(ra2) { a = ra2.body } }
        val jobB = launch {
            coordinator.commitLatest(rb1) {
                delay(20)
                b = rb1.body
            }
        }
        joinAll(jobA, jobB)
        assertEquals("page A value is its own latest issue", "a2", a)

        // A superseded page-A request still cannot resurrect an older page-A body:
        val ra1 = coordinator.issue("pa", "a1", null, null) // issued AFTER ra2 -> older content
        val lateA = coordinator.issue("pa", "a3", null, null)
        val ja = launch { coordinator.commitLatest(ra1) { a = ra1.body } } // superseded -> skip
        val jb = launch { coordinator.commitLatest(lateA) { a = lateA.body } }
        joinAll(ja, jb)

        assertEquals("page B value is independent of page A ordering", "b1", b)
        assertEquals("the latest page A body wins", "a3", a)
    }

    @Test
    fun `reader chases a request issued while it was awaiting`() = runBlocking {
        val coordinator = MarkdownBodySaveCoordinator()
        var store = ""
        val startedFirstWrite = CompletableDeferred<Unit>()
        val releaseSecondWrite = CompletableDeferred<Unit>()

        val first = coordinator.issue("p7", "first body", null, null)
        val jobFirst = launch {
            coordinator.commitLatest(first) {
                startedFirstWrite.complete(Unit) // first write starts...
                releaseSecondWrite.await()       // ...and stalls
                store = first.body
            }
        }
        startedFirstWrite.await()

        var settledOnce = false
        val jobReader = launch {
            // A read issued at this moment anchors on `first` and blocks on it
            // (the timeout is long enough for the whole scenario).
            settledOnce = coordinator.awaitSettled("p7")
        }
        delay(5)

        // While the reader waits, the user saves AGAIN -> the reader must chase it.
        val second = coordinator.issue("p7", "second body", null, null)
        val jobSecond = launch {
            coordinator.commitLatest(second) { store = second.body }
        }
        delay(5)
        assertFalse("reader still waiting: settle not yet", settledOnce)
        releaseSecondWrite.complete(Unit)
        joinAll(jobFirst, jobSecond, jobReader)

        assertTrue("reader settle completes only after the newest save settles", settledOnce)
        assertEquals("the newest body is on disk", "second body", store)
    }

    // ---------- wiring pins (the Android-bound classes) ----------

    @Test
    fun `MainActivity body reads route through the fresh coordinated read`() {
        val main = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt").readText()

        // Both layouts' produceState now read the FRESH body via the ViewModel and
        // never read a possibly-stale flow snapshot / a raw file. The composition's
        // in-memory snapshot is passed only as the key-lost deflate fallback.
        assertTrue("every markdown-body produceState read must route through readMarkdownNoteBody",
            main.contains("viewModel.readMarkdownNoteBody(page.id,"))
        assertTrue("no inline NoteBodyVaultPolicy.resolveBodyForDisplay survives in MainActivity",
            !main.contains("NoteBodyVaultPolicy.resolveBodyForDisplay"))
        assertTrue("no File body readText survives in MainActivity",
            !main.contains(".readText()"))
    }

    @Test
    fun `ViewModel serializes markdown saves through the coordinator`() {
        val vm = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt").readText()

        val save = vm.substringAfter("fun saveMarkdownNoteBody", "END").take(2200)
        assertTrue("save must register the request in UI-issue order",
            save.contains("markdownBodySaveCoordinator.issue"))
        assertTrue("the encrypted write must run under commitLatest",
            save.contains("markdownBodySaveCoordinator.commitLatest"))
        assertTrue("a superseded save must not overwrite a newer body", save.contains("!committed"))
        assertTrue("gate + lock-deferral must be retained",
            save.contains("VaultWriteGate.persistNow") && save.contains("deferBody(deferred)"))

        val read = vm.substringAfter("fun readMarkdownNoteBody", "END").take(800)
        assertTrue("the read must await the in-flight save settle", read.contains("awaitSettled"))
        assertTrue("the read must use a fresh repository fetch", read.contains("repository.getPageById"))

        val flush = vm.substringAfter("private fun flushPendingEditorSaves", "END").take(2600)
        assertTrue("the unlock flush must drain deferred bodies", flush.contains("drainBodies()"))
        assertTrue("the flush must issue deferred bodies BEFORE the write (strictly older than any new edit)",
            flush.contains("markdownBodySaveCoordinator.issue"))
        assertTrue("the flush body write must run under commitLatest",
            flush.contains("markdownBodySaveCoordinator.commitLatest"))
        assertTrue("the flush write must go through the encrypted column",
            flush.contains("repository.updatePageBody(request.pageId, request.body)"))
        assertTrue("the legacy delete must be confined to the imports root",
            flush.contains("ImportExportService.getImportsDir(appContext)"))
    }

    @Test
    fun `no plaintext body file write remains on the markdown save path`() {
        val vm = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt").readText()
        val main = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt").readText()

        val save = vm.substringAfter("fun saveMarkdownNoteBody", "END").take(2200)
        assertTrue("markdown save must never write a plaintext body file",
            !save.contains("writeText"))
        assertTrue("markdown save must only ever call the single repository write",
            !save.contains("File("))
        assertTrue("the editor save path never touches File.writeText", !main.contains(".writeText("))
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