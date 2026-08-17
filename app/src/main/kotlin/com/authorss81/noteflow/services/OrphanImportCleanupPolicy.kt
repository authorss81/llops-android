package com.authorss81.noteflow.services

import java.io.File

/**
 * B2-UI-6 (phase-96): pure-JVM decision table for the multi-URI vault import
 * loop's cancel/teardown cleanup on HomeScreen.
 *
 * Each non-text import URI (PDF / image / other binary attachment) is first
 * persisted as an artifact under `filesDir/noteflow/imports`
 * ([ImportExportService.persistFile]) and only afterwards does the loop create
 * its DB page rows. If the importer's coroutine is cancelled between those two
 * steps — historically a vault lock disposed HomeScreen and cancelled its
 * composition-scoped `rememberCoroutineScope()` — the artifact stays behind with
 * no page row referencing it: an orphaned at-rest file (same exposure class as
 * B1-DB-4, but introduced by lifecycle-cancellation TOCTOU rather than the
 * import design) that no UI ever shows and that re-persists on every re-import
 * of the same source.
 *
 * The fix has two halves (both wired by HomeScreen's `processImportedUris`):
 *   1. the loop now runs on the ViewModel scope, so a lock/teardown of the
 *      composition can no longer cancel it mid-iteration in the first place;
 *   2. as defense in depth, every persisted file is tracked by a [Run] until a
 *      page row commits it, and any cancellation sweeps the uncommitted files
 *      so no orphan accumulates even on a genuine abort (e.g. process teardown).
 *
 * All file deletion is best-effort (`runCatching`) — a delete failure must
 * never break the import flow.
 */
object OrphanImportCleanupPolicy {

    /** Human-facing, non-alarming notice for a cancelled import run. */
    const val CANCELLED_NOTICE = "Import cancelled — already-saved pages were kept."

    /**
     * Per-run tracker of persisted files → committed-by-a-page-row status.
     * Pure JVM. A production instance lives for exactly one import run.
     */
    class Run {
        private val persisted = LinkedHashMap<String, Boolean>()

        /** A URL/uri-derived file was just persisted by [ImportExportService.persistFile]. */
        fun trackPersisted(path: String) {
            if (path.isNotBlank()) persisted[path] = false
        }

        /** A page row now references [path]; it is committed and must never be swept. */
        fun markCommitted(path: String) {
            if (path in persisted) persisted[path] = true
        }

        /** True iff [path] is tracked but no page row was ever committed for it. */
        fun isOrphan(path: String): Boolean = persisted[path] == false

        /** Paths tracked so far but still waiting on (or failed to get) a page row. */
        fun pendingOrphans(): List<String> = persisted.filterValues { !it }.keys.toList()

        /**
         * Delete a single orphaned file best-effort. Returns whether it was
         * actually removed. No-op for unknown/committed paths.
         */
        fun sweep(path: String): Boolean {
            if (!isOrphan(path)) return false
            return runCatching { File(path).delete() }.getOrDefault(false)
        }

        /** Delete every tracked file with no committed page row; returns removed paths. */
        fun sweepOrphans(): List<String> = pendingOrphans().filter { sweep(it) }

        /** Start of a new import run. */
        fun clear() = persisted.clear()
    }
}