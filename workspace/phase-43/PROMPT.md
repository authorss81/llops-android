# Phase 43: B1-DB-1 - Over-broad 'corruption' classifier quarantines HEALTHY... [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-DB-1, HIGH) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-DB-1` (HIGH)
- **Area:** Batch 1 - Data-at-rest & DB
- **Evidence:** `NoteflowDatabase.kt:287-296` (`isDatabaseCorruptException` returns true for ANY `android.database.sqlite.SQLiteException` - a superclass of `SQLiteDatabaseLockedException`, `SQLiteCantOpenDatabaseException`, I/O/ENOSPC open failures), `NoteflowDatabase.kt:259-261,274-276` (immediately `quarantineCorruptDatabase()` then create a brand-new EMPTY DB), `NoteflowViewModel.kt:949-959` ('Start fresh' clears the flag and discards the quarantined copy)
- **Exploit scenario:** A transient, recoverable open failure (lock contention, disk pressure, torn I/O on kill) is misclassified as cryptocorruption: the healthy vault is renamed aside, an empty vault is created, and the user is guided to 'Start fresh' which permanently discards the healthy copy. A routine hiccup becomes permanent data loss, and an attacker who can trigger DB lock contention can force quarantine on demand.

## The fix (where & how)

`NoteflowDatabase.kt:287-296`. Match ONLY specific corruption conditions: `android.database.sqlite.SQLiteDatabaseCorruptException` and the message strings `"file is not a database"`/`"malformed"`/`"database disk image is malformed"`. Never treat 'database is locked'/I/O/ENOSPC class exceptions as corruption. Additionally, quarantine alone must NOT auto-create a replacement DB - surface recovery first and only create an empty vault after the user's explicit choice.


## Verification

- Unit tests for `isDatabaseCorruptException` classifying locked/I/O/ENOSPC exceptions as non-corrupt and genuine corrupt/malformed messages as corrupt. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-DB-1 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-43/REPORT.md` committed: what changed (file:line), the
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
