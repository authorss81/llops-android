package com.authorss81.noteflow.services

/**
 * R2-b2b4-DOS-01 (phase-149): every knob that bounds the `note_versions` table.
 *
 * The finding: a full title + full extractedText snapshot is written on EVERY
 * manual save / autosave / before-translation-replace with NO pruning in the
 * insert path, the history read decrypts EVERY stored body at once, the export
 * serializes the WHOLE table and the restore re-encrypts the whole table in
 * heap. A crafted backup holding ~5,000 rows × ~50 KB bodies grows to ~250 MB
 * in heap on Version History open → OOM.
 *
 * This policy owns the three budget numbers the rest of the fix wires to:
 *  - [MAX_VERSIONS_PER_PAGE] — retention cap: how many newest snapshots a page
 *    keeps. Everything older is pruned in `createNoteVersion` (inside the same
 *    transaction as the insert), at export time, and on restore sanitize.
 *  - [DECRYPT_BATCH_SIZE] — the LIMIT for the paged history/decrypt reads, so
 *    no code path ever materializes the whole table in one heap read. The
 *    Version History bottom sheet renders the first window and streams the rest
 *    lazily on scroll.
 *  - [REENCRYPT_BATCH_SIZE] — the LIMIT for the re-key / re-encrypt sweeps that
 *    still must cover the whole table (migrateFieldRecordAad,
 *    reencryptPlaintextFields) but must never hold it in heap at once.
 *
 * Pure-JVM by design (no Room/SQLCipher/Android), so the whole decision table
 * is unit-testable and the SQL strings are the single literal source used by
 * both the Room DAO annotations and the raw restore sanitizer.
 */
object NoteVersionRetentionPolicy {

    /** Newest snapshots retained per page. Older rows are pruned at insert. */
    const val MAX_VERSIONS_PER_PAGE: Int = 20

    /** LIMIT for every paged history read (getNoteVersions / the bottom sheet). */
    const val DECRYPT_BATCH_SIZE: Int = 20

    /** LIMIT for the bounded whole-table re-encrypt / re-key sweeps. */
    const val REENCRYPT_BATCH_SIZE: Int = 100

    /**
     * The prune statement executed by both the Room DAO and the restore-time
     * raw-SQL sanitizer: delete every row of a page that is NOT among the
     * [keepNewest] newest (timestampMs ORDER BY DESC), keeping the newest ones.
     * SQLite accepts a bound parameter inside LIMIT, so [keepNewest] is bound,
     * never string-interpolated.
     */
    const val PRUNE_KEEP_NEWEST_SQL: String =
        "DELETE FROM note_versions WHERE pageId = ? AND id NOT IN (" +
            "SELECT id FROM note_versions WHERE pageId = ? ORDER BY timestampMs DESC LIMIT ?)"

    /**
     * The paged newest-first story read. Bound [limit]/[offset] placeholders are
     * the syntax of the Room @Query that realizes this shape; the sanitizer and
     * the pure-JVM tests reference the policy object for the constants instead.
     */
    const val SELECT_PAGED_DESC_SQL: String =
        "SELECT * FROM note_versions WHERE pageId = ? ORDER BY timestampMs DESC LIMIT :limit OFFSET :offset"

    /** A page already at/below [cap] needs no pruning. */
    fun exceedsCap(count: Int, cap: Int = MAX_VERSIONS_PER_PAGE): Boolean = count > cap

    /** How many of the OLDEST rows a page with [pageCount] rows must drop to fit [cap]. */
    fun pruneCountForPage(pageCount: Int, cap: Int = MAX_VERSIONS_PER_PAGE): Int =
        (pageCount - cap).coerceAtLeast(0)

    /**
     * Pure retention decision: given a page's rows newest-first (id, timestampMs),
     * which stay and which go under [cap]. Insertion ties on timestampMs are
     * broken by the newest-first input order (matching the DAO's ORDER BY DESC,
     * which Room keeps stable per database insertion order).
     */
    data class RetentionDecision(val keepIds: Set<String>, val dropIds: List<String>)

    fun decideRetention(
        rowsNewestFirst: List<Pair<String, Long>>,
        cap: Int = MAX_VERSIONS_PER_PAGE
    ): RetentionDecision {
        val keepIds = rowsNewestFirst.take(cap).map { it.first }.toSet()
        val dropIds = rowsNewestFirst.drop(cap).map { it.first }
        return RetentionDecision(keepIds, dropIds)
    }
}