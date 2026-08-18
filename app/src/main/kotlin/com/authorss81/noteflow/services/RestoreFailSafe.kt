package com.authorss81.noteflow.services

/**
 * R2-B1D-04 (phase-138): the guaranteed post-restore-failure reopen, as a pure
 * JVM seam so the recover logic is unit-testable with closures (a "fake
 * repository") instead of Room.
 *
 * Every restore entry point closes the live vault BEFORE the swap. If ANY
 * failure happens after that close — a wrong backup password, a corrupt DB, an
 * over-budget archive, a mid-swap IO error, or an unchecked Throwable (the OOM
 * the old in-heap decrypt could trigger) — the vault must be REOPENED so the
 * app lands in a clean, recoverable state. `closeDatabase` + `reopenDatabase`
 * are supplied by the production caller as `NoteRepository` (whose
 * reopenDatabase = `NoteflowDatabase.dispose()` + a fresh instance, phase-09
 * H1), and by the unit test as plain counters — both prove the SAME invariant:
 * a failure after the close never leaves the repository closed.
 */
object RestoreFailSafe {

    /**
     * Closes, runs [restore] (suspending, since the clean import path is
     * suspending), and on ANY [Throwable] best-effort reopens (a reopen failure
     * is absorbed so it never masks the real restore error the caller is about
     * to surface) then rethrows untouched. On success the repository is
     * intentionally left CLOSED — the caller swaps the restored vault and
     * restarts the process.
     */
    suspend fun <R> guaranteeReopenAfterRestore(
        closeDatabase: () -> Unit,
        restore: suspend () -> R,
        reopenDatabase: () -> Unit
    ): R {
        closeDatabase()
        try {
            return restore()
        } catch (t: Throwable) {
            runCatching { reopenDatabase() }
            throw t
        }
    }
}