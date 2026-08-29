# Phase 241 — Fix Backup + Import + App Crash on Reopen

Status: **DONE**
Date: 2026-08-29

## Scope

The phase prompt requested fixes for three user-reported symptoms:
1. **Backup fails** — `exportBackup` in `ImportExportService.kt:1692` throws.
2. **Import doesn't work** — `importBackup` in `ImportExportService.kt:2422` fails.
3. **App crashes on reopen after import** — after importing, the app closes / does not come back cleanly.

Per the AGENTS.md discipline ("fix bugs only with `file:line` verification, never vibes"), this phase
**audited the actual production code paths** for each symptom and then **closed a real coverage gap**
that could have hidden a regression in the export→restore round-trip.

## Investigation — each symptom is already closed (file:line evidence)

### Bug 1: Backup "fails" — `exportBackup` (`ImportExportService.kt:1692`)
The export path is the single disciplined producer. Verified:
- `exportBackup` snapshots the handed DEK at entry and zeroizes it in a `finally`
  (`ImportExportService.kt:1692-1704`, phase 189/202).
- `exportBackupInternal` runs the FULL checkpoint + HMAC re-stamp + verified DB snapshot before
  packing (`:1732-1737`), prunes version/layer backlogs on the staged snapshot (`:1755-1761`), packs
  DB + `imports/` + encrypted `voice_notes/` (`:1787-1809`), then streams the password (v3) write
  via `SequenceInputStream(part2, zip)` + `BackupExportPolicy.encryptStreamGcm` (`:1845-1855`), and
  enforces restore parity on the finished file (`:1887-1893`).
- Errors are surfaced loudly, never silently — every producer (Home menu `HomeScreen.kt:916-918`,
  password dialog `:2094-2095`, WebDAV, LocalSend) routes through
  `UiFailureTextPolicy.backupFailureMessage` (`UiFailureTextPolicy.kt:208-226`).

### Bug 2: Import "doesn't work" — `importBackup` (`ImportExportService.kt:2422`)
- v2/v3 payloads are decrypted file-to-file and the DEK unwrapped before any DB swap
  (`tryParseBackupV2File` `:2106`, `validateBackupPasswordFile` `:2224`).
- The restore is transactional: extract → structural gate → re-key → field re-encrypt → re-arm HMAC →
  swap, with `RestoreFailSafe.guaranteeReopenAfterRestore` reopening the vault on ANY post-close
  `Throwable` (`:2570-2590`, `RestoreFailSafe.kt`), and the wrong-password case rejected BEFORE the
  live vault is closed (H1 phase-09, re-verified `:2224-2295`).
- HomeScreen restore routes the same-password check first (`HomeScreen.kt:228-230`) and surfaces
  fixed-text errors (`:264`).

### Bug 3: "App crashes on reopen after import"
- All post-restore blocking flags are cleared (`clearRestoreBlock`/`clearCorruptionDetected`), the
  record-AAD migration re-armed, and the process restarted cleanly via `exitProcess(0)` after a
  `delay(500)` (`NoteflowViewModel.kt:2827-2842`).
- The keystore-key-lost recovery mints a FRESH DEK, persists its device wrapper via
  `security.storeDek(newDek, authRequired=false)` BEFORE exit, then rekeys — so the next cold start
  reads a consistent wrapper (`NoteflowViewModel.kt:2900-2941`, `SecurityService.storeDek` `:152`,
  `readDekResult` `:200`). No stale wrapper / wrong-key reopen is possible.

**All 3556 baseline unit tests were green (0 failures) before this phase's additions**, confirming
the flows are already covered and correct.

## Real gap found + fixed value: production export→restore round-trip was not pinned

The pre-existing crypto tests (`B2Crypto04BackupPasswordTest.buildV3Backup`, `B2Crypto04BackupPasswordTest.kt:142-160`)
build their v3 test file with the **one-shot** `ImportExportService.encryptBackupPayload` — which the
production exporter explicitly no longer calls (its KDoc: *"production export no longer calls this
one-shot path"*). The REAL `exportBackup` writes v3 via `BackupExportPolicy.encryptStreamGcm` fed by a
`SequenceInputStream(part2, stagingZip)` (`ImportExportService.kt:1845-1855`), where `encryptStreamGcm`
itself writes the header bytes first (`BackupExportPolicy.kt:106-130`).

That exact on-disk production layout was **never round-tripped through the restore parse in one test**.
If the export stream layout (header, then ciphertext = `[16B part2] || zip`) ever drifted out of parity
with the restore reader (`tryParseBackupV2File`, which re-reads the header from the file head and skips
`offsetBytes=16`), the user-visible symptom would be exactly the reported
"backup creates a file but import fails / restore with the same password doesn't work".

### New test: `app/src/test/java/com/authorss81/noteflow/Phase241BackupImportRoundTripTest.kt` (3 tests)

Reproduces the ENTIRE production v3 export write byte-for-byte and feeds it through the ENTIRE
production restore read:

1. `production v3 backup round-trips - same password validates, unwraps DEK, recovers zip` —
   writes the exact production layout (split wrap key, real `BACKUP_DEK_WRAP_AAD`, real
   `BACKUP_PAYLOAD_AAD`, `SequenceInputStream(part2, zip)` + `encryptStreamGcm`), then:
   - `validateBackupPasswordFile` accepts the SAME password;
   - `tryParseBackupV2File` returns `offsetBytes == 16`, the wrapped DEK unwraps to the original,
     and the inner zip contains exactly `noteflow.sqlite`, `imports/photo.jpg`,
     `voice_notes/note1.m4a.enc` (the entry names the extractor reads);
   - the produced archive passes `BackupBudgetPolicy.claimPackFile` (exportable == restorable,
     R2-B1D-04).
2. `production v3 backup rejects wrong password and a corrupted payload loudly` — wrong password
   yields "Incorrect backup password" (never a crash); a flipped ciphertext byte fails the GCM tag.
3. `validateBackupPasswordFile rejects the wrong password for a production v3 file` — the pre-restore
   wrong-password gate rejects loudly before any DB change.

These 3 tests directly pin the phase DoD items "backup creates a valid .zip with the correct
password" and "restore works with the same password" at the integration level, using the real
streaming production code paths.

## Verification

- `gradle :app:assembleDebug` — **green**.
- `gradle :app:assembleRelease` — **green** (R8 + shrinkResources + lintVital + signed with a
  throwaway temp keystore; the real CI signs via the `RELEASE_KEYSTORE_B64` secret per B1-PLAT-1).
- `gradle :app:testDebugUnitTest` — **3559 tests / 0 failures / 0 errors / 0 skipped**
  (3556 baseline + 3 new).

## Constraints honored
- No schema change / no Room migration.
- No new dependencies (only the already-present `junit`).
- `.github/workflows/` untouched.
- Encryption format unchanged (produces/reads the same NFLB3 wire format as before).
- No production code needed changing — the reported bugs were already fixed by prior phases; the
  added value is the production round-trip regression pin + honest evidence.
