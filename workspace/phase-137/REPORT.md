# Phase 137 — Backup/export file-copy consistency: checkpoint-then-copy for every DB-file producer

**Status: DONE (2026-08-18)**

Closes two OPEN findings from `docs/security-report-round2.md`:
`R2-B1D-05` (LOW) and `R2-B1D-03` (LOW).

---

## R2-B1D-05 (LOW) — `exportBackup` copies the live main DB file with a plain unbounded FileInputStream; a concurrent WAL auto-checkpoint can produce a torn archive

### Before

`ImportExportService.exportBackup` (`ImportExportService.kt:1314-1318` at audit,
`importBackup`'s sibling in the same object) copied the live `noteflow.sqlite`
straight into the zip entry with a plain unbounded copy:

```kotlin
if (dbFile.exists()) {
    zos.putNextEntry(ZipEntry("noteflow.sqlite"))
    FileInputStream(dbFile).use { fis -> fis.copyTo(zos) }
    zos.closeEntry()
}
```

Unlike the checkpoint-first producers (`HomeScreen.kt:608-614` /
`NoteflowViewModel.kt:3686-3692`), `exportBackup` itself did no checkpoint and no
verification. The vault runs `JournalMode.WRITE_AHEAD_LOGGING`
(`NoteflowDatabase.kt:415`), so the 1000-page WAL auto-checkpoint can fire DURING
the copy, rewriting the main file mid-stream — the archive then contains a
main-file snapshot that never existed. The corruption only surfaces at restore
time (`integrity_check` fails) — a silently broken backup.

### After

`exportBackup` is now the **single disciplined DB-file producer**
(`ImportExportService.kt:1308-1347`):

```kotlin
if (dbFile.exists()) {
    repository.checkpointWal()                       // :1335  FULL, before any copy
    repository.stampDatabaseChecksum(context)        // :1336  re-arm baseline post-checkpoint
    if (!VaultSnapshotCopyPolicy.checkpointThenCopy(dbFile, stagedDb)) {   // :1337
        throw IllegalStateException("Backup failed: the vault database kept changing…")
    }
}
BackupExportPolicy.zipVaultEntriesToStream(FileOutputStream(stagingZip)) { zos ->
    if (dbFile.exists()) {
        zos.putNextEntry(ZipEntry("noteflow.sqlite"))
        FileInputStream(stagedDb).use { fis -> fis.copyTo(zos) }   // :1346 verified snapshot
        zos.closeEntry()
    }
    …
```

The raw live-file copy (`FileInputStream(dbFile).use { it.copyTo(zos) }`) is gone;
the DB entry now reads a **verified staged snapshot** produced by the new pure-JVM
`services/VaultSnapshotCopyPolicy.kt` (`checkpointThenCopy` `:76-98`):

1. `checkpoint?.checkpointFull()` runs **before any source byte is read** (`:84`);
2. the source is SHA-256 digested BEFORE the copy, the copy runs, the source is
   digested AGAIN, and the staging is digested (`:87-96`);
3. the snapshot is accepted **only when all three match** — proof the source held
   the copied state for the entire copy window, so a torn byte can never be
   silently shipped;
4. a source that changed mid-copy (a concurrent auto-checkpoint) is retried up to
   `MAX_VERIFY_ATTEMPTS = 3` (`:38`); on exhaustion the copy **fails closed** with a
   loud `IllegalStateException` and the torn staging deleted — a backup is never
   quietly broken.

The staged snapshot is transient (`File(context.cacheDir, backupName + ".sqlite-snapshot")`,
`:1327`) and deleted in the `finally` (`stagedDb.delete()` `:1446`).

## R2-B1D-03 (LOW) — LocalSend `VAULT_BACKUP` built the archive from the live main DB file WITHOUT the pre-export `wal_checkpoint(FULL)`

### Before

`LocalSendSendDialog.buildPayloadFile` (`LocalSendSendDialog.kt:142` at audit)
called `exportBackup` directly:

```kotlin
LocalSendPayload.VAULT_BACKUP ->
    ImportExportService.exportBackup(context, viewModel.repository.encryptionKey, backupPassword = null)
```

No checkpoint before the archive build — every other producer (HomeScreen,
WebDAV) checkpointed first. A send while the WAL held recent committed frames
silently shipped an archive missing the last note edits.

### After

The LocalSend `VAULT_BACKUP` branch now routes through the SAME disciplined
producer, passing its repository (`LocalSendSendDialog.kt:141-152`) so
`exportBackup` runs the FULL checkpoint + re-stamp + verified copy before any
archive byte moves:

```kotlin
LocalSendPayload.VAULT_BACKUP ->
    ImportExportService.exportBackup(
        context,
        viewModel.repository.encryptionKey,
        backupPassword = null,
        repository = viewModel.repository
    )
```

## Definition-of-done coverage — every DB-file copy producer runs checkpoint-then-copy

`exportBackup` requires the `NoteRepository` (`:1312`) and is the single owner of
checkpoint-then-copy, so **all four producers inherit the discipline by
construction**:

| Producer | Routing |
|----------|---------|
| HomeScreen device-keyed backup | `HomeScreen.kt:613-617` → `exportBackup(…, repository = viewModel.repository)` |
| HomeScreen password backup | `HomeScreen.kt:1419-1424` → `exportBackup(…, backupPasswordInput, repository = viewModel.repository)` |
| WebDAV upload | `NoteflowViewModel.kt:3691-3694` `exportEncryptedBackupToZip` → `exportBackup(getApplication(), repository.encryptionKey, repository = repository)` |
| LocalSend `VAULT_BACKUP` | `LocalSendSendDialog.kt:147-152` → `exportBackup(…, repository = viewModel.repository)` |

The now-redundant explicit `checkpointWal()` + `stampDatabaseChecksum()` in
HomeScreen (`:610,:612` and `:1413-1416`) and WebDAV (`:3688-3691`) were removed —
single owner, no double full-file HMAC.

## No-regression evidence

- The encrypted wire format, entry names (`noteflow.sqlite`, `imports/…`,
  `voice_notes/…`), the v3 split-key header, the strength-gate, and the streaming
  encryptors are untouched — legacy restore paths (`importBackup`) unchanged.
- Existing source-pin suites that reference `exportBackup`/the HomeScreen backup
  dialog (`B2Dos07BackupExportStreamingTest`, `B2Crypto04BackupPasswordTest`,
  `B1Db03VoiceNoteEncryptionTest`, `B1Auth07IsMasterPasswordOracleTest`,
  `B2Crypto08RngHygieneTest`) all stay green.

## Evidence (tests)

`Phase137BackupCopyConsistencyTest` (7 tests) — `app/src/test/java/com/authorss81/noteflow/Phase137BackupCopyConsistencyTest.kt`:

- **Pure-JVM behavior** (real `VaultSnapshotCopyPolicy` against temp files with a
  fake checkpoint/copy seam):
  - `checkpoint runs before any copy and a stable source yields a verified
    byte-identical snapshot` — ordering `[checkpoint, copy]` + snapshot == source.
  - `a source mutated mid-copy is retried and the final snapshot is consistent,
    never torn` — a copy seam emulating a WAL auto-checkpoint (half-copy, then
    source rewrite, then stale tail): the torn attempt is detected and re-run;
    the accepted snapshot equals the FINAL source state.
  - `a perpetually-mutating source fails closed and leaves no torn staging
    behind` — after exactly `MAX_VERIFY_ATTEMPTS` retries the copy returns false
    and the torn staging file is deleted.
  - `the digest is deterministic so the before-after compare is meaningful` —
    `sha256("abc")` == the well-known digest `ba7816bf…`.
- **Source-level wiring pins**:
  - `exportBackup checkpoints re-stamps then verified-copies before zipping the
    db entry` — ordering `checkpointWal()` < `stampDatabaseChecksum(context)` <
    `VaultSnapshotCopyPolicy.checkpointThenCopy(dbFile, stagedDb)` <
    `zos.putNextEntry(ZipEntry("noteflow.sqlite"))`; the db entry reads
    `FileInputStream(stagedDb)`; the raw `FileInputStream(dbFile).copyTo(zos)` is
    gone; a racing source fails loudly.
  - `every DB-file copy producer routes through exportBackup with its repository`
    — HomeScreen (both paths, `repository = viewModel.repository`; no duplicated
    checkpoint/re-stamp), WebDAV (`repository = repository`; no duplicated
    checkpoint), LocalSend VAULT_BACKUP branch (`repository = viewModel.repository`).
  - `the policy itself checkpoints before any read and re-digests to reject torn
    copies` — `checkpoint?.checkpointFull()` precedes every `sha256Digest(source)`;
    `before.contentEquals(after)` + staging digest + `destination.delete()` +
    `maxAttempts` bounded retry.

## Verification

- `gradle testDebugUnitTest` — **1927 total (app 1877 + plugins:llm 50), 0
  failures, 0 errors, 0 skipped.**
- `gradle assembleDebug` — green (app debug APK built).
- New class in isolation: `Phase137BackupCopyConsistencyTest` 7/7 green.

## Files touched

- `app/src/main/kotlin/com/authorss81/noteflow/services/VaultSnapshotCopyPolicy.kt` (new — pure-JVM checkpoint-then-verified-copy).
- `app/src/main/kotlin/com/authorss81/noteflow/services/ImportExportService.kt` (`exportBackup` signature + checkpoint/re-stamp/verified-copy + staged-db zip entry; `stagedDb` cleanup).
- `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/HomeScreen.kt` (both backup paths pass `repository`; redundant checkpoint/re-stamp removed).
- `app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt` (`exportEncryptedBackupToZip` passes `repository`; redundant checkpoint/re-stamp removed).
- `app/src/main/kotlin/com/authorss81/noteflow/ui/components/LocalSendSendDialog.kt` (`VAULT_BACKUP` passes `repository` — R2-B1D-03).
- `app/src/test/java/com/authorss81/noteflow/Phase137BackupCopyConsistencyTest.kt` (new).
- Docs: `docs/ARCHITECTURE.md` (phase-137 note), `docs/phase-status.md` (phase-137 → DONE).

No schema change, no migration, no new dependencies, `.github/workflows/` untouched.

---

## Notes / out-of-scope

- The other DB-copy producers reviewed: `NoteflowViewModel.kt:1538,1565,2875` are
  migration/re-encrypt paths (checkpoint+re-stamp after mutations), not backup
  copies — left untouched. No NEW bugs found this phase; the adjacent
  R2-B1D-04 (restore in-heap decrypt / cap asymmetry) is triaged to phase-138 and
  was NOT touched per phase scope.
