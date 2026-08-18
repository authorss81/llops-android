# Phase 135 — Restore hardening: structural/empty-DB gate + one-in-flight re-entrancy gate + saveable recovery state

**Status: DONE (2026-08-18)**

Closes three OPEN findings from `docs/security-report-round2.md`:
`R2-B1D-02` (MEDIUM), `R2-b2b1-UI-03` (LOW), `R2-b2b1-UI-06` (LOW).

This phase also **repairs the pre-existing phase-134 compile break** (`396f5d4`): the
repo did not compile at that commit (duplicated bare function bodies + stray braces in
`NoteflowViewModel.kt`), so phase-134's own tests had never run. Those duplicates are
removed here and the two source-pin tests they broke (`B1Db07PlainZipRestoreRejectedTest`,
`B1Db08DecryptFailureTest`) pass again.

---

## R2-B1D-02 (MEDIUM) — structurally-invalid / EMPTY SQLCipher DB is swapped over the live vault

### Before

`ImportExportService.kt` `validateAndPrepareRestoredDb` (~1867-1932) ran
`PRAGMA integrity_check` + `checkRestoredSchemaNotNewer` (only rejects NEWER
`user_version`) and then re-keyed / HMAC-re-armed / swapped. An attacker-crafted
empty SQLCipher DB passes `integrity_check = ok`, carries `user_version = 0`
(0 < 9 passes the newer-schema check), and its header materializes on
`PRAGMA rekey` — so the empty/attacker DB got HMAC-blessed and moved over the
live vault, and the temp dir's `deleteRecursively()` destroyed the only other copy.

Additionally `extractBackupEntriesTo` (~1816-1860) set `sawDatabase = true` for a
**0-byte** `noteflow.sqlite` entry and continued silently.

### After

New pure-JVM gate **`services/RestoredDbPolicy.kt`** (single decision table,
no Android deps):

- `REQUIRED_TABLES = [pages, strokes, note_versions, media_embeds]`
- `MIN_USER_VERSION = 1L`
- `MIN_DB_FILE_BYTES = 4096L`
- sealed `Decision { Pass | Reject(reason) | EmptyVault }` — `EmptyVault` fires
  only when the structure is real (all required tables present, version ok) but
  the `pages` row count is zero.
- `decide(userVersion, presentTableCount, pageCount, allowEmptyVault)`.
  **`allowEmptyVault` is only the user-confirmed zero-row escape — it NEVER
  bypasses the structural gate** (missing tables / blank version are rejected
  regardless).

Wiring in `ImportExportService.kt`:

- `extractBackupEntriesTo` now rejects a **0-byte** `noteflow.sqlite` before any
  open: `Restore rejected: the backup's database is empty.` (`:1874`).
- `validateAndPrepareRestoredDb(..., allowEmptyVault)` collects `user_version`,
  `countPresentRestoredTables(db)`, `countRestoredRows(db, "pages")` under the
  SAME SQLCipher candidate open that ran `integrity_check` (`:1934-1935`), so the
  counts cannot be gamed between gate and swap. Then:
  - `RestoredDbPolicy.decide(...)` → `Reject(reason)` ⇒
    `quarantineRejectedRestoredDb(context, tempDb)` (byte-exact copy saved beside
    the live vault as `noteflow.sqlite.restore-rejected-<ts>`) + throw with the
    policy reason (`:1959`).
  - `EmptyVault` ⇒ quarantine + `throw EmptyVaultRestoreDecisionException()`
    (`:1963-1964`) — the caller shows the "start fresh" confirmation; the live
    vault is never touched on either path.
- New helpers `countPresentRestoredTables`, `countRestoredRows`,
  `quarantineRejectedRestoredDb` (`:1989`, `:2016`, `:2037`).
- `importBackup(..., allowEmptyVault = false)` threads the flag to both the v2
  and legacy `restoreFromZip(..., allowEmptyVault)` paths (`:1734`, `:1756`,
  `:1787`, `:1801`).
- File-level `internal class EmptyVaultRestoreDecisionException` (`:2822`).

### Evidence (tests)

- `RestoredDbPolicyTest` (7 tests): structural reject on missing table / blank
  version; empty-vault decision only when schema is real; `allowEmptyVault`
  passes a real-schema zero-row vault but NEVER a missing-table one; pass on
  healthy counts.

---

## R2-b2b1-UI-03 (LOW) — unguarded restore entry points / double-trigger race

### Before

All four restore entry points ran unguarded
`viewModelScope.launch { closeDatabase → importBackup → reopen/exitProcess }`:
`attemptRecoveryFromBackup`, `attemptKeystoreKeyLostRecoveryFromBackup`,
`restoreEncryptedBackupFromZip` (`NoteflowViewModel.kt`), `performRestore`
(`HomeScreen.kt`). Double-trigger raced two file swaps; the WebDAV path could
complete AFTER a lock.

### After

New pure-JVM gate **`services/RestoreInflightGate.kt`** (`AtomicBoolean` +
`MutableStateFlow<Boolean>`): `tryBegin()` (optimistic — losers are refused
BEFORE touching the live DB), `end()`, `isRestoring`.

ViewModel (`NoteflowViewModel.kt`):
- `private val restoreGate = RestoreInflightGate()` (`:1328`),
  `val isRestoring: StateFlow<Boolean>` (`:1330`), `tryBeginRestore()`
  (`:1333`), `endRestore()` (`:1336`).
- `pendingEmptyVaultConfirm: StateFlow<CompletableDeferred<Boolean>?>`
  (`:1343-1344`) + `answerEmptyVaultRestore(confirmed)` (`:1346`) +
  `awaitEmptyVaultConfirm()` (`:1351`) — the confirm channel lives in the VM so
  it survives rotation; `allowEmptyVault = true` is set ONLY after the user
  confirms.
- All three VM paths gate on `restoreGate.tryBegin()` (refuse with "A restore is
  already in progress. Wait for it to finish."), release in `finally` via
  `restoreGate.end()`, and catch `EmptyVaultRestoreDecisionException` →
  reopen live DB → `awaitEmptyVaultConfirm()` → re-import with
  `allowEmptyVault = true`:
  - `attemptRecoveryFromBackup` (`:2287-2341`)
  - `attemptKeystoreKeyLostRecoveryFromBackup` (keeps fresh-DEK mint + store)
    (`:2358-2413`)
  - `restoreEncryptedBackupFromZip` (`:3713-3771`) — new pre-close check
    `if (!_authenticated.value || repository.encryptionKey == null)` ⇒ abort +
    reopen + message "The vault locked during the download — restore cancelled…"
    (`:3735`); the WebDAV path refuses empty-vault (`allowEmptyVault = false`).

UI wiring:
- `HomeScreen.kt` `performRestore` enters `tryBeginRestore()`, locks the
  pre-close auth check (reopen + "The vault locked before the restore…" on
  failure), `finally { viewModel.endRestore() }` (`:142-187`); `importBackup`
  runs with `allowEmptyVault = false`.
- `HomeScreen.kt` collects `isRestoring` (`:108`); backup-password confirm and
  legacy-restore confirm buttons are `enabled = !isRestoring` (`:1313`, `:1442`).
- `MainActivity.kt` `RestoreBlockedScreen` / `CorruptionRecoveryScreen` /
  `KeystoreKeyLostScreen` collect `isRestoring`; their "Choose Backup & Restore"
  / "Start Fresh" buttons are `enabled = !isRestoring` with a "Restoring…" label
  while in flight (`:991-995`, `:1070-1079`).
- `WebDavSyncDialog.kt`: "Download & Restore" button is
  `enabled = !isLoading && !isRestoring && username.isNotBlank() && passwordOrToken.isNotBlank()`.

### Evidence (tests)

- `RestoreInflightGateTest` (4 tests): first `tryBegin` succeeds, second is
  refused while first is in flight, `end()` frees the gate, `isRestoring`
  StateFlow tracks the lifecycle.

---

## R2-b2b1-UI-06 (LOW) — recovery-screen state dies on rotation

### Before

`MainActivity.kt` recovery screens kept `backupPassword`/error in
`remember { mutableStateOf }` — rotation wiped the password and error, and there
was no in-flight gate.

### After

- `RestoreBlockedScreen` (`:946-948`), `CorruptionRecoveryScreen`
  (`:1015-1017`), `KeystoreKeyLostScreen` (`:1106-1109`, incl. `confirmStartFresh`):
  all trigger/password/error state hoisted to `rememberSaveable`; each collects
  `isRestoring` and disables its buttons while a restore is in flight.
- Shared `EmptyVaultRestoreConfirmDialog(viewModel)` (`:909`) renders from
  `viewModel.pendingEmptyVaultConfirm` and calls
  `viewModel.answerEmptyVaultRestore(...)` — invoked from all three recovery
  screens. The deferred answer survives rotation because it lives in the VM.
- `HomeScreen.kt` restore-dialog state (`showBackupPasswordDialog`,
  `showLegacyRestoreConfirmDialog`, `backupPasswordInput`, `backupPasswordError`,
  `isValidating`) hoisted to `rememberSaveable` (`:99-104`); `pendingRestoreBytes`
  (a `ByteArray`) intentionally stays `remember` — rotation after file-pick but
  before confirm only drops the in-memory bytes, never re-enters the gate.

---

## Phase-134 compile-break repair (prerequisite)

`396f5d4` ("llops: phase-134") duplicated six bare function bodies in
`NoteflowViewModel.kt` alongside their `writeGuardedAgainstLock`-wrapped
versions and left stray braces, so `:app:compileDebugKotlin` failed ("Conflicting
overloads"). The bare duplicates of `updatePageSource`, `updatePageTemplate`,
`renameTag`, `deleteTag`, `togglePinPage`, `trashPage` are removed here; the
guard-wrapped copies (R2-B1A-01, phase-134 semantics) are preserved verbatim.

Two pre-existing source-pin tests were collateral damage and are restored:
- `B1Db07PlainZipRestoreRejectedTest`: the pinned legacy call string became
  `restoreFromZip(context, rawBytes, null, currentDekHex, allowEmptyVault)`
  (phase-135 signature change); the ordering pin (plain-zip reject precedes the
  extraction call) is unchanged in intent.
- `B1Db08DecryptFailureTest`: phase-134's lock() comment (`:4032`) contained the
  literal `NoteflowDatabase.dispose()`, tripping `assertFalse(...contains(...))`.
  The comment is reworded to "the DB teardown below" — the pin (reset precedes
  connection drop) is again meaningful.

---

## Verification

- `gradle :app:testDebugUnitTest --tests RestoredDbPolicyTest` → 7 passed
- `gradle :app:testDebugUnitTest --tests RestoreInflightGateTest` → 4 passed
- `gradle :app:testDebugUnitTest --tests RestoreHardeningWiringTest` → 13 passed
- `gradle :app:testDebugUnitTest` (full suite) → **1861 tests, 0 failures**
- `gradle assembleDebug` → **BUILD SUCCESSFUL** (one transient
  `mergeExtDexDebug` failure on a parallel daemon re-ran clean)
- APK: `app/build/outputs/apk/debug/app-debug.apk`

## Definition of done

- [x] All three findings closed with before/after evidence (above).
- [x] The live vault is never swapped for a structurally-invalid/empty restore
      (reject/EmptyVault both quarantine before any re-arm/swap).
- [x] No existing restore flow regressed — full unit suite green, debug APK builds.

## Constraints honored

- NO DB schema change, no migration, no new dependencies, no workflow edits.
- `allowBackup=false` / quarantine / fail-closed lock model preserved;
  no keys/passwords/decrypted content logged. B1-PLAT-1 fail-closed release
  signing untouched.
