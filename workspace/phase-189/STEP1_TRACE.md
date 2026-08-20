# Phase 189 — Step 1: Trace the export → backup/restore-failure chain

Reproduced at the SOURCE level (pure-JVM host; no emulator) by walking every
export chain end-to-end against `7042ade` (HEAD), recording per call whether it
touches a LIVE handle (Room connection / `VaultKeyHolder.dek` / the live main DB
file) or only the staged copy.

## Reported symptom

> After a vault export (Obsidian/HTML/encrypted-vault), "Backup to file" and
> "Backup from file" both fail; the app recovers only after leaving + reopening.
> (The "notes unreadable / last-used notebook missing" sibling symptom is
> phase-182's scope and is fixed already.)

## Current-tree finding (summary)

The export chain is **session read-only**: it never calls `closeDatabase()`,
`reopenDatabase()`, `zeroizeKey()`, mutates `_authenticated`, or re-keys the
Room instance. Post-export, a follow-up `exportBackup`/`importBackup` works
provided the DEK and the Room connection are still alive.

The export chain DOES, however, hold exactly **two live-handle dependencies** —
both inside `ImportExportService.exportBackup` (`ImportExportService.kt:1451`):

1. `repository.checkpointWal()` + `repository.stampDatabaseChecksum(context)` +
   `VaultSnapshotCopyPolicy.checkpointThenCopy(dbFile, stagedDb)` run against the
   LIVE main DB file and the LIVE Room connection (`ImportExportService.kt:1478-1484`).
   `checkpointThenCopy` **fails CLOSED** ("Backup failed: the vault database kept
   changing during the snapshot copy. Please try again.", `:1480-1483`) when a
   concurrent WAL auto-checkpoint rewrites the main file mid-copy for all
   `MAX_VERIFY_ATTEMPTS` retries.
2. The staged-snapshot prune helpers re-read the **mutable singleton**
   `VaultKeyHolder.dek` at PRUNE time — long after the export decision was made —
   and throw "Backup failed: the vault is locked; cannot bound the
   {version-history,layer} snapshot." if it was zeroized in between
   (`pruneStagedSnapshotVersions` `:2762-2766`, `pruneStagedSnapshotLayers` `:2724-2728`).

Nothing in the exports CLOSES/ZEROIZES/REOPENS by itself. The session loss is
triggered AROUND the export, never by it directly:

- `MainActivity.kt:207-209`: any `ON_STOP` (the SAF picker opened for the export
  destination, the screen-off receiver `:138-144`, a share intent) calls
  `viewModel.lock()`.
- `NoteflowViewModel.kt:4688`: for a **password-protected** vault `lock()` is a
  full teardown — DEK zeroized (`:4747`), SessionState cleared. The DEK is
  reinstated only by a later `verifyMasterPassword` / `unlockWithKey`.
- Because the prunes read `VaultKeyHolder.dek` at prune time inside the same
  export run, ANY lock that lands mid-export makes the CURRENT export fail with
  the fixed-text "vault is locked" message.

So the deterministic defect the phase must close is the **mutable-singleton read
inside the export helpers**: the staged-snapshot prune should run under the SAME
key the export started with, snapshot-at-entry, never a re-read that a lock can
invalidate. The residual torn-copy race (live-file checkpoint-then-copy) is
already guarded by phase-137's fail-closed verified copy and is out of scope for
the session-loss fix (its mitigation is deliberate security, not a defect).

## Walk-through per manual path

### A. HomeScreen device-keyed "Backup to file" (`HomeScreen.kt:680` → `:695`)
1. `onBackup` reads `viewModel.repository.encryptionKey` (the DEK) and calls
   `ImportExportService.exportBackup(context, key, backupPassword = null, repository)`.
2. `exportBackup` (`withContext(Dispatchers.IO)`, `:1456`):
   - `repository.checkpointWal()` (`NoteRepository.kt:328-336`, live
     `PRAGMA wal_checkpoint(FULL)`, cursor fully stepped) — live connection, read/checkpoint only.
   - `repository.stampDatabaseChecksum(context)` (`NoteRepository.kt:338-340` →
     `DatabaseSecurityHelper.updateStoredChecksum`, `DatabaseSecurityHelper.kt:85-97`)
     — synchronous HMAC of `main + -wal`, `commit()`-written pref. No DB write.
   - `VaultSnapshotCopyPolicy.checkpointThenCopy(dbFile, stagedDb)`
     (`VaultSnapshotCopyPolicy.kt`, injectable pre-`checkpointFull()`, triple-digest,
     fail-closed) — reads LIVE main file.
   - `pruneStagedSnapshotVersions(stagedDb)` + `pruneStagedSnapshotLayers(stagedDb)`
     — open the STAGED copy with `VaultKeyHolder.dek` (live-singleton read), prune, close.
   - pack `stagedDb`, encrypt (device-keyed, `:1608-1612`), `:1636` sets
     `SettingsManager.lastBackupTimestamp`, temp file returned.
3. HomeScreen `exporter.export(ENCRYPTED_BACKUP, cacheFile)` — SAF
   `ACTION_CREATE_DOCUMENT` (backgrounds activity → `ON_STOP` → `lock()` for
   password vaults).
4. On SAF result (main thread) the cache file is copied to the destination and
   `viewModel.refreshBackupTimestamp()` + snackbar fire (`HomeScreen.kt` exporter
   completion); phase-153 policy DROPS the snackbar while `_authenticated=false`.

Follow-up "Backup to file" after `lock()`-then-unlock re-runs the same chain
with the reinstated DEK, so it recovers — matching the report that a RESTART is
the reliable fix: on restart the DEK re-derives and `VaultKeyHolder.dek` is
re-seeded (`NoteflowDatabase.kt:465` factory passwordless path /
`SecurityService.forDevice(...).getOrCreateDek(...)`), removing every
`VaultKeyHolder.dek` null-window.

### B. HomeScreen password "Backup to file" (`HomeScreen.kt:1580-1613`)
Same chain with `backupPasswordInput`; v3 NFLB3 header, strength gate
(`BackupPasswordPolicy.requireStrongBackupPassword`, `:1554`), split wrap key
(`:1567-1594`). Live-handle profile identical to A.

### C. WebDAV / LocalSend producers
Both route through the SAME `exportBackup` with `repository` (`NoteflowViewModel.kt:4339-4346`,
`LocalSendSendDialog.kt:147-152`) — identical live-handle profile (checkpoint +
re-stamp + live copy + `VaultKeyHolder.dek` prune reads).

### D. "Backup from file" (restore) — `HomeScreen.performRestore` (`HomeScreen.kt:167-238`)
1. Picker → `stageBackupUriToFile` (cache copy, reviewed budget) → header sniff:
   - NFLB2/NFLB3 → password dialog → `validateBackupPasswordFile` comes BEFORE
     any live-DB close (`HomeScreen.kt:191`, H1);
   - plain zip → refused (`:280-289`); legacy device-keyed → confirm dialog.
2. Pre-close guard: `viewModel.repository.encryptionKey == null` →
   `reopenDatabase(context)` + "vault locked" failure (`:195-198`) — this is the
   ONLY restore gate that a zeroized DEK trips.
3. `RestoreFailSafe.guaranteeReopenAfterRestore` (`:202-211`) closes, swaps,
   re-opens, restarts. The export never participates here, so the residual risk
   for restore is purely the `encryptionKey == null` gate at `:195` — again a
   `VaultKeyHolder.dek` zeroization window, not an export-internal write.

## Evidence anchors

| Call | Location | Live handle | Effect |
|------|----------|-------------|--------|
| `checkpointWal` | `NoteRepository.kt:328-336` | Room connection / live main file | folds WAL into main; no session mutation |
| `stampDatabaseChecksum` | `NoteRepository.kt:338-340`; `DatabaseSecurityHelper.kt:85-97` | reads live `main + -wal` | pref `db_hmac_checksum` re-armed (commit) |
| `checkpointThenCopy` | `ImportExportService.kt:1480-1484`; `VaultSnapshotCopyPolicy.kt` | LIVE main file | verified snapshot → `stagedDb`; fail-closed on racing writer |
| `pruneStagedSnapshotVersions` | `ImportExportService.kt:2762-2766` | **`VaultKeyHolder.dek` at prune time** | throws "vault is locked" if DEK zeroized mid-export |
| `pruneStagedSnapshotLayers` | `ImportExportService.kt:2724-2728` | **`VaultKeyHolder.dek` at prune time** | throws "vault is locked" if DEK zeroized mid-export |
| `lock()` (ON_STOP / screen-off) | `MainActivity.kt:207-209`, `:138-144`; `NoteflowViewModel.kt:4688`, `:4747` | zeroizes DEK (password vaults) | tears session; DEK reinstated only on re-unlock/restart |
| `encryptionKey == null` gate | `HomeScreen.kt:195-198` | `VaultKeyHolder.dek` | restore fails closed while DEK zeroized |

## Conclusion for Step 2

Per the phase directive ("the export runs entirely against the staged copy and
never touches live handles; the next backup/restore must work with the SAME key
the export used"), the fix is:

- `exportBackup` pins the DEK it was handed (`key`) for the whole run and passes
  it into `pruneStagedSnapshotVersions` / `pruneStagedSnapshotLayers`, which then
  open the staged snapshot under that pinned key and are REMOVED from any
  `VaultKeyHolder` dependence. The `key ?: VaultKeyHolder.dek ?: throw locked`
  fallback preserves the locked-vault refusal for the (impossible-when-open)
  null-key caller without re-reading the mutable singleton mid-export.
- Verified by a pure-JVM test that proves: a staged-snapshot prune with the
  pinned key still opens the snapshot EVEN WHEN `VaultKeyHolder.dek == null`
  (a lock that lands mid-export can no longer fail the backup or cascade into
  the next one), and that the prune functions no longer reference the singleton.