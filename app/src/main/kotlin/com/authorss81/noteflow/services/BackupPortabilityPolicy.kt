package com.authorss81.noteflow.services

/**
 * Phase 252 (HIGH 4/5): passwordless backup portability decision table.
 *
 * When a vault has NO master password, the in-memory DEK is the
 * device-wrapped AndroidKeyStore copy (`VaultKeyHolder.dek`, restored from
 * `SharedPrefsDekDeviceStore`). Calling [ImportExportService.exportBackup]
 * with no backup password then writes a DEVICE-KEYED archive (B1-CRYPTO-05):
 * the archive carries the AndroidKeyStore-wrapped DEK blob, which no other
 * device can unwrap — losing the device, a factory reset, or a keystore re-key
 * makes the backup permanently unreadable, and until phase-252 the export UI
 * silently shipped exactly this without telling the user.
 *
 * This policy is the single gate: [requirePortableBackup] throws when an
 * export would produce a device-keyed archive for a passwordless vault unless
 * the caller explicitly opted into the device-keyed model via
 * `requireBackupPassword = false` (the WebDAV/LocalSend producers and any
 * future "device-locked backup" feature). The UI layer (HomeScreen) is the
 * first gate — it never invokes the passwordless export without a master
 * password; this service gate is defense-in-depth for any future caller that
 * bypasses the UI.
 *
 * Pure JVM (no Android imports) so the decision table and the error copy are
 * unit-testable.
 */
internal object BackupPortabilityPolicy {

    /**
     * The [IllegalArgumentException] message thrown by the service gate. Honest
     * and actionable: a device-keyed archive can never be opened anywhere but
     * the originating device, so the only portable path is a master password.
     */
    const val PASSWORDLESS_DEVICE_KEYED_ERROR: String =
        "Backup rejected: no backup password was provided and this vault has no master " +
            "password, so the archive would be encrypted with a key bound to this device's " +
            "hardware and could never be opened on any other device (a lost device or " +
            "factory reset would make it unreadable forever). Set a master password first " +
            "to create a portable backup."

    /**
     * True exactly when the export would be DEVICE-KEYED: a key is available
     * (the vault is unlocked), no backup password was supplied, and the vault
     * has no master password (so the DEK is the device-wrapped copy). A
     * password-protected vault exporting with `backupPassword == null` stays
     * allowed — those callers are the documented B1-CRYPTO-05 device-keyed
     * sync producers.
     */
    fun isDeviceKeyed(
        backupPassword: String?,
        keyAvailable: Boolean,
        hasMasterPassword: Boolean
    ): Boolean = keyAvailable && backupPassword == null && !hasMasterPassword

    /**
     * The phase-252 service gate: throws [IllegalArgumentException] when
     * [requireBackupPassword] is true (the default) and the export would be a
     * silent device-keyed archive for a passwordless vault.
     */
    fun requirePortableBackup(
        requireBackupPassword: Boolean,
        backupPassword: String?,
        keyAvailable: Boolean,
        hasMasterPassword: Boolean
    ) {
        if (requireBackupPassword && isDeviceKeyed(backupPassword, keyAvailable, hasMasterPassword)) {
            throw IllegalArgumentException(PASSWORDLESS_DEVICE_KEYED_ERROR)
        }
    }
}