# Phase 56: B1-DB-7 - Restore accepts a legacy PLAIN zip and validates it... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-DB-7, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-DB-7` (MEDIUM)
- **Area:** Batch 1 - Data-at-rest & DB
- **Evidence:** `ImportExportService.kt:1392-1403` (legacy path treats any `PK`-headed payload as a plain keyless backup), `ImportExportService.kt:1476-1502` (validateAndPrepareRestoredDb tries `listOfNotNull(backupDekHex, currentDekHex, "")`), then re-key + HMAC-rearm `ImportExportService.kt:1425-1428`; only gate is the legacy confirm dialog `HomeScreen.kt:150-155`
- **Exploit scenario:** An attacker-crafted zip whose `noteflow.sqlite` was created with the empty passphrase passes validation, is re-keyed to the victim's real DEK, HMAC-rearmed, and moved over the live vault - attacker-chosen content (phishing notes, planted data, an empty DB wiping everything) is presented to the user as a 'successful restore'.

## The fix (where & how)

`ImportExportService.kt:1392-1403,1476-1502` - reject legacy unencrypted (plain-zip) backups outright, or at minimum drop the `""` empty-key candidate so only the backup's own wrapped DEK or the current DEK can open it; keep a prominent 'untrusted/unsigned backup' warning for any legacy import path that must remain.


## Verification

- Unit test: a plain SQLite DB built with the empty passphrase fails restore validation; a properly wrapped-DEK password-v2 backup still restores. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-DB-7 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-56/REPORT.md` committed: what changed (file:line), the
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
