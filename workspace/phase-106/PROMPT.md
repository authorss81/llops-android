# Phase 106: B2-CRYPTO-06 - Exact-to-the-millisecond timestamps in backup/sync... [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-CRYPTO-06, LOW) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-CRYPTO-06` (LOW)
- **Area:** Batch 2 - Crypto side-channels & edge cases
- **Evidence:** `ImportExportService.kt:1158` (`noteflow_backup_${System.currentTimeMillis()}.noteflow`), `WebDavSyncService.kt:202` (`noteflow_vault_backup_${System.currentTimeMillis()}.nfb`), both land in public `/Download` (`HomeScreen.kt:1196-1199`) and WebDAV; quarantine renames `*.corrupt-<timestamp>` (`NoteflowDatabase.kt:308-309`) and `PREF_CORRUPTION_TIMESTAMP` (`DatabaseSecurityHelper.kt:122-127`)
- **Exploit scenario:** Anyone who can list `/Download` (any storage-permission app, MTP/USB computer, the share recipient) or the WebDAV folder learns the exact second the user last backed up or synced - a direct proxy for 'when the user last used/closed the vault' - enabling activity profiling from data entirely OUTSIDE the app sandbox that outlives vault clearing.

## The fix (where & how)

`ImportExportService.kt:1158`, `WebDavSyncService.kt:202`, `HomeScreen.kt:1196-1199` - name exported backups with random tokens or day-granularity strings (never epoch-millis in public/remote filenames); keep internal temp/corruption names private where possible (`NoteflowDatabase.kt:308-309`, `DatabaseSecurityHelper.kt:122-127` - keep timestamps internal or day-granular).


## Verification

- Unit test: generated backup/sync filenames contain no epoch-millis. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-CRYPTO-06 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-106/REPORT.md` committed: what changed (file:line), the
  checksum/secrets handling, verification output, and any input you judged
  out-of-scope.

## Constraints

- NO DB schema change unless this fix requires one - then a migration-safe note
  in REPORT.md is MANDATORY, and the migration must never delete user data.
- Do NOT edit `.github/workflows/`. Do not add new dependencies unless required
  by the fix (then justify in the commit).
- Never log keys, passwords, or decrypted note content. Keep `allowBackup=false`,
  `ClipboardGuard`, and FLAG_SECURE intact.
- Do not fix OTHER security findings in this phase - that is a different phase.
  If you find a new related bug, document it in REPORT.md, do not fix it here.
