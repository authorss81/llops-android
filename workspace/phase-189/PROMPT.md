# Phase 189: Fix — Backup-to-file and backup-from-file FAIL after a vault export (session-state corruption) [NOT STARTED]

You are working on **InkFlow/Noteflow**. USER REPORT: after exporting the vault, (a) the
"Backup to file" and "Backup from file" (restore) actions FAIL, (b) notes show
"Unreadable (decryption failed)" and the last-used notebook is not shown — but closing
and reopening the app fixes (b). This strongly suggests the EXPORT leaves the in-memory
session (key / DB handle / notebook state) in a degraded state, NOT a disk-data problem.
Phase-182 fixes the decryption-marker symptom; THIS phase fixes the backup failure.

Read `docs/ARCHITECTURE.md`, `docs/phase-status.md`, `workspace/phase-181/REPORT.md`,
`workspace/phase-182/REPORT.md`, and `workspace/phase-169/REPORT.md` first.

## WORKFLOW RULE
Work in small steps; `git add -A && git commit -m "llops: phase-189 step N: <desc>" && git push`
after EVERY step. Never sit on uncommitted work.

## Step 1 - Reproduce + trace the export→backup-failure chain (commit it)
- The vault export path: `ImportExportService.exportBackup` (`:1390`) does
  `repository.checkpointWal()` + `stampDatabaseChecksum` + `VaultSnapshotCopyPolicy.checkpointThenCopy`
  + staged-snapshot prunes, then encrypts the pack. After it returns, HomeScreen
  `exporter.export(ENCRYPTED_BACKUP, cacheFile)` copies to the chosen destination.
- Trace what `checkpointWal()` / `stampDatabaseChecksum` / `pruneStagedSnapshotVersions`
  / `pruneStagedSnapshotLayers` do to the LIVE vault (Do they run on the live DB or
  the staged copy? Do they mutate `repository.encryptionKey` / the DEK / Room's open
  connection? Do they close/reopen the DB?).
- Then trigger "Backup to file" again right after the export and capture the ACTUAL
  exception (snackbar text / `UiFailureTextPolicy.backupFailureMessage` mapping) and
  "Backup from file" (restore) failure — what does `validateBackupPasswordFile` /
  `importBackup` throw?
- Also check the LAST-NOTEBOOK loss: does `checkpointWal`/export completion reset
  `_selectedNotebook` or `lastNotebookId` (`NoteflowViewModel.kt:4768` re-stamps on
  selection change; who clears it?).
- COMMIT this step with the full before/after session-state trace.

## Step 2 - Fix the root cause
- Whatever the export leaves degraded: the export must be SIDE-EFFECT-FREE on the
  live session. The DEK (`repository.encryptionKey`) must be unchanged, the DB
  connection must stay open/usable, `lastNotebookId` must survive, and the next
  backup/restore must work with the SAME key the export used.
- If the export (or its staged-snapshot helpers) closes/zeroizes/reopens anything
  that makes a subsequent `exportBackup`/`importBackup` fail, fix it so the export
  runs entirely against the staged copy and never touches live handles.
- If the failure is that the SECOND export reuses a leftover `stagingZip`/`stagedDb`
  temp file or a stale cache file (name collision from `BackupExportPolicy.stagingFileName`),
  make temp names unique + clean up on every outcome.
- COMMIT this step.

## Step 3 - Regression proof
- `gradle assembleDebug` green + `gradle testDebugUnitTest` green (except the
  pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure + the 2
  `B1Plat01ReleaseSigningTest` asserts, untouched).
- Add pure-JVM tests: export-then-export (two exports back-to-back succeed), the
  export's session-state invariants (key unchanged, DB handle still usable,
  notebook id preserved), and a source pin that export helpers never touch live
  encryption keys or close the DB.

## Definition of done
- After ANY export, "Backup to file" and "Backup from file" work again; last-used
  notebook still shown; no decryption-marker regression.
- `workspace/phase-189/REPORT.md`: reproduced exception stack/messages, root cause,
  fix, test list.
- Commit + push.

## Constraints
- Do NOT edit `.github/workflows/`. No new dependencies. No DB schema change.
- Never write plaintext while locked. Never zeroize the live DEK during export.
- Reuse the existing backup budget/guard machinery (`BackupBudgetPolicy`,
  `BackupExportPolicy`, `VaultSnapshotCopyPolicy`).