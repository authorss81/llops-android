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
 * path (`NoteflowViewModel.createNoteVersion` → `NoteRepository.createNoteVersion`),
 * the history read decrypts EVERY stored body at once
 * (`getNoteVersions` → `VersionHistoryBottomSheet`), the backup serializes the
 * WHOLE table and a cross-device restore re-encrypts the whole table in heap. A
 * crafted backup holding ~5,000 rows × ~50 KB bodies grows to ~250 MB in heap on
 * Version History open → OOM.
 *
 * Fix shape (all provable on the pure JVM — no Room/SQLCipher):
 *  - [NoteVersionRetentionPolicy] owns the budgets, the retention SQL (the SINGLE
 *    literal wired into the Room `@Query` annotations AND the raw restore/export
 *    sanitizers) and the cap gate; ties on `timestampMs` break on `rowid`
 *    (insertion order) so retention is deterministic.
 *  - `createNoteVersion` inserts AND prunes to the newest N inside ONE transaction
 *    (prune gated on the post-insert count so the DELETE never runs below the cap) —
 *    simulated with a fake store below (the DAO is otherwise Room-bound, exactly
 *    like the StrokesGeometry list-gate tests).
 *  - `getNoteVersions` returns ONLY the first bounded window; the bottom sheet
 *    streams further windows via `getNoteVersionsPaged` (LIMIT/OFFSET) — the
 *    history is never decrypted whole on open.
 *  - the export writer prunes the STAGED SNAPSHOT (never the live vault) and the
 *    restore sanitizer strips a crafted table under the candidate key BEFORE re-key.
 *
 * The Android-bound wiring is pinned at source level below.
 */
class Phase149NoteVersionsRetentionTest {

    private data class Row(val id: String, val timestampMs: Long, val rowid: Int)

    // ---------------------------------------------------------------------
    // pure policy: budgets + the retention SQL shape
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
    }

    /** Mirrors the production keep-set: newest [MAX_VERSIONS_PER_PAGE] by time, ties by rowid. */
    private fun keepNewestModel(rows: List<Row>): List<Row> =
        rows.sortedWith(compareByDescending<Row> { it.timestampMs }.thenByDescending { it.rowid })
            .take(NoteVersionRetentionPolicy.MAX_VERSIONS_PER_PAGE)

    @Test
    fun `the retention model keeps the newest N and drops exactly the oldest tail`() {
        val rows = (0 until 50).map { i -> Row(id = "v$i", timestampMs = 1_000L + i * 10L, rowid = i) }
        val kept = keepNewestModel(rows)

        assertEquals(NoteVersionRetentionPolicy.MAX_VERSIONS_PER_PAGE, kept.size)
        // The 20 highest-timestamp rows survive, newest first.
        assertEquals("v49", kept.first().id)
        assertEquals((49 downTo 30).map { "v$it" }, kept.map { it.id })
        assertFalse(kept.any { it.id == "v0" })
        assertFalse(kept.any { it.id == "v29" })
        // Deterministic: same input → same output.
        assertEquals(kept.map { it.id }, keepNewestModel(rows).map { it.id })
    }

    @Test
    fun `same-millisecond snapshots break ties by insertion order`() {
        // All rows share one timestamp — the keep-set must be the LATEST-INSERTED
        // 20 (highest rowid), exactly what `ORDER BY timestampMs DESC, rowid DESC`
        // gives the production prune.
        val rows = (0 until 25).map { i -> Row(id = "v$i", timestampMs = 5_000L, rowid = i) }
        val kept = keepNewestModel(rows)
        assertEquals(20, kept.size)
        assertEquals((24 downTo 5).map { "v$it" }, kept.map { it.id })
        assertFalse(kept.any { it.id == "v0" })
    }

    @Test
    fun `the prune SQL keeps only the newest N via the NOT IN subselect`() {
        val sql = NoteVersionRetentionPolicy.PRUNE_KEEP_NEWEST_SQL
        assertTrue("the prune is a DELETE on the page's rows", sql.startsWith("DELETE FROM note_versions WHERE pageId = ?"))
        assertTrue("the keep-set is the newest window, ties broken by insertion order", sql.contains("ORDER BY timestampMs DESC, rowid DESC LIMIT ?"))
        assertTrue("everything older is dropped", sql.contains("id NOT IN"))
        assertTrue("the newest-window subselect is scoped to the same page", sql.contains("FROM note_versions WHERE pageId = ?"))
        // The LIMIT is a bound parameter, never interpolated SQL.
        assertFalse(sql.contains("LIMIT 20"))
    }

    @Test
    fun `the paged select is newest-first with bound limit offset and the DAO uses the policy literal`() {
        val sql = NoteVersionRetentionPolicy.SELECT_PAGED_DESC_SQL
        assertTrue(sql.contains("ORDER BY timestampMs DESC, rowid DESC"))
        assertTrue(sql.contains("LIMIT :limit OFFSET :offset"))
        assertTrue("the paged read is scoped to one page", sql.contains("pageId = :pageId"))
        // Single source of truth: the DAO @Query annotations reference the policy
        // constants BY NAME, so the Room query can never drift from the raw SQL.
        val daos = sourceFile("data/db/Daos.kt")
        assertTrue("the DAO paged read must be the policy's literal", daos.contains("@Query(NoteVersionRetentionPolicy.SELECT_PAGED_DESC_SQL)"))
        assertTrue("the DAO prune must be the policy's literal", daos.contains("@Query(NoteVersionRetentionPolicy.PRUNE_KEEP_NEWEST_ROOM_SQL)"))
    }

    // ---------------------------------------------------------------------
    // pure behavior: createNoteVersion insert+prune (the fake-DAO model)
    // ---------------------------------------------------------------------

    private fun simulateInsertThenPrune(
        store: MutableMap<String, MutableList<Row>>,
        pageId: String,
        newId: String,
        timestampMs: Long,
        rowid: Int
    ) {
        val rows = store.getOrPut(pageId) { mutableListOf() }
        val kept = keepNewestModel(rows + Row(newId, timestampMs, rowid))
        store[pageId] = kept
            .sortedWith(compareByDescending<Row> { it.timestampMs }.thenByDescending { it.rowid })
            .toMutableList()
    }

    /** Mirrors the bottom sheet's streaming read: bounded LIMIT/OFFSET windows. */
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
            assertTrue("a streaming window is never bigger than the batch cap", window.size <= batch)
            windows.add(window.size)
            result += window.map { it.id }
            if (window.size < batch) break
            offset += window.size
        }
        assertTrue("streaming must page — it requested these windows", windows.isNotEmpty())
        return result
    }

    @Test
    fun `saving 5000 snapshots keeps at most the newest 20 per page`() {
        val store = mutableMapOf<String, MutableList<Row>>()
        for (i in 0 until 5000) {
            simulateInsertThenPrune(store, "page-A", "v$i", timestampMs = 1_000L + i, rowid = i)
        }
        val rows = store.getValue("page-A")
        assertEquals("the page never exceeds the retention cap", 20, rows.size)
        assertTrue("the newest snapshot is held", rows.first().id == "v4999")
        assertTrue("the kept window is contiguously the newest", rows.map { it.id } == (4999 downTo 4980).map { "v$it" })
        assertFalse("the oldest snapshot is gone", rows.any { it.id == "v0" })
    }

    @Test
    fun `history open reads only the first bounded window`() {
        // Mirrors getNoteVersions: the initial read is the newest DECRYPT_BATCH_SIZE
        // rows and NEVER the whole history.
        val rows = (0 until 100).map { i -> Row("v$i", 1_000L + i, i) }
            .sortedWith(compareByDescending<Row> { it.timestampMs }.thenByDescending { it.rowid })
        val initial = rows.take(NoteVersionRetentionPolicy.DECRYPT_BATCH_SIZE)
        assertEquals(NoteVersionRetentionPolicy.DECRYPT_BATCH_SIZE, initial.size)
        assertEquals("the first window is the newest snapshot", "v99", initial.first().id)
        assertEquals("the first window never spills the older rows", 20, initial.size)
    }

    @Test
    fun `the full history is only reachable through bounded streaming windows`() {
        val store = mutableMapOf<String, MutableList<Row>>()
        store["page-A"] = (0 until 5000).map { i -> Row("v$i", 1_000L + i, i) }
            .sortedWith(compareByDescending<Row> { it.timestampMs }.thenByDescending { it.rowid })
            .toMutableList()

        val read = pagedReadAll(store, "page-A")
        assertEquals(5000, read.size)
        assertEquals("newest-first order is preserved", "v4999", read.first())
        assertEquals("the full history is still discoverable by streaming", "v0", read.last())
    }

    @Test
    fun `a page already under the cap is untouched by the retention model`() {
        val rows = (0 until 5).map { i -> Row("v$i", 1_000L + i, rowid = i) }
        val kept = keepNewestModel(rows)
        assertEquals(5, kept.size)
        assertFalse(NoteVersionRetentionPolicy.exceedsCap(5))
        assertEquals(0, (5 - NoteVersionRetentionPolicy.MAX_VERSIONS_PER_PAGE).coerceAtLeast(0))
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
        assertTrue(
            "the prune must be gated on the policy cap check (count before delete)",
            region.contains("NoteVersionRetentionPolicy.exceedsCap(")
        )
        assertTrue(region.contains("insertVersion(version)"))
    }

    @Test
    fun `getNoteVersions is a single bounded window and the whole-table read is gone`() {
        val source = sourceFile("data/repository/NoteRepository.kt")
        val region = source.substringAfter("fun getNoteVersions").substringBefore("private fun decryptPageIfNeeded")
        assertTrue(
            "the initial read must be the single bounded window",
            region.contains("getVersionsForPagePaged(") &&
                region.contains("NoteVersionRetentionPolicy.DECRYPT_BATCH_SIZE,") &&
                region.contains("decryptVersionForDisplay(")
        )
        assertFalse("the whole-table while-loop must be gone", region.contains("while (true)"))
        assertFalse("the unbounded whole-page read must be gone", region.contains("db.noteVersionDao().getVersionsForPage(pageId)"))

        val paged = source.substringAfter("fun getNoteVersionsPaged").substringBefore("private fun decryptVersionForDisplay")
        assertTrue("the streaming window read must exist for the bottom sheet", paged.contains("getVersionsForPagePaged(pageId, limit, offset)"))
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
        assertTrue("the shared decrypt helper must be the only decrypt path", region.contains("private fun decryptVersionForDisplay"))
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
    fun `the backup writer prunes the STAGED snapshot and never the live vault`() {
        val source = sourceFile("services/ImportExportService.kt")

        val exportRegion = source.substringAfter("suspend fun exportBackup(").substringBefore("private fun copyWithLimit")
        val copyIdx = exportRegion.indexOf("VaultSnapshotCopyPolicy.checkpointThenCopy(dbFile, stagedDb)")
        val pruneIdx = exportRegion.indexOf("pruneStagedSnapshotVersions(stagedDb)")
        assertTrue("export must run the verified snapshot copy first", copyIdx >= 0)
        assertTrue(
            "the retention prune must run on the STAGED snapshot, after the copy",
            pruneIdx > copyIdx
        )
        assertFalse(
            "the destructive live-vault prune must be gone from the export path",
            exportRegion.contains("repository.pruneVersionsToRetention()")
        )

        val stagedPrune = source.substringAfter("private fun pruneStagedSnapshotVersions").substringBefore("private fun ByteArray.toSqlcipherPassphraseBytes")
        assertTrue("the staged prune must open only the staged snapshot copy", stagedPrune.contains("openOrCreateDatabase(") && stagedPrune.contains("stagedDb, passphrase"))
        assertTrue("the staged prune must be keyed by the in-memory DEK", stagedPrune.contains("VaultKeyHolder.dek"))
        assertTrue("the staged prune must share the retention SQL", stagedPrune.contains("pruneVersionPagesToRetention(db)"))
        assertTrue("the staged prune must never touch the live repository", !stagedPrune.contains("repository."))

        val validator = source.substringAfter("private fun validateAndPrepareRestoredDb").substringBefore("private fun rekeyVoiceNoteBlobs")
        assertTrue(
            "restore must run the version sanitizer under the candidate key",
            validator.contains("sanitizeRestoredNoteVersions(db)")
        )
    }

    @Test
    fun `the raw sanitizer shares the retention SQL and restore wraps the shared core`() {
        val source = sourceFile("services/ImportExportService.kt")
        val sanitizer = source.substringAfter("private fun pruneVersionPagesToRetention").substringBefore("private fun sanitizeRestoredSourceFilePaths")
        assertTrue("the sanitizer core iterates every distinct page", sanitizer.contains("SELECT DISTINCT pageId FROM note_versions"))
        assertTrue(
            "the sanitizer core shares the policy's prune statement",
            sanitizer.contains("NoteVersionRetentionPolicy.PRUNE_KEEP_NEWEST_SQL")
        )
        assertTrue("the keep count is the policy cap", sanitizer.contains("NoteVersionRetentionPolicy.MAX_VERSIONS_PER_PAGE"))

        val restoreWrapper = source.substringAfter("private fun sanitizeRestoredNoteVersions").substringBefore("private fun pruneStagedSnapshotVersions")
        assertTrue(
            "a missing table is tolerated like the strokes sanitizer",
            restoreWrapper.contains("shouldPropagateRestoreStripFailure")
        )
        assertTrue("the restore sanitizer delegates to the shared prune core", restoreWrapper.contains("pruneVersionPagesToRetention(db)"))
    }

    @Test
    fun `the bottom sheet materializes lazily and keeps the pinned guarded read`() {
        val source = sourceFile("ui/components/VersionHistoryBottomSheet.kt")
        // R2-B1A-02 (phase-134) pin: the guarded initial-window read still arms the sheet.
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