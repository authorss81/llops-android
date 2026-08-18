package com.authorss81.noteflow.services

/**
 * R2-B1P-02 (phase-141): the SAF export staging-cleanup decision table.
 *
 * Pure JVM so the every-outcome cleanup contract is unit-testable without
 * Android. `SaFExporter`'s picker callback feeds this function the raw
 * ActivityResult facts and performs the returned [Cleanup] (see
 * `ui/components/SaFExporter.kt`).
 *
 * Motivation (docs/security-report-round2.md R2-B1P-02): phase-59 wired
 * transfer-then-delete only when `resultCode == RESULT_OK`, so (a) a CANCELLED
 * whole-vault PLAINTEXT export left the entire decrypted vault staged under
 * the per-kind `_exports` dirs in the app cache (`cacheDir`) — the cancel/
 * no-data branches called `done(false)` without touching the file — and (b) the
 * success path deleted the staging copy even when the SAF `copyTo` FAILED
 * (`ok == false`), silently destroying the export the user DID generate on a
 * transient I/O error.
 *
 * Contract (every picker outcome):
 *  - `RESULT_OK` + destination URI + copy SUCCEEDED → [Cleanup.DELETE] (bytes
 *    moved to the user-picked destination; drop the app-private staging).
 *  - `RESULT_OK` + destination URI + copy FAILED → [Cleanup.KEEP] (never
 *    destroy a fresh export; the caller can retry the picker with the same file).
 *  - `RESULT_OK` + destination URI + NO copy ran (unreachable in the wired
 *    flow) → [Cleanup.KEEP] (ambiguous; never gamble away a user-visible
 *    export over an impossible state).
 *  - `RESULT_OK` + NO destination URI → [Cleanup.DELETE] (nothing was written).
 *  - NOT `RESULT_OK` (user cancelled / dismiss) → [Cleanup.DELETE] (nothing was
 *    written, and a decrypted archive must not linger in the app cache).
 */
object ExportStagingPolicy {

    /** Android's `Activity.RESULT_OK` — kept local to stay pure JVM. */
    const val RESULT_OK = -1

    enum class Cleanup { DELETE, KEEP }

    /**
     * @param resultCode the SAF picker's `resultCode` (equals [RESULT_OK] when
     *   the destination was confirmed).
     * @param destinationUriPresent whether `result.data?.data` was non-null.
     * @param copySucceeded `true`/`false` when the destination write ran;
     *   `null` when no write was attempted (cancel / no-data).
     */
    fun cleanupAfterSaF(
        resultCode: Int,
        destinationUriPresent: Boolean,
        copySucceeded: Boolean?
    ): Cleanup = when {
        resultCode == RESULT_OK && destinationUriPresent && copySucceeded == true -> Cleanup.DELETE
        resultCode == RESULT_OK && destinationUriPresent && copySucceeded == false -> Cleanup.KEEP
        resultCode == RESULT_OK && destinationUriPresent -> Cleanup.KEEP
        resultCode == RESULT_OK -> Cleanup.DELETE
        else -> Cleanup.DELETE
    }
}