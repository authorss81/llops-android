# Phase 252 — Passwordless backup portability (HIGH 4/5)

## Goal
When a user has NO master password (`hasMasterPassword == false`), the backup export path silently writes a **device-DEK-encrypted** archive to SAF (`ImportExportService.kt:1864-1878` in `exportBackupInternal`). The DEK is the device-wrapped DEK (AndroidKeyStore-bound blob), which means the backup is:
- (a) unportable — cannot be opened on any other device (the recipient has the wrapped blob but no matching AndroidKeyStore binding);
- (b) effectively bound to the single device's hardware key — losing the device's keystore key (factory reset, new device, OS upgrade that re-keys the keystore) = losing the backup forever.

The user has no UI indication of this silent data-loss semantics. This is a HIGH-class defect from audit 4/5.

## Context — verified at `2709453`

- `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/HomeScreen.kt:884-915` (the export flow). When `!hasMasterPassword`, the password prompt is skipped and the file is written directly.
- `app/src/main/kotlin/com/authorss81/noteflow/services/ImportExportService.kt:1864-1878`:
  ```kotlin
  val key = vaultDek?.copyOf()
  ...
  val wrappedKey = wrapKey(key)  // AndroidKeyStore-wraps the DEK
  ... write wrappedKey + iv + encrypted payload
  ```
  The `wrappedKey` is only recoverable on the originating device.
- `B1-CRYPTO-05` documents the device-keyed backup flow as a known design but does NOT flag the user-facing export path as a silent loss-of-data.
- The fix: require a backup password for passwordless vaults OR show a non-bypassable warning that forces the user to set a master password before exporting.

## Files to change

### 1. `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/HomeScreen.kt`
- The export path branches on `hasMasterPassword`:
  - If `hasMasterPassword == true`: existing flow (prompt for backup password, encrypt with PBKDF2-derived KEK).
  - If `hasMasterPassword == false`: show a non-bypassable dialog `BackupPasswordRequirementDialog` that explains: "Your vault has no master password. A backup created now would be encrypted with a key bound to this device's hardware — if you lose this device or factory-reset, the backup will be unreadable. To create a portable backup, set a master password first." with two buttons: "Set Master Password" (opens the master-password setup dialog) and "Cancel Export".
- No path bypasses this dialog when `!hasMasterPassword`.

### 2. `app/src/main/kotlin/com/authorss81/noteflow/services/ImportExportService.kt`
- `exportBackup` (the public entry) gains a `requireBackupPassword: Boolean = true` parameter. When `true`, the function throws `IllegalArgumentException` if `password == null` AND `vaultDek` is device-wrapped. The UI layer is the gate (it never calls `exportBackup` without a password when `!hasMasterPassword`).
- Defensive: the gate at the service layer catches any future caller that bypasses the UI check.

### 3. `app/src/main/kotlin/com/authorss81/noteflow/ui/dialogs/BackupPasswordRequirementDialog.kt` (new file)
- Composable dialog with the warning text + two buttons.
- Strings in `strings.xml` (no hardcoded English literals).

## New tests

### `app/src/test/java/com/authorss81/noteflow/Phase252PasswordlessBackupTest.kt` (pure JVM, 4+ tests)
- Pin: `HomeScreen.kt` export path checks `viewModel.settings.hasMasterPassword` BEFORE allowing the export; if `false`, the `BackupPasswordRequirementDialog` is shown.
- Pin: `ImportExportService.kt` `exportBackup` throws `IllegalArgumentException` when called with `password = null` AND `vaultDek` is device-wrapped.
- Pin: the new `BackupPasswordRequirementDialog.kt` exists and contains the "Set Master Password" + "Cancel Export" actions.
- Pin: `strings.xml` has the new warning string (no hardcoded English in the composable).

## Constraints
- No schema change
- No new dependencies
- No `.github/workflows/` edits
- The dialog is mandatory; no "skip" or "export anyway" path. The only way to export from a passwordless vault is to set a master password first.
- The service-layer gate is defense-in-depth — it should not be reachable in normal use, but if a future caller (e.g. a plugin) tries to bypass the UI, the service throws.
- The existing device-keyed backup path (`B1-CRYPTO-05`) is preserved for callers that explicitly opt in via `requireBackupPassword = false` (e.g. a future "device-locked backup" feature). Document this in the KDoc.
- `verification-metadata.xml` untouched (no dep changes)

## DoD
- `gradle :app:testDebugUnitTest` 3556+ green
- `gradle :app:assembleDebug` + `assembleRelease` green
- `gradle :app:lintDebug` 0 errors
- Manual: with a passwordless vault, tap "Export Backup" → the warning dialog appears, "Export" is not possible without setting a master password
- Manual: with a master-password vault, tap "Export Backup" → the password prompt appears (existing flow, unchanged)
- Manual: with a master-password vault, the export creates a portable backup (round-trip restore on a different device works)
- `workspace/phase-252/REPORT.md` with file:line evidence
