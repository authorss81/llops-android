package com.authorss81.noteflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 250 — data-loss criticals (AUDIT_2026-08-30):
 *
 *  1. A stale autosave that has already passed its `delay(1000)` debounce and
 *     dispatched its write can land AFTER a newer `flushPendingSaves`, over-
 *     writing the newest snapshot (the newest stroke disappears on reopen).
 *  2. A `lock()` firing between `loadEditorCanvasPage` returning and
 *     `isInitialLoadComplete = true` renders the page empty; a back-press then
 *     flushes `strokes = emptyList()` and DELETES every stroke row.
 *
 * Bug 1 is fixed by a monotonically increasing `editorSaveGeneration` token:
 * every write-entry (triggerAutoSave debounce + flushPendingSaves) bumps it and
 * stamps its write; `persistEditorSaveSuspend` (the single entry into
 * `repo.saveStrokesForPage` — "at the start of the WRITE", not the UI layer)
 * re-checks the stamped token and SKIPS a stale one. Bug 2 is fixed by re-
 * checking the auth gate at the assignment moment and refusing to flush while
 * `loadFailedDueToLock` is set.
 *
 * The generation semantics are provable on the pure JVM (modeled in
 * [GenerationGate] below); the Compose / ViewModel wiring is pinned at source
 * level, consistent with the repo's pure-JVM unit-suite convention.
 */
class Phase250DataLossCriticalsTest {

    // ------------------------------------------------------------------
    // Bug 1: generation token — stale save after a newer flush is a no-op
    // ------------------------------------------------------------------

    /** Pure-JVM model of the generation-gated write contract described in the
     *  PROMPT: a write stamps the generation that was current when it was
     *  requested and `persistEditorSaveSuspend` skips any stamped token that is
     *  no longer the newest when the write actually runs. */
    class GenerationGate {
        @Volatile
        var currentGeneration: Int = 0

        fun bump(): Int = ++currentGeneration

        /** Mirrors the VM's `persistEditorSaveSuspend` entry check: return true
         *  iff [stamped] is still the newest generation. */
        fun isCurrentSaveGeneration(stamped: Int): Boolean = stamped == currentGeneration

        /** Mirrors the write check: a stale save returns null and is never
         *  committed; a current one runs [block]. */
        fun <T> persistIfCurrent(stamped: Int, block: () -> T): T? =
            if (isCurrentSaveGeneration(stamped)) block() else null
    }

    @Test
    fun `a save stamped with an older generation is skipped after a newer flush bumps it`() {
        val gate = GenerationGate()
        val autosaveGen = gate.bump() // gen 1 — the stale autosave
        assertEquals(1, autosaveGen)

        // A newer flush arrives and bumps the generation to 2.
        val flushGen = gate.bump()
        assertEquals(2, flushGen)

        var staleCommitted = false
        val result = gate.persistIfCurrent(autosaveGen) { staleCommitted = true }

        // The stale autosave (gen 1) must be a no-op — its write never runs,
        // so it can never overwrite the newer flush's snapshot.
        assertNull("stale save must be skipped, not committed", result)
        assertFalse("stale write block must never execute", staleCommitted)
    }

    @Test
    fun `the current generation save is still committed after bumping`() {
        val gate = GenerationGate()
        gate.bump() // some older autosave
        val latestGen = gate.bump() // the newest flush
        assertEquals(2, latestGen)

        var committed = false
        val result = gate.persistIfCurrent(latestGen) { committed = true }

        assertTrue("the current (newest) save must be committed", result != null)
        assertTrue("the current write block must execute", committed)
    }

    @Test
    fun `an in flight save whose generation was superseded mid gate is skipped - newest wins`() {
        // Models the worst case in the PROMPT: the debounce's delay(1000) has
        // elapsed and it dispatches its write (gen 1). The write's own entry
        // check runs at the start of persistEditorSaveSuspend; if a flush bumps
        // the token before the write's entry check runs, the stale write is
        // dropped — so the flush's (gen 2) snapshot always lands last.
        val gate = GenerationGate()
        val staleAutosaveGen = gate.bump() // gen 1
        val flushGen = gate.bump() // gen 2 (flush bumps BEFORE its own write entry)

        // The flush's entry check sees the newest gen and commits.
        var flushed = false
        assertTrue(gate.persistIfCurrent(flushGen) { flushed = true } != null)
        assertTrue("newest flush must commit", flushed)

        // The stale autosave's write entry runs AFTER — its token is superseded.
        var staleCommitted = false
        assertNull("stale autosave entry after flush must be dropped", gate.persistIfCurrent(staleAutosaveGen) { staleCommitted = true })
        assertFalse("stale write must not commit after the newest flush", staleCommitted)
    }

    // ------------------------------------------------------------------
    // Bug 1: source pins — generation bumped BEFORE every flush / autosave
    // ------------------------------------------------------------------

    @Test
    fun `source pin - generation is bumped before every flushPendingSaves and every triggerAutoSave`() {
        val vm = file("app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt")
        val editor = file("app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt")

        // The VM declares the token and bumps it before the flush is dispatched.
        assertTrue("VM must declare the generation token", vm.contains("@Volatile") && vm.contains("editorSaveGeneration"))
        val flush = vm.substringAfter("fun flushPendingSaves(", "END").substringBefore("\n    suspend fun autosaveStrokes(", "END")
        assertTrue(
            "flushPendingSaves must bump the generation BEFORE flushing (so an in-flight stale autosave is invalidated)",
            flush.contains("bumpSaveGeneration()") &&
                flush.indexOf("bumpSaveGeneration()") < flush.indexOf("flushEditorPageSave(")
        )

        // The editor bumps the generation before arming the autosave debounce.
        val autosave = editor.substringAfter("fun triggerAutoSave(", "END").substringBefore("\n    fun handleStrokesChange(", "END")
        assertTrue(
            "triggerAutoSave must bump the VM generation BEFORE launching the autosave debounce",
            autosave.contains("++viewModel.editorSaveGeneration") &&
                autosave.indexOf("++viewModel.editorSaveGeneration") < autosave.indexOf("autosaveStrokes(")
        )
    }

    @Test
    fun `source pin - flushPendingSaves body wraps the write in withContext NonCancellable`() {
        val vm = file("app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt")
        val flush = vm.substringAfter("fun flushPendingSaves(", "END").substringBefore("\n    suspend fun autosaveStrokes(", "END")
        assertTrue(
            "flushPendingSaves body must run inside withContext(NonCancellable)",
            flush.contains("withContext(NonCancellable) {")
        )
        // And it must still cancel+await the pending debounce and flush the newest.
        assertTrue("flushPendingSaves must cancel the pending debounce", flush.contains("pendingDebounce?.cancel()"))
        assertTrue("flushPendingSaves must await the pending debounce", flush.contains("pendingDebounce?.join()"))
    }

    @Test
    fun `source pin - write entry skips a stale generation (persistEditorSaveSuspend)`() {
        val vm = file("app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt")
        val persist = vm.substringAfter("private suspend fun persistEditorSaveSuspend(", "END")
            .substringBefore("\n    private fun persistOrDefer(", "END")
        assertTrue(
            "the write entry must skip a stale (non-current) generation before persisting",
            persist.contains("isCurrentSaveGeneration(generation)")
        )
        // The check lives inside persistEditorSaveSuspend (the single entry into
        // repo.saveStrokesForPage for the autosave path) — NOT at the UI layer.
        assertTrue("the generation gate must delegate to isCurrentSaveGeneration", persist.contains("if (!isCurrentSaveGeneration(generation))"))
    }

    // ------------------------------------------------------------------
    // Bug 2: source pins — lock during load cannot wipe the page
    // ------------------------------------------------------------------

    @Test
    fun `source pin - editor load assigns only while authenticated and sets isInitialLoadComplete inside the same if`() {
        val editor = file("app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt")
        val load = editor.substringAfter("LaunchedEffect(page.id, isAuthenticated) {", "END")

        // The whole assignment block must live inside `if (viewModel.authenticated.value) { ... }`.
        val authIdx = load.indexOf("if (viewModel.authenticated.value) {")
        assertTrue("the load must gate the assignment on the auth value", authIdx >= 0)

        val block = load.substring(authIdx, load.indexOf("} else {", authIdx))
        assertTrue("the assignment must be inside the authenticated block", block.contains("strokes = data.strokes"))
        // isInitialLoadComplete must be set INSIDE the auth branch (not after it),
        // so a lock dropping the gate can never mark the page as fully loaded (which
        // would let a back-press flush emptyList() over real ink).
        assertTrue(
            "isInitialLoadComplete = true must be inside the authenticated block",
            block.contains("isInitialLoadComplete = true") &&
                load.indexOf("isInitialLoadComplete = true") < load.indexOf("} else {", authIdx)
        )
        // The else branch must mark the load as failed-due-to-lock and keep the
        // page not-loaded (isInitialLoadComplete stays false), so no flush can fire.
        assertTrue(
            "the locked branch must set loadFailedDueToLock and keep isInitialLoadComplete false",
            load.contains("loadFailedDueToLock = true") && load.contains("isInitialLoadComplete = false")
        )
    }

    @Test
    fun `source pin - back paths refuse to flush while loadFailedDueToLock is set`() {
        val editor = file("app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt")

        // System back (BackHandler).
        val backHandler = editor.substringAfter("BackHandler {", "END").substringBefore("\n    Scaffold(", "END")
        assertTrue(
            "BackHandler must check !loadFailedDueToLock before flushPendingSaves",
            backHandler.contains("!loadFailedDueToLock") &&
                backHandler.indexOf("!loadFailedDueToLock") < backHandler.indexOf("flushPendingSaves(")
        )

        // Top-bar navigation back button (the IconButton onClick path).
        val navBack = editor.substringAfter("IconButton(", "END")
        assertTrue(
            "top-bar back must check !loadFailedDueToLock before flushPendingSaves",
            navBack.contains("!loadFailedDueToLock") &&
                navBack.indexOf("!loadFailedDueToLock") < navBack.indexOf("flushPendingSaves(")
        )
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun file(path: String): String {
        return java.io.File(repoRoot(), path).readText()
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
