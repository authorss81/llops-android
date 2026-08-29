# Phase 241 — Fix Backup + Import + App Crash on Reopen

## Goal
Fix the backup and import failures observed on real device:
1. **Backup fails** — `exportBackup` in `ImportExportService.kt:1692` throws
2. **Import doesn't work** — `importBackup` in `ImportExportService.kt:2422` fails
3. **App suddenly crashes on reopen** — after importing, the app closes automatically

## Context — Verified Root Cause (from screenshots)

### Bug 1: Backup fails
**File:** `app/src/main/kotlin/com/authorss81/noteflow/services/ImportExportService.kt:1692`
**Root cause:** Likely the `exportBackupInternal` is failing during the zip creation, password derivation, or the SAF write. Common causes:
- Master key derivation fails (Phase 49 B1-CRYPTO-02 changed the DEK wrapping to auth-gated)
- The `NoteflowDatabase` isn't properly opened during backup
- The `BackupService` or SAF directory creation is broken
- The output stream isn't being closed properly

### Bug 2: Import doesn't work
**File:** `app/src/main/kotlin/com/authorss81/noteflow/services/ImportExportService.kt:2422`
**Root cause:** Same family of issues — likely the password input doesn't unlock the backup encryption, the zip is empty, or the database restore fails partway through.

### Bug 3: App crashes on reopen after import
**File:** `MainActivity.kt` (likely in `NoteflowTheme` or `NoteflowViewModel` initialization)
**Root cause:** After import, the DEK is re-derived from the backup's password, but something fails on next app open — likely:
- The wrapped DEK in SharedPreferences has a stale wrapper that doesn't match the new master password
- Phase 62's B1-CRYPTO-03 commit (MasterPasswordCredential) introduced a new serialization format; if the import code doesn't use the new format, the new key doesn't match the old key
- The DB HMAC is stale, so the integrity check fails on first open
- The vault quarantine flag is set and the app can't recover

## Files to Fix

### 1. `app/src/main/kotlin/com/authorss81/noteflow/services/ImportExportService.kt`
Audit the full backup/import flow:
- `exportBackup(context, key, backupPassword, repository)` line 1692
- `exportBackupInternal` line 1706 — ensure the master key is derived, the wrapped DEK is unwrapped, all strokes and pages are serialized, the manifest is written, the zip is sealed
- `importBackup` line 2422 — ensure the backup password unwraps the DEK, the manifest is parsed, the database is restored, the master key is re-wrapped
- Add better error reporting (`viewModel.notifyBackupFailure(message)`)

### 2. `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/HomeScreen.kt` (or wherever `createBackupAndExport` is called)
Ensure:
- The master password is passed correctly
- The `BackupService` or `ImportExportService` is called with the correct arguments
- Errors are shown to the user (not silent failures)

### 3. `app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt` + `NoteflowViewModel.kt`
Ensure the post-import app initialization:
- Detects the new DEK from SharedPreferences
- Recovers from the quarantine flag if the DB integrity check fails
- Shows a recovery screen instead of crashing
- Resets the master key wrapper if the new password doesn't match

## Constraints
- No new dependencies
- No schema change to Room (must work with existing DBs)
- No `.github/workflows/` edits
- Must not break the existing 3420+ tests
- Must not change the encryption format (would break existing users)

## DoD
- `gradle :app:assembleDebug` + `assembleRelease` green
- `gradle :app:testDebugUnitTest` 3420+ tests green
- Manual test: backup creates valid .zip file with correct password
- Manual test: restore from backup works with the same password
- Manual test: app doesn't crash on reopen after import
- Manual test: errors are shown to user (not silent)
- `workspace/phase-241/REPORT.md` with file:line evidence
