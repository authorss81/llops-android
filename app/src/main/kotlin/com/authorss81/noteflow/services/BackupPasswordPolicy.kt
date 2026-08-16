package com.authorss81.noteflow.services

/**
 * B2-CRYPTO-04 (phase-84): the single decision table for BACKUP password
 * strength at export time.
 *
 * Threat model (the finding): a password-protected (v2/v3 NFLB) backup is
 * DESIGNED to travel — it lands in the world-readable public Downloads, the
 * share sheet, MTP/USB readers, and the user's own WebDAV/Nextcloud server.
 * Anyone who obtains the file receives the full encrypted vault plus the v3
 * header's salt and half of the DEK-wrapping key; an offline GPU/FPGA
 * PBKDF2-SHA-256 rig can crack a weak backup password with ZERO device access
 * and no lockout (the vault's on-device 5-attempt throttle in `NoteflowViewModel`
 * never fires for an offline attacker). The pre-fix gate at
 * `ImportExportService.exportBackup` was a bare `length >= 6` with no
 * complexity — exactly the 6-7 char numeric/lowercase keyspace the finding
 * shows is crackable offline in hours-to-days.
 *
 * Because this app's password-backup flow encrypts with the vault MASTER password
 * (HomeScreen validates the typed password against the vault before exporting —
 * see `HomeScreen.kt` `isMasterPasswordValid`), the backup password must clear
 * the SAME strength bar as the vault master password ([PasswordStrengthPolicy],
 * B1-CRYPTO-04 / phase-63 + B1-PLAT-8 / phase-90): at least 10 NFKC-normalized
 * graphemes, no sequential/keyboard-row/repeated/common-word/prefix-suffix
 * patterns, at least 3 distinct graphemes, and
 * 3-of-4 character-class diversity for short passwords (passphrases of at least
 * 12 graphemes pass on length alone). The floor is measured on the NFKC-
 * normalized form — the exact bytes `EncryptionService.deriveKey` hashes
 * (B2-CRYPTO-07) — so a normalization shift can never silently weaken or reject
 * a stored password. Enforcing the same bar here is deliberately consistent:
 * a backup can never be protected by a WEAKER password than its vault.
 *
 * The gate is authoritative at `ImportExportService.exportBackup` and pre-checked
 * for a friendly error by the HomeScreen backup dialog ([OFFLINE_BACKUP_NOTICE]
 * is surfaced loudly so the user makes the strength/placement tradeoff with open
 * eyes). The RESTORE / verify side (["validateBackupPassword"],
 * `tryParseBackupV2`) is deliberately NOT strength-gated — a pre-fix backup made
 * with a weak password must still restore (unlock paths never strength-gate,
 * matching the B1-CRYPTO-04 principle). Pure JVM (no Android imports) so the
 * decision table and the warning copy are unit-testable.
 */
internal object BackupPasswordPolicy {

    /**
     * Non-alarming warning appended to every rejection and shown on the backup
     * dialog, so "Backups in Downloads/cloud are as weak as the backup password"
     * is never silent (AGENTS.md hardware-reality: never silent degradation).
     */
    const val OFFLINE_BACKUP_NOTICE: String =
        "Backups stored in Downloads, shares, or cloud/WebDAV servers are only as strong as this password " +
            "- anyone who obtains the file can try to crack it offline."

    /**
     * Judges a backup password against the vault master-password bar (delegates
     * to [PasswordStrengthPolicy] — the single strength decision table).
     */
    fun evaluate(raw: String): PasswordStrengthVerdict = PasswordStrengthPolicy.evaluate(raw)

    /**
     * The [BackupPasswordPolicy] gate used by export: throws
     * [IllegalArgumentException] carrying the verdict message + the offline
     * warning when the password is too weak, so a weak-credential backup can
     * never be written silently.
     */
    fun requireStrongBackupPassword(raw: String) {
        val verdict = evaluate(raw)
        require(verdict.accepted) { verdict.message + " " + OFFLINE_BACKUP_NOTICE }
    }
}