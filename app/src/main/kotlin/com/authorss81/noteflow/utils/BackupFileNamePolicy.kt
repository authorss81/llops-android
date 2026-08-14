package com.authorss81.noteflow.utils

import java.security.SecureRandom
import java.time.LocalDate

/**
 * B2-CRYPTO-06 (phase-106): public/remote backup & sync filenames must never
 * embed epoch-millis. The old names (`noteflow_backup_${System.currentTimeMillis()}.noteflow`
 * and `noteflow_vault_backup_${System.currentTimeMillis()}.nfb`) leaked the exact
 * second of the last backup/sync to anyone who can list public `/Download` or the
 * user's WebDAV folder — a direct proxy for "when the user last used/closed the
 * vault" that lives entirely OUTSIDE the app sandbox and outlives vault clearing.
 *
 * This policy produces names with day-granularity (`yyyy-MM-dd`) plus a random
 * token. Naming rules:
 * - NEVER epoch-millis (or epoch-seconds) anywhere in a public/remote name.
 * - keep the `noteflow_backup_` / `noteflow_vault_backup_` prefixes and the
 *   `.noteflow` / `.nfb` suffixes so the existing WebDAV download listing regex
 *   (`noteflow_vault_backup_[^<]+\.nfb`) keeps matching both old and new files.
 * - day-granularity is the coarsest supported time unit; the random token makes
 *   same-day names collision-free (two backups in a day never overwrite).
 *
 * Pure JVM (`java.security.SecureRandom` + `java.time.LocalDate` are both
 * available on the API-26 floor) so the policy is fully unit-testable without
 * Android.
 */
object BackupFileNamePolicy {
    private const val TOKEN_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private const val TOKEN_LENGTH = 10
    private const val LOCAL_EXTENSION = "noteflow"
    private const val REMOTE_EXTENSION = "nfb"

    private val random = SecureRandom()

    /** Day-granular ISO stamp (`yyyy-MM-dd`), the only time unit allowed in output names. */
    fun dayStamp(date: LocalDate = LocalDate.now()): String = date.toString()

    /** Random alphanumeric token used to keep same-day names collision-free. */
    fun randomToken(): String = buildString(TOKEN_LENGTH) {
        repeat(TOKEN_LENGTH) { append(TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)]) }
    }

    /**
     * Public backup archive name that lands in `/Download` (local export path).
     * e.g. `noteflow_backup_2026-08-14_Ax9kC2mQpz.noteflow`
     */
    fun localBackupFileName(date: LocalDate = LocalDate.now(), token: String = randomToken()): String =
        "noteflow_backup_${dayStamp(date)}_$token.$LOCAL_EXTENSION"

    /**
     * Remote backup archive name pushed to the user's WebDAV/Nextcloud server.
     * e.g. `noteflow_vault_backup_2026-08-14_Ax9kC2mQpz.nfb`
     */
    fun remoteVaultBackupFileName(date: LocalDate = LocalDate.now(), token: String = randomToken()): String =
        "noteflow_vault_backup_${dayStamp(date)}_$token.$REMOTE_EXTENSION"
}