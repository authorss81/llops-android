package com.authorss81.noteflow.services

/**
 * Phase 252 (HIGH 4/5): backup portability decision table.
 *
 * Calling [ImportExportService.exportBackup] with no backup password writes a
 * DEVICE-KEYED archive (B1-CRYPTO-05): the archive's DEK is the
 * AndroidKeyStore-wrapped device copy (every vault's in-memory DEK — with or
 * without a master password — is this device-bound blob, see
 * `VaultKeyHolder.dek`/`SharedPrefsDekDeviceStore`), which no other device can
 * unwrap — losing the device, a factory reset, or a keystore re-key makes the
 * backup permanently unreadable, and until phase-252 the export UI silently
 * shipped exactly this without telling the user.
 *
 * The portability of a backup is therefore a pure function of whether a backup
 * password was supplied: `backupPassword == null` ⇔ the archive is
 * device-keyed, regardless of `hasMasterPassword`. [isDeviceKeyed] encodes
 * exactly that (a key must also be available, i.e. the vault must be unlocked).
 *
 * This policy is the single gate: [requirePortableBackup] throws when an
 * export would produce a device-keyed archive unless the caller explicitly
 * opted into the device-keyed model via `requireBackupPassword = false` (the
 * WebDAV/LocalSend producers and any future "device-locked backup" feature).
 * The UI layer (HomeScreen) is the first gate — it never invokes the backup
 * export after the requirement dialog without a master password; this service
 * gate is defense-in-depth for any future caller (e.g. a plugin) that bypasses
 * the UI, on ANY vault shape — passwordless OR master-password — exporting
 * without a backup password.
 *
 * Pure JVM (no Android imports) so the decision table and the error copy are
 * unit-testable.
 */
internal object BackupPortabilityPolicy {

    /**
     * The [IllegalArgumentException] message thrown by the service gate. Honest
     * and actionable: a device-keyed archive can never be opened anywhere but
     * the originating device, so the only portable path is a backup password
     * (via a master password on the interactive HomeScreen path).
     */
    const val PASSWORDLESS_DEVICE_KEYED_ERROR: String =
        "Backup rejected: no backup password was provided, so the archive would be encrypted " +
            "with a key bound to this device's hardware and could never be opened on any other " +
            "device (a lost device or factory reset would make it unreadable forever). Set a " +
            "master password first and export again to create a portable backup."

    /**
     * True exactly when the export would be DEVICE-KEYED: a key is available
     * (the vault is unlocked) AND no backup password was supplied. Every
     * vault's in-memory DEK is the device-wrapped AndroidKeyStore copy, so a
     * missing backup password always yields an unportable archive — whether or
     * not the vault has a master password.
     */
    fun isDeviceKeyed(
        backupPassword: String?,
        keyAvailable: Boolean
    ): Boolean = keyAvailable && backupPassword == null

    /**
     * The phase-252 service gate: throws [IllegalArgumentException] when
     * [requireBackupPassword] is true (the default for every caller) and the
     * export would be a silent device-keyed archive (no backup password).
     */
    fun requirePortableBackup(
        requireBackupPassword: Boolean,
        backupPassword: String?,
        keyAvailable: Boolean
    ) {
        if (requireBackupPassword && isDeviceKeyed(backupPassword, keyAvailable)) {
            throw IllegalArgumentException(PASSWORDLESS_DEVICE_KEYED_ERROR)
        }
    }
}
