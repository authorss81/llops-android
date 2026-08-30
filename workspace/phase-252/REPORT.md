# Phase 252 — Passwordless backup export is portable (no silent device-keyed archives)

**Date:** 2026-08-30
**Status:** DONE

## 1. Summary

**Audit finding: HIGH 4/5 (B1-CRYPTO-05 descendant).** The HomeScreen "Back up
vault now" action on a passwordless vault (no master password set) silently
wrote an archive whose DEK was wrapped by a keystore-bound key
(`keyAndIv = keyStore.getKey`, export `fallback to the device DEK`) — a
backup that (a) only this device's AndroidKeyStore can ever unwrap, and
(b) is **permanently lost** on device loss, factory reset, or keystore re-key.
There was no UI indication that the produced file was unportable.

This phase makes the passwordless path a **non-bypassable portability gate**:

1. **HomeScreen gate** — `onBackup` on a `!hasMasterPassword` vault no longer
   exports. It opens a new `BackupPasswordRequirementDialog` whose ONLY actions
   are "Set Master Password" (routes to the existing `SecuritySettingsDialog`)
   and "Cancel Export". There is no "export anyway" button — the final
   portability decision cannot be silently bypassed from the phone.
2. **Service-layer enforcement / defense in depth** — `exportBackup` gained a
   `requireBackupPassword: Boolean = true` parameter (defaulting to the SAFE
   value for every future UI caller). Any call whose input shape is
   *device-keyed* (`backupPassword == null` **and** vault DEK key available
   **and** no master password set) triggers the pure-JVM
   `BackupPortabilityPolicy.requirePortableBackup` guard.
3. **Documented device-keyed producers preserved by explicit opt-in** — the
   WebDAV sync (`NoteflowViewModel.exportEncryptedBackupToZip`) and the LocalSend
   "Encrypted vault backup" payload are documented B1-CRYPTO-05 device-keyed
   features; both pass `requireBackupPassword = false` so passwordless vaults
   keep syncing/sending as before while the high-risk "backup" export is gated.
4. **Honest error mapping** — `UiFailureTextPolicy.backupFailureMessage` maps
   the guard's message to user-facing copy for any non-UI caller that ignores
   the gate (defense in depth, same lead phrase for searchability).

No schema change, no new dependencies, `.github/workflows/` untouched, base-APK-
size rule intact.

## 2. Changes — file:line evidence

### NEW `app/src/main/kotlin/com/authorss81/noteflow/services/BackupPortabilityPolicy.kt`

Pure-JVM (no Android imports), `internal`, unit-testable decision table:
- `:27` — `internal object BackupPortabilityPolicy`.
- `:34` — `const val PASSWORDLESS_DEVICE_KEYED_ERROR = "set a master password first — a passwordless vault's backup is encrypted with a device-bound key and cannot be restored on another device."`.
- `:49` — `isDeviceKeyed(backupPassword, keyAvailable, hasMasterPassword)` =
  `keyAvailable && backupPassword == null && !hasMasterPassword` — the exact
  shape `exportBackup` previously turned into an unwrappable-elsewhere archive.
- `:60-67` — `requirePortableBackup(...)`: throws `IllegalArgumentException`
  with that message when `requireBackupPassword` is true and the shape is
  device-keyed.

### `app/src/main/kotlin/com/authorss81/noteflow/services/ImportExportService.kt`

- `:1692-1706` — KDoc on `exportBackup` documents the Phase-252 gate, which
  input shape is device-keyed/unportable, and that WebDAV/LocalSend opt in via
  `requireBackupPassword = false` (the documented "device-locked backup").
- `:1708-1712` — new signature `suspend fun exportBackup(context, vaultDek,
  backupPassword = null, requireBackupPassword: Boolean = true, repository)` —
  the DEFAULTS are the safe ones for any caller.
- `:1717-1740` — inside the snapshot-copy try block the gate runs before any
  bytes are staged:
  ```kotlin
  BackupPortabilityPolicy.requirePortableBackup(
      requireBackupPassword = requireBackupPassword,
      backupPassword = backupPassword,
      keyAvailable = key != null,
      hasMasterPassword = SettingsManager(context.applicationContext).hasMasterPassword,
  )
  ```
  `key != null` is read from the exact key the implementation wraps with
  (`:1740+`), so the gate fires precisely when the archive's DEK would be
  keystore-wrapped.

### NEW `app/src/main/kotlin/com/authorss81/noteflow/ui/dialogs/BackupPasswordRequirementDialog.kt`

- `@Composable BackupPasswordRequirementDialog(onSetMasterPassword, onCancel)` —
  an `AlertDialog` with `stringResource` for every surface (title, body, and the
  two buttons **Set Master Password** / **Cancel Export**). No hardcoded
  literals. There is deliberately no third/no-op action.

### `app/src/main/res/values/strings.xml`

- `:31-34` — `backup_password_requirement_title`, `backup_password_requirement_body`
  (states the hardware-bound key + the "can never be opened again" consequence +
  "set a master password first"), `backup_password_requirement_set_password`,
  `backup_password_requirement_cancel_export`.

### `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/HomeScreen.kt`

- `:60` — import of `com.authorss81.noteflow.ui.dialogs.BackupPasswordRequirementDialog`
  (the `ui/dialogs/` package is created by this phase).
- `:127` — `var showBackupPasswordRequirementDialog by rememberSaveable { mutableStateOf(false) }`.
- `:890-907` — `onBackup`: master-password vaults open the EXISTING
  backup-password dialog; `!hasMasterPassword` sets the requirement-dialog flag
  (with a comment documenting why no path bypasses it). The old silent
  `ImportExportService.exportBackup(...)` + `SaFExporter` call in this branch is
  **removed**.
- `:2060` — the ONLY remaining `ImportExportService.exportBackup(` call in
  HomeScreen, inside the master-password dialog flow (unchanged).
- `:2123-2130` — the requirement dialog composited after the restart-confirm
  dialog: `onSetMasterPassword = { dismiss; showSecurityDialog = true }`,
  `onCancel = { dismiss }` (routing to the existing `SecuritySettingsDialog`).

### `app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt`

- `:4604-4622` — `exportEncryptedBackupToZip` (WebDAV producer) passes
  `requireBackupPassword = false`, explicitly preserving the documented
  device-keyed sync archive path for passwordless vaults.

### `app/src/main/kotlin/com/authorss81/noteflow/ui/components/LocalSendSendDialog.kt`

- `:172` — the VAULT_BACKUP payload passes `requireBackupPassword = false` for
  the same reason (documented device-keyed send; receiver must still
  human-accept).

### `app/src/main/kotlin/com/authorss81/noteflow/services/UiFailureTextPolicy.kt`

- `:208-226` — `backupFailureMessage` maps `"set a master password first"` to
  the Phase-252 copy (long-form, no secrets) and keeps the existing default
  sanitization for other errors — defense in depth for any caller that reaches
  the service gate outside the UI.

## 3. New tests — `app/src/test/java/com/authorss81/noteflow/Phase252PasswordlessBackupTest.kt`

7 tests (pure JVM; source pins + policy behavior + dialog copy):

1. **`HomeScreen no longer exports on passwordless vaults`** — source pin:
   the `registerTopBar`/`onBackup` lambda shows `showBackupPasswordRequirementDialog = true`
   in the `else` of `viewModel.hasMasterPassword.value` and no `exportBackup(`
   call exists in that branch (the only `exportBackup(` in the file sits inside
   the master-password dialog flow).
2. **`the requirement dialog is shown and Set Master Password routes to Security`**
   — source pin: `BackupPasswordRequirementDialog(` composite follows the
   `showBackupPasswordRequirementDialog` guard and its `onSetMasterPassword`
   opens `showSecurityDialog` (setup is handed to the real master-password UI,
   not a fake flow).
3. **`BackupPortabilityPolicy allows every legitimate export shape`** — behavior:
   password supplied, master-password vault, `requireBackupPassword = false`
   opt-in device-keyed, and passwordless-with-no-key all PASS; only the exact
   device-keyed + gated shape throws.
4. **`the gated device-keyed shape is rejected with the exact error message`** —
   asserts `IllegalArgumentException` + `PASSWORDLESS_DEVICE_KEYED_ERROR`
   equality (message starts with the "set a master password first" lead used by
   the UI policy mapping).
5. **`exportBackup signature carries the default-safe gate`** — signature pin on
   `requireBackupPassword: Boolean = true` + the in-body
   `BackupPortabilityPolicy.requirePortableBackup(` call keyed on `key != null`
   and `SettingsManager(...).hasMasterPassword`.
6. **`the dialog composes and carries no hardcoded literals`** — source pin:
   the composable wires the four `stringResource(R.string.backup_password_requirement_*)`
   refs and contains NO straight-quoted English UI literals.
7. **`strings resource file carries the warning copy and the two button labels`**
   — resource-file scan pins the exact `name=` attributes and labels
   ("Set Master Password", "Cancel Export") with a warning copy that names the
   hardware-bound-key consequence.

**`Phase148UiFailureTextScrubTest`** — the `backup-failure ≥ 2` HomeScreen count
pin was updated to `≥ 1`: phase-252 legitimately removed the second
`backupFailureMessage(e)` call site (the deleted passwordless-export catch); the
surviving master-password export surface still routes through the policy (the
real guarantee), and the raw-interpolation `assertFalse` guards are untouched.
Precedent for updating a stale pin when the pinned mechanism changes:
phases 177/181/198/203.

## 4. DoD verification

| DoD item | Result |
|---|---|
| `gradle :app:testDebugUnitTest` green | **3633 / 0 failures / 0 errors** (phase-251 baseline 3626 + 7 new; the pre-existing flaky `Phase148UiFailureTextScrubTest` UNC-path failure did not fire this run) |
| `gradle :app:assembleDebug` green | green |
| `gradle :app:assembleRelease` green (R8+signed) | green |
| `gradle :app:lintDebug` 0 errors | 0 errors (106 pre-existing warnings) |
| No device-keyed archive produced from a passwordless vault | covered by source pins + behavior tests (manual SaF export needs a device; verifier documented) |
| WebDAV + LocalSend still work for passwordless vaults | opt-in preserved + verified by signature/source pins in the test suite; live sync needs a device |

Notes:
- The documented pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure
  is environment/flaky — this run it passed; the only failure this run was the
  count-pin stale caused by the (legitimate) removal of the second export
  surface, fixed as described in §3.
- `.github/workflows/` untouched, `verification-metadata.xml` untouched, no
  schema change, no new dependencies, base-APK-size rule intact.

## 5. Compatibility & security posture (constraint)

- The dialog now surfaces on EVERY passwordless backup attempt — one honest,
  non-alarming message with a single action; it is not silent degradation.
- `requireBackupPassword = true` default means any FUTURE caller of
  `exportBackup` that forgets to think about portability gets a loud failure,
  never a silent unreadable archive. Review codes that must opt out
  (WebDAV/LocalSend) do so explicitly at the call site with the B1-CRYPTO-05
  rationale.
- The master backup dialog, `BackupPasswordPolicy` strength gate, and the
  existing `hasMasterPassword` flow are untouched.