package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.NotePageEntity
import com.authorss81.noteflow.services.VaultSearchPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 78 (B2-DOS-02): vault search is BOUNDED — a keystroke never re-decrypts
 * the whole vault, and concurrent in-flight searches cancel each other.
 *
 * Finding: `NoteRepository.loadSearchCorpus` deliberately skipped the decrypted
 * corpus cache once the active page count exceeded 1500, so for a 5k+ page vault
 * EVERY non-blank keystroke re-ran a full-vault AES-GCM decrypt of every
 * title/body + an O(n) substring scan on a 2-core device, `searchVault` launched
 * a fresh never-cancelled coroutine per query, and the underlying reads had no
 * LIMIT.
 *
 * What this proves on the pure JVM (no Room/SQLCipher/Context): the decision
 * table — the cached window is ALWAYS bounded at `SEARCH_CORPUS_CAP`, vaults
 * over the cap are DETECTED (so the UI can offer an explicit, user-approved
 * "search all pages" refine instead of silent degradation), and a blank query is
 * never scanned. The Android-bound wiring (corpus always cached via the bounded
 * DAO read, the shared cancelled search Job, the paged deep-scan refine, the
 * HomeScreen affordance) is pinned at source level below.
 */
class B2Dos02VaultSearchBoundedTest {

    // ---------- policy: searches beyond the cap are bounded ----------

    @Test
    fun `the cached search window is always capped at SEARCH_CORPUS_CAP`() {
        assertEquals(1500, VaultSearchPolicy.SEARCH_CORPUS_CAP)
        assertEquals(
            "a vault AT the cap fits in the window unchanged",
            1500,
            VaultSearchPolicy.cachedWindowSize(1500)
        )
        assertEquals(
            "a small vault fits in the window unchanged",
            42,
            VaultSearchPolicy.cachedWindowSize(42)
        )
        assertEquals(
            "a vault OVER the cap is always bounded to the cap — never the vault size",
            VaultSearchPolicy.SEARCH_CORPUS_CAP,
            VaultSearchPolicy.cachedWindowSize(5000)
        )
        assertEquals(
            "an empty vault yields an empty window",
            0,
            VaultSearchPolicy.cachedWindowSize(0)
        )
    }

    @Test
    fun `cap detection is exact at the boundary_1500 vs 1501`() {
        assertFalse(VaultSearchPolicy.exceedsCorpusCap(1500))
        assertTrue(VaultSearchPolicy.exceedsCorpusCap(1501))
        assertTrue(VaultSearchPolicy.exceedsCorpusCap(5000))
        assertFalse(VaultSearchPolicy.exceedsCorpusCap(0))
    }

    @Test
    fun `blank queries are never scanned`() {
        assertTrue(VaultSearchPolicy.isBlankQuery(null))
        assertTrue(VaultSearchPolicy.isBlankQuery(""))
        assertTrue(VaultSearchPolicy.isBlankQuery("   "))
        assertFalse(VaultSearchPolicy.isBlankQuery("hello"))
        assertFalse(VaultSearchPolicy.isBlankQuery(" " + "hi"))
    }

    @Test
    fun `the refine notice is non-alarming and only present for capped vaults`() {
        assertEquals("", VaultSearchPolicy.refineNoticeMessage(1500))
        assertEquals("", VaultSearchPolicy.refineNoticeMessage(0))
        val capped = VaultSearchPolicy.refineNoticeMessage(5000)
        assertTrue(capped.contains("1500"))
        assertTrue(capped.contains("5000"))
        assertFalse(capped.contains("error"))
        assertFalse(capped.contains("fail"))
    }

    @Test
    fun `page match is title-or-body substring case-insensitive`() {
        val page = NotePageEntity(id = "p1", sectionId = "s", title = "Trip to Berlin", extractedText = "Waves crash loudly on the shore.")

        assertTrue(VaultSearchPolicy.pageMatches(page, "berlin"))
        assertTrue(VaultSearchPolicy.pageMatches(page, "CRASH"))
        assertTrue(VaultSearchPolicy.pageMatches(page, "Trip"))
        assertFalse(VaultSearchPolicy.pageMatches(page, "paris"))
        assertFalse(VaultSearchPolicy.pageMatches(page, "zzz"))
    }

    @Test
    fun `page match handles null and blank bodies`() {
        val noBody = NotePageEntity(id = "p2", sectionId = "s", title = "Groceries", extractedText = null)
        assertTrue(VaultSearchPolicy.pageMatches(noBody, "GROCERIES"))
        assertFalse(VaultSearchPolicy.pageMatches(noBody, "milk"))

        val blankBody = NotePageEntity(id = "p3", sectionId = "s", title = "Idea", extractedText = "")
        assertTrue(VaultSearchPolicy.pageMatches(blankBody, "idea"))
        assertFalse(VaultSearchPolicy.pageMatches(blankBody, "plan"))
    }

    // ---------- source pins: Android-bound wiring ----------

    @Test
    fun `corpus loader always caches the bounded window via a limited read`() {
        val source = readNoteRepositorySource()
        assertTrue(
            "the load must come from the BOUNDED DAO read (LIMIT at the SQL layer)",
            source.contains("getAllActivePagesBounded(VaultSearchPolicy.SEARCH_CORPUS_CAP)")
        )
        assertTrue(
            "the decrypted window must ALWAYS be cached — no size-gated skip",
            source.contains("cachedSearchCorpus = window")
        )
        assertFalse(
            "the pre-fix 'cache disabled over the cap' gate must be gone",
            source.contains("searchCorpusMaxPages")
        )
        assertFalse(
            "the pre-fix conditional cache write must be gone",
            source.contains("corpus.size <= ")
        )
        assertTrue(
            "the capped state must be surfaced so the UI can offer the refine path",
            source.contains("val searchCorpusCapped: Boolean")
        )
    }

    @Test
    fun `the explicit refine deep search pages the vault in bounded batches`() {
        val source = readNoteRepositorySource()
        assertTrue(
            "the deep scan must use the paged DAO read",
            source.contains("getAllActivePagesPaged(VaultSearchPolicy.DEEP_SCAN_BATCH_SIZE, offset)")
        )
        assertTrue(
            "the deep scan must advance by the bounded batch size",
            source.contains("offset += VaultSearchPolicy.DEEP_SCAN_BATCH_SIZE")
        )
        assertTrue(
            "the deep scan must stop at a partial batch (end of vault)",
            source.contains("if (batch.size < VaultSearchPolicy.DEEP_SCAN_BATCH_SIZE) break")
        )
        assertTrue(
            "the deep scan must keep only matches — never pin the full decrypted vault",
            source.contains("matches += batch.filter")
        )
    }

    @Test
    fun `the DAO exposes the bounded and counted reads with LIMIT`() {
        val source = readDaosSource()
        assertTrue(
            "the capped window read must carry a SQL LIMIT",
            source.contains("getAllActivePagesBounded(limit: Int)") &&
                source.contains("LIMIT :limit")
        )
        assertTrue(
            "the deep scan paging read must carry a SQL LIMIT/OFFSET",
            source.contains("getAllActivePagesPaged(limit: Int, offset: Int)") &&
                source.contains("LIMIT :limit OFFSET :offset")
        )
        assertTrue(
            "the cap decision must be backed by a vault page count",
            source.contains("getActivePageCountOnce()")
        )
    }

    @Test
    fun `searchVault shares one job and cancels the previous in-flight search`() {
        val source = readNoteflowViewModelSource()
        assertTrue(
            "every new search must cancel the prior in-flight one",
            source.contains("searchVaultJob?.cancel()")
        )
        assertTrue(
            "the fresh launch must be stored on the shared job",
            source.contains("searchVaultJob = viewModelScope.launch")
        )
        assertTrue(
            "a superseded search must not deliver stale results",
            source.contains("coroutineContext.ensureActive()")
        )
        assertTrue(
            "the deep refine must share the SAME cancelled-on-keystroke job",
            source.contains("fun deepSearchVault(query: String, onResult: (List<NotePageEntity>) -> Unit)") &&
                source.contains("searchVaultJob?.cancel()")
        )
    }

    @Test
    fun `HomeScreen offers the one-time non-alarming refine affordance`() {
        val source = readHomeScreenSource()
        assertTrue(
            "the refine notice must only appear for a capped vault",
            source.contains("viewModel.repository.searchCorpusCapped")
        )
        assertTrue(
            "the affordance must be one-time per query session (no re-scam)",
            source.contains("refinedSearchDone")
        )
        assertTrue(
            "tapping it must route to the explicit deep search",
            source.contains("viewModel.deepSearchVault(searchQuery)")
        )
        assertTrue(
            "the affordance must be non-alarming copy",
            source.contains("Search covers the most recent pages")
        )
    }

    // ---------- source readers ----------

    private fun readNoteRepositorySource(): String =
        readSource("data/repository/NoteRepository.kt")

    private fun readDaosSource(): String =
        readSource("data/db/Daos.kt")

    private fun readNoteflowViewModelSource(): String =
        readSource("ui/viewmodel/NoteflowViewModel.kt")

    private fun readHomeScreenSource(): String =
        readSource("ui/screens/HomeScreen.kt")

    private fun readSource(relative: String): String {
        val file = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/$relative")
        assertTrue("$relative must exist for the wiring pin", file.isFile)
        return file.readText()
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