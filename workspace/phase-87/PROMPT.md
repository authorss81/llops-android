# Phase 87: B1-DB-6 - Tamper HMAC covers only the main .sqlite file (not WAL... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-DB-6, LOW) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-DB-6` (LOW)
- **Area:** Batch 1 - Data-at-rest & DB
- **Evidence:** `DatabaseSecurityHelper.computeDatabaseHmac` `DatabaseSecurityHelper.kt:49-65` streams only `noteflow.sqlite`; the DB runs WRITE_AHEAD_LOGGING `NoteflowDatabase.kt:358`, so committed-but-uncheckpointed data lives in `-wal` which the HMAC never covers; `verifyDatabaseIntegrity` at `DatabaseSecurityHelper.kt:146-154` re-baselines when the stored checksum is missing; 'Don't show again' at `NoteflowViewModel.kt:974-981` sets `settings.databaseIntegrityCheckEnabled = false` permanently
- **Exploit scenario:** (a) A WAL-only mutation before checkpoint, or a checksum-pref reset (root), edits/forges data undetected because the baseline is re-armed or the target file never covered the modified bytes. (b) Realistic: WAL replay changes the main file after a stamp -> spurious integrity alarms -> a user clicking 'Don't show again' permanently disables the only tripwire (the app walks the user into reducing protection).

## The fix (where & how)

`DatabaseSecurityHelper.kt:49-65,146-154` - include the `-wal` bytes (or checkpoint + re-stamp atomically before every baseline creation - the export paths already do this at `NoteflowViewModel.kt:2017-2019`; replicate wherever a baseline is armed); `NoteflowViewModel.kt:974-981` - never let a single checkbox permanently kill the integrity check - at most store a per-session dismissal.


## Verification

- Unit test: mutating WAL frames is detected at the next verification; the 'don't show again' choice is scoped per-session. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-DB-6 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-87/REPORT.md` committed: what changed (file:line), the
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
