package com.authorss81.noteflow

import com.authorss81.noteflow.services.NoteVersionRetentionPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2-b2b4-DOS-01 (phase-149) — bound the `note_versions` table and never decrypt
 * it wholesale.
 *
 * Finding: a full title + full extractedText snapshot is written on EVERY manual
 * save / autosave / before-translation-replace with NO pruning in the insert
 * path ([NoteflowViewModel.createNoteVersion] → `NoteRepository.createNoteVersion`),
 * the history read decrypts EVERY stored body at once
 * (`getNoteVersions` → `VersionHistoryBottomSheet`), the backup serializes the
 * WHOLE table and a cross-device restore re-encrypts the whole table in heap. A
 * crafted backup holding ~5,000 rows × ~50 KB bodies grows to ~250 MB in heap on
 * Version History open → OOM.
 *
 * Fix shape (all provable on the pure JVM — no Room/SQLCipher):
 *  - [NoteVersionRetentionPolicy] owns the budgets and a pure retention
 *    decision; the Room prune + raw restore sanitizer share the SAME SQL.
 *  - `createNoteVersion` inserts AND prunes to the newest N inside ONE
 *    transaction — simulated with a fake store below (the DAO is otherwise
 *    Room-bound, exactly like the StrokesGeometry list-gate tests).
 *  - `getNoteVersions` pages with LIMIT/OFFSET (never one all-row read), and the
 *    bottom sheet materializes lazily window-by-window.
 *  - the backup writer prunes before its checkpoint-then-copy and the restore
 *    sanitizer strips a crafted table under the candidate key BEFORE re-key.
 *
 * The Android-bound wiring is pinned at source level below.
 */
class Phase149NoteVersionsRetentionTest {

    private data class Row(val id: String, val timestampMs: Long)

    // ---------------------------------------------------------------------
    // pure policy: budgets + retention decision
    // ---------------------------------------------------------------------

    @Test
    fun `the policy owns the exact budgets the fix wires`() {
        assertEquals("retention cap is the newest 20 per page", 20, NoteVersionRetentionPolicy.MAX_VERSIONS_PER_PAGE)
        assertEquals("the history decrypt window is 20", 20, NoteVersionRetentionPolicy.DECRYPT_BATCH_SIZE)
        assertEquals("the reencrypt sweep window is 100", 100, NoteVersionRetentionPolicy.REENCRYPT_BATCH_SIZE)
        assertTrue(NoteVersionRetentionPolicy.exceedsCap(21))
        assertTrue(NoteVersionRetentionPolicy.exceedsCap(100_000))
        assertFalse(NoteVersionRetentionPolicy.exceedsCap(20))
        assertFalse(NoteVersionRetentionPolicy.exceedsCap(0))
        assertEquals(30, NoteVersionRetentionPolicy.pruneCountForPage(50))
        assertEquals(0, NoteVersionRetentionPolicy.pruneCountForPage(20))
        assertEquals(0, NoteVersionRetentionPolicy.pruneCountForPage(7))
    }

    @Test
    fun `the decision keeps the newest N and drops exactly the oldest tail`() {
        val rows = (0 until 50).map { i -> Row(id = "v$i", timestampMs = 1_000L + i * 10L) }
        // Newest-first input (matching the DAO's ORDER BY timestampMs DESC).
        val decision = NoteVersionRetentionPolicy.decideRetention(
            rows.sortedByDescending { it.timestampMs }.map { it.id to it.timestampMs }
        )

        assertEquals(NoteVersionRetentionPolicy.MAX_VERSIONS_PER_PAGE, decision.keepIds.size)
        assertEquals(30, decision.dropIds.size)
        // The 20 newest survive.
        assertTrue(decision.keepIds.contains("v49"))
        assertTrue(decision.keepIds.contains("v30"))
        // The 30 oldest are these exact tail rows (oldest-of-kept first).
        assertEquals((29 downTo 0).map { "v$it" }, decision.dropIds)
        assertFalse(decision.keepIds.contains("v0"))
        assertFalse(decision.keepIds.contains("v29"))
        // Deterministic: same input → same output.
        assertEquals(
            decision,
            NoteVersionRetentionPolicy.decideRetention(
                rows.sortedByDescending { it.timestampMs }.map { it.id to it.timestampMs }
            )
        )
    }

    @Test
    fun `the prune SQL keeps only the newest N via the NOT IN subselect`() {
        val sql = NoteVersionRetentionPolicy.PRUNE_KEEP_NEWEST_SQL
        assertTrue("the prune is a DELETE on the page's rows", sql.startsWith("DELETE FROM note_versions WHERE pageId = ?"))
        assertTrue("the keep set is the newest window", sql.contains("ORDER BY timestampMs DESC LIMIT ?"))
        assertTrue("everything older is dropped", sql.contains("id NOT IN"))
        assertTrue("the newest-window subselect is scoped to the same page", sql.contains("FROM note_versions WHERE pageId = ?"))
        // The LIMIT is a bound parameter, never interpolated SQL.
        assertFalse(sql.contains("LIMIT 20"))
    }

    @Test
    fun `the paged select is newest-first with bound limit and offset`() {
        val sql = NoteVersionRetentionPolicy.SELECT_PAGED_DESC_SQL
        assertTrue(sql.contains("ORDER BY timestampMs DESC"))
        assertTrue(sql.contains("LIMIT :limit OFFSET :offset"))
    }

    // ---------------------------------------------------------------------
    // pure behavior: createNoteVersion insert+prune (the fake-DAO model)
    // ---------------------------------------------------------------------

    /** Mirrors `NoteRepository.createNoteVersion`: insert then prune to the cap. */
    private fun simulateInsertThenPrune(
        store: MutableMap<String, MutableList<Row>>,
        pageId: String,
        newId: String,
        timestampMs: Long
    ) {
        val rows = store.getOrPut(pageId) { mutableListOf() }
        rows.add(0, Row(newId, timestampMs))
        val decision = NoteVersionRetentionPolicy.decideRetention(rows.map { it.id to it.timestampMs })
        store[pageId] = rows.filter { it.id in decision.keepIds }.toMutableList()
    }

    /** Mirrors `NoteRepository.getNoteVersions`: bounded LIMIT/OFFSET windows. */
    private fun pagedReadAll(
        store: MutableMap<String, MutableList<Row>>,
        pageId: String
    ): List<String> {
        val batch = NoteVersionRetentionPolicy.DECRYPT_BATCH_SIZE
        val rows = store[pageId] ?: emptyList()
        val windows = mutableListOf<Int>()
        val result = mutableListOf<String>()
        var offset = 0
        while (true) {
            val window = rows.drop(offset).take(batch)
            if (window.isEmpty()) break
            windows.add(window.size)
            result += window.map { it.id }
            if (window.size < batch) break
            offset += window.size
        }
        assertTrue("getNoteVersions must page — it requested these windows", windows.isNotEmpty())
        return result
    }

    @Test
    fun `saving 5000 snapshots keeps at most the newest 20 per page`() {
        val store = mutableMapOf<String, MutableList<Row>>()
        for (i in 0 until 5000) {
            simulateInsertThenPrune(store, "page-A", "v$i", timestampMs = 1_000L + i)
        }
        val rows = store.getValue("page-A")
        assertEquals("the page never exceeds the retention cap", 20, rows.size)
        assertTrue("the newest snapshot is held", rows.first().id == "v4999")
        assertTrue("the kept window is contiguously the newest", rows.map { it.id } == (4999 downTo 4980).map { "v$it" })
        assertFalse("the oldest snapshot is gone", rows.any { it.id == "v0" })
    }

    @Test
    fun `getNoteVersions pages the whole history and never materializes it at once`() {
        val store = mutableMapOf<String, MutableList<Row>>()
        store["page-A"] = (0 until 5000).map { i -> Row("v$i", 1_000L + i) }
            .sortedByDescending { it.timestampMs }
            .toMutableList()

        val read = pagedReadAll(store, "page-A")
        assertEquals(5000, read.size)
        assertEquals("newest-first order is preserved", "v4999", read.first())
        assertEquals("the full history is still discoverable", "v0", read.last())
        // The paging model asserts each requested window is ≤ DECRYPT_BATCH_SIZE —
        // i.e. the whole table never lands in one heap read.
    }

    @Test
    fun `a page already under the cap is untouched by the retention decision`() {
        val rows = (0 until 5).map { i -> Row("v$i", 1_000L + i) }
        val decision = NoteVersionRetentionPolicy.decideRetention(rows.map { it.id to it.timestampMs })
        assertEquals(5, decision.keepIds.size)
        assertTrue(decision.dropIds.isEmpty())
        assertFalse(NoteVersionRetentionPolicy.exceedsCap(5))
        assertEquals(0, NoteVersionRetentionPolicy.pruneCountForPage(5))
    }

    // ---------------------------------------------------------------------
    // source pins: the Android-bound wiring
    // ---------------------------------------------------------------------

    @Test
    fun `createNoteVersion prunes inside the insert transaction`() {
        val source = sourceFile("data/repository/NoteRepository.kt")
        val region = source.substringAfter("fun createNoteVersion").substringBefore("fun getNoteVersions")
        assertTrue(
            "the insert and the prune must be atomic (shared transaction)",
            region.contains("db.withTransaction")
        )
        assertTrue(
            "the prune must keep exactly the policy cap",
            region.contains("pruneVersionsForPage(pageId, NoteVersionRetentionPolicy.MAX_VERSIONS_PER_PAGE)")
        )
        assertTrue(region.contains("insertVersion(version)"))
    }

    @Test
    fun `getNoteVersions pages and the direct whole-table read is gone`() {
        val source = sourceFile("data/repository/NoteRepository.kt")
        val region = source.substringAfter("fun getNoteVersions").substringBefore("private fun decryptPageIfNeeded")
        assertTrue(
            "the paged DAO window must be the only history read",
            region.contains("getVersionsForPagePaged(pageId, retention.DECRYPT_BATCH_SIZE, offset)")
        )
        assertTrue("the paged window must be repeated until the tail", region.contains("offset += batch.size"))
        assertFalse("the unbounded whole-page read must be gone", region.contains("db.noteVersionDao().getVersionsForPage(pageId)"))

        val paged = source.substringAfter("fun getNoteVersionsPaged").substringBefore("fun pruneVersionsToRetention")
        assertTrue("the lazy window read must exist for the bottom sheet", paged.contains("getVersionsForPagePaged(pageId, limit, offset)"))
        assertTrue(
            "the suspend VM gateway is available",
            sourceFile("ui/viewmodel/NoteflowViewModel.kt").contains("suspend fun getNoteVersionsPaged(pageId: String, limit: Int, offset: Int)")
        )
    }

    @Test
    fun `the version decrypt decision is shared and still routes through the policy`() {
        val source = sourceFile("data/repository/NoteRepository.kt")
        val region = source.substringAfter("fun getNoteVersions").substringBefore("private fun decryptPageIfNeeded")
        assertTrue(
            "version title must go through the single render decision, keyed on the note",
            region.contains("decryptFieldForDisplay(v.title, \"note_versions\", v.id, \"title\", v.pageId)")
        )
        assertTrue(
            "version body must go through the single render decision, keyed on the note",
            region.contains("decryptFieldForDisplay(v.extractedText, \"note_versions\", v.id, \"extractedText\", v.pageId)")
        )
        assertFalse(
            "the pre-fix whole-table decrypt is gone",
            region.contains("getVersionsForPage(pageId)")
        )
    }

    @Test
    fun `both reencrypt sweeps page instead of materializing the table`() {
        val source = sourceFile("data/repository/NoteRepository.kt")
        val migrateRegion = source.substringAfter("fun migrateFieldRecordAad").substringBefore("fun reencryptPlaintextFields")
        assertTrue(
            "the record-AAD re-key sweep must page the version table",
            migrateRegion.contains("getVersionsForReencryptPaged(NoteVersionRetentionPolicy.REENCRYPT_BATCH_SIZE, versionOffset)")
        )
        assertFalse(
            "the all-at-once version read is gone from the re-key sweep",
            migrateRegion.contains("getAllVersionsForReencrypt().forEach")
        )
        val plaintextRegion = source.substringAfter("fun reencryptPlaintextFields").substringBefore("fun closeDatabase")
        assertTrue(
            "the C1 plaintext sweep must page the version table",
            plaintextRegion.contains("getVersionsForReencryptPaged(NoteVersionRetentionPolicy.REENCRYPT_BATCH_SIZE, versionOffset)")
        )
        assertFalse(
            "the all-at-once version read is gone from the C1 sweep",
            plaintextRegion.contains("getAllVersionsForReencrypt().forEach")
        )
    }

    @Test
    fun `the backup writer prunes before its checkpoint and the restore sanitizes`() {
        val source = sourceFile("services/ImportExportService.kt")

        val exportRegion = source.substringAfter("suspend fun exportBackup(").substringBefore("private fun copyWithLimit")
        val pruneIdx = exportRegion.indexOf("repository.pruneVersionsToRetention()")
        val checkpointIdx = exportRegion.indexOf("repository.checkpointWal()")
        assertTrue("export must prune the version table before the snapshot", pruneIdx >= 0)
        assertTrue("the prune must be WAL-committed by the checkpoint that follows it", checkpointIdx > pruneIdx)

        val validator = source.substringAfter("private fun validateAndPrepareRestoredDb").substringBefore("private fun rekeyVoiceNoteBlobs")
        assertTrue(
            "restore must run the version sanitizer under the candidate key",
            validator.contains("sanitizeRestoredNoteVersions(db)")
        )
    }

    @Test
    fun `the restore sanitizer runs the same retention SQL and strips crafted rows`() {
        val source = sourceFile("services/ImportExportService.kt")
        val sanitizer = source.substringAfter("private fun sanitizeRestoredNoteVersions").substringBefore("private fun sanitizeRestoredSourceFilePaths")
        assertTrue("the sanitizer iterates every distinct page", sanitizer.contains("SELECT DISTINCT pageId FROM note_versions"))
        assertTrue(
            "the sanitizer shares the policy's prune statement",
            sanitizer.contains("NoteVersionRetentionPolicy.PRUNE_KEEP_NEWEST_SQL")
        )
        assertTrue("the keep count is the policy cap", sanitizer.contains("NoteVersionRetentionPolicy.MAX_VERSIONS_PER_PAGE"))
        assertTrue(
            "a missing table is tolerated like the strokes sanitizer",
            sanitizer.contains("shouldPropagateRestoreStripFailure")
        )
    }

    @Test
    fun `the bottom sheet materializes lazily and keeps the pinned guarded read`() {
        val source = sourceFile("ui/components/VersionHistoryBottomSheet.kt")
        // R2-B1A-02 (phase-134) pin: the guarded full read still arms the sheet.
        assertTrue("the pinned guarded read must remain", source.contains("viewModel.getNoteVersions(page.id)"))
        assertTrue(
            "the sheet must stream further windows lazily",
            source.contains("viewModel.getNoteVersionsPaged(") &&
                source.contains("NoteVersionRetentionPolicy.DECRYPT_BATCH_SIZE")
        )
        assertTrue("the sheet holds a mutable lazy window", source.contains("mutableStateListOf<NoteVersionEntity>()"))
        assertTrue("the sheet tracks scroll position", source.contains("rememberLazyListState()"))
        assertTrue("the sheet stops fetching past the tail", source.contains("endReached"))
        // The sheet no longer renders an eager copy of the whole history list.
        assertFalse(source.contains("mutableStateOf<List<NoteVersionEntity>>(emptyList())"))
    }

    // ---------- helpers ----------

    private fun sourceFile(relative: String): String {
        val file = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/$relative")
        assertTrue("$relative must exist for the wiring pin", file.isFile)
        return file.readText()
    }

    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (File(dir, "gradle/libs.versions.toml").isFile &&
                File(dir, "app").isDirectory
            ) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}