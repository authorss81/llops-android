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
 * This policy owns the budget numbers and the SQL the rest of the fix wires to:
 *  - [MAX_VERSIONS_PER_PAGE] — retention cap: how many newest snapshots a page
 *    keeps. Everything older is pruned in `createNoteVersion` (inside the same
 *    transaction as the insert) and on the restore-time sanitize / export-time
 *    staged-snapshot prune.
 *  - [DECRYPT_BATCH_SIZE] — the LIMIT for the paged history/decrypt reads, so
 *    no code path ever materializes the whole table in one heap read. The
 *    Version History bottom sheet renders the first bounded window and streams
 *    the rest lazily on scroll.
 *  - [REENCRYPT_BATCH_SIZE] — the LIMIT for the re-key / re-encrypt sweeps that
 *    still must cover the whole table (migrateFieldRecordAad,
 *    reencryptPlaintextFields) but must never hold it in heap at once.
 *
 * The SQL strings are the SINGLE literal source: the Room `@Query` annotations
 * in `NoteVersionDao` reference [PRUNE_KEEP_NEWEST_ROOM_SQL] and
 * [SELECT_PAGED_DESC_SQL] by name and the raw restore/export sanitizers use
 * [PRUNE_KEEP_NEWEST_SQL], so the retention shape can never drift between the
 * DAO and the raw SQL paths. The `?` / `:name` forms differ only in binding
 * syntax; both keep the same keep-set: the newest [keepNewest] rows ordered by
 * `timestampMs DESC, rowid DESC` (rowid is the insertion tie-break, so a mobile
 * clock that steps backwards — or two snapshots in the same millisecond — can
 * never make retention chooser non-deterministic).
 *
 * Pure-JVM by design (no Room/SQLCipher/Android), so the whole decision table
 * is unit-testable.
 */
object NoteVersionRetentionPolicy {

    /** Newest snapshots retained per page. Older rows are pruned at insert. */
    const val MAX_VERSIONS_PER_PAGE: Int = 20

    /** LIMIT for every paged history read (getNoteVersions / the bottom sheet). */
    const val DECRYPT_BATCH_SIZE: Int = 20

    /** LIMIT for the bounded whole-table re-encrypt / re-key sweeps. */
    const val REENCRYPT_BATCH_SIZE: Int = 100

    /**
     * The raw-SQL prune statement used by the restore-time and export-time
     * sanitizers (`SQLiteDatabase.execSQL` with positional `?` binds): delete
     * every row of a page that is NOT among the [keepNewest] newest — ordered by
     * `timestampMs DESC, rowid DESC` so timestamp ties break deterministically on
     * insertion order. SQLite accepts a bound parameter inside LIMIT, so
     * [keepNewest] is bound, never string-interpolated.
     */
    const val PRUNE_KEEP_NEWEST_SQL: String =
        "DELETE FROM note_versions WHERE pageId = ? AND id NOT IN (" +
            "SELECT id FROM note_versions WHERE pageId = ? ORDER BY timestampMs DESC, rowid DESC LIMIT ?)"

    /**
     * The same prune statement in Room `@Query` syntax (named `:pageId` /
     * `:keepNewest` binds), referenced BY NAME by `NoteVersionDao.pruneVersionsForPage`
     * so the DAO and the raw sanitizer can never drift.
     */
    const val PRUNE_KEEP_NEWEST_ROOM_SQL: String =
        "DELETE FROM note_versions WHERE pageId = :pageId AND id NOT IN (" +
            "SELECT id FROM note_versions WHERE pageId = :pageId ORDER BY timestampMs DESC, rowid DESC LIMIT :keepNewest)"

    /**
     * The bounded newest-first history read in Room `@Query` syntax (named
     * `:pageId` / `:limit` / `:offset` binds), referenced BY NAME by
     * `NoteVersionDao.getVersionsForPagePaged` — this is the production query the
     * Version History bottom sheet pages through.
     */
    const val SELECT_PAGED_DESC_SQL: String =
        "SELECT * FROM note_versions WHERE pageId = :pageId ORDER BY timestampMs DESC, rowid DESC LIMIT :limit OFFSET :offset"

    /** A page already at/below [cap] needs no pruning (checked in the insert txn). */
    fun exceedsCap(count: Int, cap: Int = MAX_VERSIONS_PER_PAGE): Boolean = count > cap
}