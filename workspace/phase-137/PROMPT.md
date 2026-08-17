# Phase 137: Backup/export file-copy consistency — no more torn main-file snapshots or stale-WAL archives [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report-round2.md`**
first (findings R2-B1D-05, R2-B1D-03) and `docs/phase-status.md` +
`docs/ARCHITECTURE.md`. This phase closes the two DB-file-copy paths that
produce inconsistent (torn/stale) archives.

## Source findings (both OPEN, LOW)

1. **R2-B1D-05** — `exportBackup` copies the live main DB file with a plain
   unbounded FileInputStream (`ImportExportService.kt:1314-1318`), unlike the
   checkpoint-first producers (`HomeScreen.kt:584-588`, `NoteflowViewModel.kt:
   3346-3350`). A WAL auto-checkpoint (1000-page threshold) during the copy
   yields a main-file snapshot that never existed → a silently broken backup
   that only surfaces at restore time.
2. **R2-B1D-03** — LocalSend `VAULT_BACKUP` builds the archive from the live
   main DB file WITHOUT the pre-export `wal_checkpoint(FULL)` every other
   producer does (`LocalSendSendDialog.kt:142` calls `exportBackup` directly).
   The receiving archive silently misses the last note edits held in the WAL.

## The fix (where & how)

- Give `ImportExportService.exportBackup` (`:1314-1318`) the same discipline as
  the checkpoint-first producers: run `repository.checkpointWal()` (FULL) BEFORE
  copying, and copy the DB file with a size+HMAC-verified staged copy (or open
  the file for copy while holding a read lock after a fresh FULL checkpoint).
  Prefer the `checkpointWal(FULL)` + verified-copy pattern already proven in
  `HomeScreen.kt:584-588` / `NoteflowViewModel.kt:3346-3350`.
- Make LocalSend's vault-backup path call the same checkpoint+re-stamp before
  `exportBackup` (`LocalSendSendDialog.kt:142`), exactly like the HomeScreen and
  WebDAV producers.

## Verification

- New/updated pure-JVM unit tests: a copy producer that runs checkpoint-first
  yields a main-file snapshot consistent with the WAL (verify via a fake
  repository/File seam); source pins that `exportBackup` and the LocalSend
  vault path checkpoint before copying.
- `gradle testDebugUnitTest` then `gradle assembleDebug`, report in
  `workspace/phase-137/REPORT.md`.

## Definition of done

- Both findings closed with `file:line` before/after evidence.
- Every DB-file copy producer (HomeScreen, WebDAV, exportBackup, LocalSend)
  runs checkpoint-then-copy.
- No existing backup/restore flow regressed.

## Constraints

- NO DB schema change. Do NOT edit `.github/workflows/`. No new dependencies.
- Never log keys, passwords, or decrypted note content. Keep `allowBackup=false`.
- Do not fix OTHER findings in this phase — document new bugs in REPORT.md.