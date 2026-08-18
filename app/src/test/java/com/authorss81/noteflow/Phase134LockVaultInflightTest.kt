package com.authorss81.noteflow

import com.authorss81.noteflow.services.LockedPoolDecision
import com.authorss81.noteflow.services.LockedPoolGuard
import com.authorss81.noteflow.services.VaultLockedWriteException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2-B1A-01 / R2-B1A-02 / R2-b2b1-UI-01 (phase-134) — the lock-vs-inflight pool
 * race.
 *
 * `lock()` zeroizes the DEK and disposes the Room/SQLCipher pool underneath any
 * in-flight read or write that was launched while unlocked. Before this phase
 * those flights threw an uncaught `IllegalStateException("connection pool has
 * been closed")` on the main dispatcher / composition scope → process crash:
 *  - ~18 notebook/section/tag/pin/trash/template/palette writes ran bare
 *    `viewModelScope.launch { repository.X() }` with no catch (R2-B1A-01),
 *  - the shared search job was cancelled only on a new keystroke, never in
 *    `lock()`, and even a completed search published decrypted results after the
 *    auth gate dropped (R2-B1A-02),
 *  - every composition-scoped read-side load was unguarded (R2-b2b1-UI-01).
 *
 * What is provable on the pure JVM: the classic decision table
 * ([LockedPoolGuard.isLockRace]) that classifies a failure as a lock race vs a
 * genuine error (never swallowed), and the fail-closed entry/publish predicates
 * of the search path. The Android-bound wiring (VM write/read guards, search-job
 * cancellation in `lock()`, guarded UI accessors) is pinned at source level
 * below, mirroring the B2Ui1LockedFlushTest.style.
 */
class Phase134LockVaultInflightTest {

    // ---------- pure-JVM decision table behavior ----------

    @Test
    fun `closed pool ISE is classified as a lock race when the vault holds a key`() {
        val closedPool = IllegalStateException("connection pool has been closed")
        assertTrue(
            "Room/SQLCipher's closed-pool throw must degrade, never crash",
            LockedPoolGuard.isLockRace(closedPool, keyPresent = true)
        )
        assertEquals(LockedPoolDecision.Degrade, LockedPoolGuard.isLockRaceOrRethrow(closedPool, keyPresent = true))
    }

    @Test
    fun `the write gate's fail-closed throw is a lock race`() {
        assertTrue(LockedPoolGuard.isLockRace(VaultLockedWriteException(), keyPresent = true))
        assertEquals(
            LockedPoolDecision.Degrade,
            LockedPoolGuard.isLockRaceOrRethrow(VaultLockedWriteException(), keyPresent = true)
        )
    }

    @Test
    fun `a closed-pool ISE wrapped in a runtime cause chain is still a lock race`() {
        val wrapped = RuntimeException("db layer", IllegalStateException("connection pool has been closed"))
        assertTrue("Room may wrap the SQLCipher throw — causes must be walked", LockedPoolGuard.isLockRace(wrapped, keyPresent = true))
    }

    @Test
    fun `a genuine error with a live key is never swallowed as a lock race`() {
        val ioError = IllegalStateException("disk I/O error while writing")
        assertFalse(LockedPoolGuard.isLockRace(ioError, keyPresent = true))
        assertEquals(LockedPoolDecision.Rethrow, LockedPoolGuard.isLockRaceOrRethrow(ioError, keyPresent = true))

        val sqlite = RuntimeException("corruption", IllegalStateException("database disk image is malformed"))
        assertFalse("a real DB error must stay loud", LockedPoolGuard.isLockRace(sqlite, keyPresent = true))
    }

    @Test
    fun `a missing DEK fails closed for any failure - the vault is locked`() {
        assertTrue(LockedPoolGuard.isLockRace(IllegalStateException("anything"), keyPresent = false))
        assertTrue(LockedPoolGuard.isLockRace(RuntimeException("anything"), keyPresent = false))
        assertTrue(LockedPoolGuard.isLockRace(NullPointerException("anything"), keyPresent = false))
    }

    // ---------- wiring pins (the Android-bound classes) ----------

    private fun vmSource(): String =
        java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt").readText()

    @Test
    fun `the write-race predicate now classifies via LockedPoolGuard`() {
        val source = vmSource()
        assertTrue("write predicate must use the pure-JVM decision table", source.contains("LockedPoolGuard.isLockRace"))
    }

    @Test
    fun `a shared read-side guard accessor exists and the search path fails closed`() {
        val source = vmSource()
        assertTrue("a shared read guard must exist", source.contains("private suspend fun <T> withLockedPoolGuard"))
        assertTrue("search must fail closed into an empty batch", source.contains("searchFailClosed"))
        assertTrue("shallow search must empty-publish when locked at entry", source.contains("if (repository.encryptionKey == null) {\n            onResult(emptyList())"))
        assertTrue("deep search must empty-publish when locked at entry", source.contains("searchVaultJob = viewModelScope.launch(Dispatchers.IO) {"))
    }

    @Test
    fun `lock cancels the shared search job and clears it`() {
        val source = vmSource()
        assertTrue("lock() must cancel the in-flight search job", source.contains("searchVaultJob?.cancel()"))
        assertTrue("lock() must null the job handler", source.contains("searchVaultJob = null"))
    }

    @Test
    fun `a completed search re-checks the auth gate before publishing`() {
        val source = vmSource()
        assertTrue(
            "decrypted rows must never publish after the auth gate dropped",
            source.contains("if (repository.encryptionKey != null) {\n                onResult(results)\n            }")
        )
    }

    @Test
    fun `every R2-B1A-01 write mutation is routed through writeGuardedAgainstLock`() {
        val source = vmSource()
        val unguardedSignatures = listOf(
            "fun addNotebook", "fun renameNotebook", "fun updateNotebookNameAndTags", "fun updateNotebookTags",
            "fun deleteNotebook", "fun addSection", "fun renameSection", "fun deleteSection",
            "fun updatePageTags", "fun renameTag", "fun deleteTag", "fun togglePinPage", "fun trashPage",
            "fun updatePageTemplate", "fun updatePageSource", "fun restorePage", "fun deletePagePermanently",
            "fun movePage", "fun emptyTrash", "fun insertPaletteItem", "fun deletePaletteItem",
            "fun clearPaletteItemsByType"
        )
        for (sig in unguardedSignatures) {
            val block = source.substringAfter(sig, "END").take(4500)
            assertTrue(
                "$sig must guard its repository writes against a lock race",
                block.contains("writeGuardedAgainstLock")
            )
        }
    }

    @Test
    fun `composition-scoped UI reads use the guarded VM accessors`() {
        val editor = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt").readText()
        assertTrue("editor must load the whole canvas through one guard", editor.contains("viewModel.loadEditorCanvasPage(page.id)"))
        assertTrue("editor must re-check the auth gate before assigning", editor.contains("if (viewModel.authenticated.value) {"))

        val graph = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/screens/KnowledgeGraphScreen.kt").readText()
        assertTrue("graph must load pages through the guarded accessor", graph.contains("viewModel.loadAllActivePages()"))

        val backlinks = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/components/BacklinksInspector.kt").readText()
        assertTrue("backlinks must load pages through the guarded accessor", backlinks.contains("viewModel.loadAllActivePages()"))

        val explorer = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/components/TagExplorerView.kt").readText()
        assertTrue("tag explorer must load pages through the guarded accessor", explorer.contains("viewModel.loadAllActivePages()"))

        val tagManager = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/components/TagManagerDialog.kt").readText()
        assertTrue("tag manager must load notebooks through the guarded accessor", tagManager.contains("viewModel.loadAllNotebooks()"))
        assertTrue("tag manager must load pages through the guarded accessor", tagManager.contains("viewModel.loadAllActivePages()"))

        val versions = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/components/VersionHistoryBottomSheet.kt").readText()
        assertTrue("version history must load through getNoteVersions (now guarded)", versions.contains("viewModel.getNoteVersions(page.id)"))

        val home = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/screens/HomeScreen.kt").readText()
        assertTrue("home trash must route through the guarded VM write", home.contains("viewModel.trashPage(page.id)"))
        assertTrue("home pin must route through the guarded VM write", home.contains("viewModel.togglePinPage(page.id, page.pinned)"))
        assertTrue("home delete-confirm counts must be guarded reads", home.contains("viewModel.loadNotebookCounts(nb.id)"))
    }

    @Test
    fun `the command palette search is guarded inside the VM`() {
        val source = vmSource()
        val palette = source.substringAfter("suspend fun commandPaletteSearch", "END").take(1400)
        assertTrue("palette search must degrade on a lock race", palette.contains("withLockedPoolGuard"))
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