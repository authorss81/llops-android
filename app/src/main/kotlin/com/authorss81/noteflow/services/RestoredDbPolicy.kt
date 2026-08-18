package com.authorss81.noteflow.services

/**
 * R2-B1D-02 (phase-135): pure-JVM decision table for "is the restored SQLCipher
 * database copy a real vault backup worth swapping in?".
 *
 * A freshly-initialized EMPTY SQLCipher database satisfies every pre-swap gate
 * that existed before this phase: it opens under the backup/current DEK,
 * `PRAGMA integrity_check` returns "ok", and `PRAGMA user_version` is 0 — which
 * `checkRestoredSchemaNotNewer` only accepts (0 < 9). `PRAGMA rekey` even
 * materializes the header, so the file is non-empty by the time the HMAC
 * baseline is re-armed and `commitRestoredFiles` swaps it over the live vault —
 * the victim's notes are irreversibly replaced by an empty database carrying the
 * app's own HMAC blessing.
 *
 * The gate therefore combines (a) the expected Room schema tables being present
 * in `sqlite_master`, (b) a `user_version` inside the accepted range (a real
 * app-created vault is ALWAYS >= 1 — Room stamps the schema version on first
 * create, so 0 means "never initialized"), and (c) a non-zero page row count (a
 * zero-row vault is only restorable with explicit user confirmation — "start
 * fresh"). Any failure means the restore ABORTS pre-swap and the incoming file
 * is quarantined — never re-armed, never swapped.
 *
 * Pure JVM (no Android/Room/SQLCipher references) so the whole decision is
 * unit-testable on the CI runner.
 */
object RestoredDbPolicy {

    /** Every real app vault (schema >= 1) carries these Room entity tables. */
    val REQUIRED_TABLES: List<String> =
        listOf("pages", "strokes", "note_versions", "media_embeds")

    /**
     * The lowest `user_version` a genuine app-created vault can carry. Room
     * stamps the schema version on first create, so a value below this means a
     * blank/never-initialized SQLCipher file — not a backup worth swapping in.
     */
    const val MIN_USER_VERSION = 1L

    /** Smallest plausible on-disk size of a real Room SQLCipher database. */
    const val MIN_DB_FILE_BYTES = 4096L

    sealed interface Decision {
        /** The copy is a real, populated vault — safe to re-key/migrate/swap. */
        data object Pass : Decision

        /** Structurally not a vault backup (missing schema, blank DB) — reject + quarantine. */
        data class Reject(val reason: String) : Decision

        /**
         * A real schema but zero notes — restoring silently wipes a populated
         * vault. Allowed only when the caller explicitly confirmed "start fresh".
         */
        data object EmptyVault : Decision
    }

    fun decide(
        userVersion: Long,
        presentTableCount: Int,
        pageCount: Long,
        allowEmptyVault: Boolean
    ): Decision {
        if (presentTableCount < REQUIRED_TABLES.size) {
            return Decision.Reject(
                "Restore rejected: the backup's database is missing its core tables " +
                    "($presentTableCount/${REQUIRED_TABLES.size} of ${REQUIRED_TABLES.joinToString()} " +
                    "found) — it is not a valid vault backup."
            )
        }
        if (userVersion < MIN_USER_VERSION) {
            return Decision.Reject(
                "Restore rejected: the backup's database is empty or was never initialized " +
                    "(schema version $userVersion). Restoring it would replace your entire vault with nothing."
            )
        }
        if (pageCount == 0L) {
            return if (allowEmptyVault) Decision.Pass else Decision.EmptyVault
        }
        return Decision.Pass
    }
}