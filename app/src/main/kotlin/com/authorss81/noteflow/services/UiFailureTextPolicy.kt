package com.authorss81.noteflow.services

/**
 * R2-b2b3-LOG-01 (phase-148): restore/recovery/backup failure surfaces may only
 * reach the user as FIXED text — never the raw `${e.message}`. Pre-fix, a
 * crafted NFLB3 archive whose inner zip entry name was hostile was interpolated
 * verbatim into `Backup contains unsafe relative path: $entryName` and rendered
 * raw in the restart dialog / recovery screens, and genuine SQLCipher/file-IO
 * failures could carry `/data/user/0/...` paths into UI text.
 *
 * This policy is the phase-71/94 pattern (`FailureLogPolicy` B2-LOG-03 +
 * `WebDavFailurePolicy` B2-LOG-05) applied to the remaining UI text surfaces:
 *  - [restoreFailureMessage] / [recoveryMessage] classify an import-restore
 *    failure into a fixed, user-meaningful sentence by matching our OWN fixed
 *    code-derived messages; anything unrecognized maps to a fixed generic
 *    string. The OUTPUT is always one of the constants below — never a fragment
 *    of [e.message] — so attacker-carried text and absolute vault paths can
 *    never be echoed, even by misclassification.
 *  - [backupFailureMessage] does the same for the standalone backup producer;
 *  - [importSkippedMessage] renders oversized/bombed archive skips;
 *  - [scrubForUi] is the defense-in-depth sanitizer for any raw text that must
 *    still be rendered: strips URL userinfo/query tokens and collapses
 *    `scheme://host/path` to `host`, and redacts absolute filesystem paths.
 *
 * Pure JVM (`java.util.regex` only), API 26+ floor — no fallback needed.
 */
object UiFailureTextPolicy {

    /** Fixed generic "restore failed" fallback (unknown failure). */
    const val RESTORE_FAILED_GENERIC: String =
        "Restore failed. Your vault was left unchanged."

    /** Fixed generic "backup failed" fallback (unknown failure). */
    const val BACKUP_FAILED_GENERIC: String =
        "Backup failed — please try again."

    /** Fixed generic "recovery failed" fallback (recovery screens). */
    const val RECOVERY_FAILED_GENERIC: String =
        "Recovery failed. Your vault is unchanged — choose the backup again and retry."

    /** Fixed generic "import skipped" fallback. */
    const val IMPORT_SKIPPED_GENERIC: String =
        "Import skipped — the file is too large or is not a supported archive."

    /** Fixed "wrong/needed backup password" restore text. */
    const val RESTORE_PASSWORD_TEXT: String =
        "Restore failed: the backup password is incorrect or was not entered."

    /** Fixed "plain unsigned zip" restore refusal. */
    const val RESTORE_UNENCRYPTED_TEXT: String =
        "Restore rejected: this is an unencrypted (unsigned) backup. Only password-protected or device-keyed backups can be restored."

    /** Fixed "hostile archive path" restore refusal — NEVER the entry name. */
    const val RESTORE_UNSAFE_PATH_TEXT: String =
        "Restore rejected: the backup contains a file path that escapes the vault directory."

    /** Fixed "no database in archive" restore refusal. */
    const val RESTORE_NO_DATABASE_TEXT: String =
        "Restore rejected: the backup contains no database — it is not a valid backup."

    /** Fixed "empty database" restore refusal. */
    const val RESTORE_EMPTY_DATABASE_TEXT: String =
        "Restore rejected: the backup's database is empty."

    /** Fixed "corrupt backup" restore refusal. */
    const val RESTORE_CORRUPT_TEXT: String =
        "Restore rejected: the backup appears corrupted or was created on a different device."

    /** Fixed "newer app version" restore refusal. */
    const val RESTORE_NEWER_APP_TEXT: String =
        "Restore rejected: this backup was created by a newer version of the app. Update the app first, then restore."

    /** Fixed "too large" restore/backup refusal. */
    const val BACKUP_TOO_LARGE_TEXT: String =
        "The backup is too large to restore or export."

    /** Fixed "empty vault" refusal (valid schema, zero pages). */
    const val RESTORE_EMPTY_VAULT_TEXT: String =
        "The downloaded backup contains an empty vault — nothing was restored."

    /** Fixed "vault locked mid-restore" text. */
    const val RESTORE_LOCKED_TEXT: String =
        "Restore failed: the vault locked during the restore — unlock the vault and try again."

    /** Fixed "unreadable selected file" text. */
    const val RESTORE_UNREADABLE_FILE_TEXT: String =
        "Restore failed: the selected backup file could not be read."

    /** Fixed "no data key on device" restore refusal. */
    const val RESTORE_NO_DEVICE_KEY_TEXT: String =
        "This backup cannot be restored on this device — no vault data key is available."

    /** Fixed "rows could not be re-keyed" restore refusal (phase-169). */
    const val RESTORE_REENCRYPT_FAIL_TEXT: String =
        "Restore rejected: some notes in this backup could not be re-encrypted for this device. " +
            "Your vault was left unchanged — restore a backup created on this device instead."

    private val PATH_TOKEN_REGEX = Regex(
        // Android app-private + shared/unix roots — group 1 keeps a short label.
        "((?:/data/(?:user/\\d+|data|app))|/(?:storage|sdcard|home|tmp))" +
            "[\\\\/][\\p{Alnum} _.#@%+~={}:()\\[\\]-]+" +
            "(?:[\\\\/][\\p{Alnum} _.#@%+~={}:()\\[\\]-]+)*" +
            // Windows drive paths (C:\Users\name\...).
            "|[A-Za-z]:[\\\\/][\\p{Alnum} _.#@%+~={}:()\\[\\]-]+" +
            "(?:[\\\\/][\\p{Alnum} _.#@%+~={}:()\\[\\]-]+)*" +
            // UNC share paths (\\server\share\file...).
            "|//[^/\\s]+[\\\\/][\\p{Alnum} _.#@%+~={}:()\\[\\]-]+" +
            "(?:[\\\\/][\\p{Alnum} _.#@%+~={}:()\\[\\]-]+)*"
    )
    private val URL_USERINFO_REGEX = Regex("([A-Za-z][A-Za-z0-9+.-]*://)[^/@\\s]*@")
    private val URL_TOKEN_REGEX = Regex("([A-Za-z][A-Za-z0-9+.-]*://)([^/\\s]+)(/\\S*)?")
    private val QUERY_TOKEN_REGEX = Regex("[?&][^\\s&=]+=[^\\s&]*")

    /** Message text used ONLY for classification — never surfaced directly. */
    private fun messageOf(e: Throwable): String = e.message.orEmpty()

    /**
     * Fixed restore/import-failure text for a user-facing snackbar / restart
     * dialog. Every branch returns one of the [UiFailureTextPolicy] constants;
     * the exception message is read for classification only.
     */
    fun restoreFailureMessage(e: Throwable): String {
        val msg = messageOf(e)
        val lower = msg.lowercase()
        return when {
            lower.contains("backup contains unsafe relative path") ||
                lower.contains("unsafe relative path") ->
                RESTORE_UNSAFE_PATH_TEXT
            lower.contains("incorrect backup password") ||
                lower.contains("protected by a password") ->
                RESTORE_PASSWORD_TEXT
            lower.contains("unencrypted (unsigned)") ->
                RESTORE_UNENCRYPTED_TEXT
            lower.contains("no noteflow.sqlite database entry") ->
                RESTORE_NO_DATABASE_TEXT
            lower.contains("database is empty") ->
                RESTORE_EMPTY_DATABASE_TEXT
            lower.contains("empty vault") ->
                RESTORE_EMPTY_VAULT_TEXT
            lower.contains("could not unlock the backup key") ||
                lower.contains("payload is too short") ||
                lower.contains("header and the encrypted payload do not match") ||
                lower.contains("corrupt or was created on a different device") ->
                RESTORE_CORRUPT_TEXT
            lower.contains("created by a newer version") ->
                RESTORE_NEWER_APP_TEXT
            lower.contains("too many entries") ||
                lower.contains("too large") ||
                lower.contains("extraction limit exceeded") ||
                lower.contains("compression ratio") ->
                BACKUP_TOO_LARGE_TEXT
            lower.contains("no data key available") ->
                RESTORE_NO_DEVICE_KEY_TEXT
            lower.contains("could not be re-encrypted") ->
                RESTORE_REENCRYPT_FAIL_TEXT
            lower.contains("vault locked") ->
                RESTORE_LOCKED_TEXT
            lower.contains("could not read the selected") ||
                lower.contains("could not read the selected backup file") ->
                RESTORE_UNREADABLE_FILE_TEXT
            else -> RESTORE_FAILED_GENERIC
        }
    }

    /**
     * Fixed failure text for the recovery screens (`RestoreBlockedScreen`,
     * `CorruptionRecoveryScreen`, `KeystoreKeyLostScreen`). Same classifier as
     * [restoreFailureMessage], generic wording by default.
     */
    fun recoveryMessage(e: Throwable): String {
        val restore = restoreFailureMessage(e)
        return if (restore == RESTORE_FAILED_GENERIC) RECOVERY_FAILED_GENERIC else restore
    }

    /** Fixed standalone-backup-producer failure text. */
    fun backupFailureMessage(e: Throwable): String {
        val msg = messageOf(e)
        val lower = msg.lowercase()
        return when {
            lower.contains("larger than the restoreable size") ||
                lower.contains("too large") ||
                lower.contains("extraction limit exceeded") ||
                lower.contains("too many entries") ||
                lower.contains("compression ratio") ->
                BACKUP_TOO_LARGE_TEXT
            lower.contains("kept changing during the snapshot copy") ||
                lower.contains("kept changing") ->
                "Backup failed — the vault kept changing during the backup. Please try again."
            lower.contains("no encryption key is available") ||
                lower.contains("unlock the vault") ->
                "Backup failed — the vault is locked. Unlock the vault and try again."
            else -> BACKUP_FAILED_GENERIC
        }
    }

    /** Fixed import-skip text for oversized/bombed archives. */
    fun importSkippedMessage(e: Throwable): String {
        val lower = messageOf(e).lowercase()
        return when {
            lower.contains("too large") ||
                lower.contains("single file is too large") ||
                lower.contains("size exceeds") ||
                lower.contains("total archive size exceeds") ->
                "Import skipped — the file is too large."
            lower.contains("too many entries") ||
                lower.contains("more than ") ||
                lower.contains("compression ratio") ||
                lower.contains("zip bomb") ->
                "Import skipped — the archive looks unsafe (possible zip bomb)."
            else -> IMPORT_SKIPPED_GENERIC
        }
    }

    /**
     * Defense-in-depth sanitizer for any raw text that must still reach the UI:
     * strips URL userinfo, collapses `scheme://host/path` to the bare `host/...`,
     * drops `?key=value` query tokens, and redacts absolute filesystem paths —
     * Android app-private + shared roots (`/data/...`, `/storage/...`,
     * `/sdcard/...`), `/home|/tmp` trees, Windows drive paths (`C:\...`) and UNC
     * shares (`\\server\share\...`). The output is a scrubbed diagnostic string —
     * it never carries credentials, hosts-with-paths or app-private vault paths.
     */
    fun scrubForUi(text: String): String {
        if (text.isNullOrEmpty()) return text
        var scrubbed = URL_USERINFO_REGEX.replace(text) { m -> m.groupValues[1] }
        scrubbed = QUERY_TOKEN_REGEX.replace(scrubbed, "")
        scrubbed = URL_TOKEN_REGEX.replace(scrubbed) { m ->
            val authority = m.groupValues[2]
            if (m.groupValues[3].isEmpty()) authority else "$authority/..."
        }
        return PATH_TOKEN_REGEX.replace(scrubbed) {
            if (it.groupValues[1].isEmpty()) "[path]" else it.groupValues[1] + "/..."
        }
    }
}