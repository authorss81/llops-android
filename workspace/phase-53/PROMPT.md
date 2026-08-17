# Phase 53: B1-DB-2 - Plaintext->SQLCipher migration deletes the original... [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-DB-2, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-DB-2` (MEDIUM)
- **Area:** Batch 1 - Data-at-rest & DB
- **Evidence:** `NoteflowDatabase.kt:191-232` `migratePlaintextIfNeeded`: on success `dbFile.delete(); tempFile.renameTo(dbFile)` (:220-221); the `catch` block (:224-231) deletes `dbFile` PLUS `-wal`/`-shm` on ANY exception, with no quarantine name and no `setCorruptionDetected` call (no recovery screen after)
- **Exploit scenario:** A pre-SQLCipher (legacy plaintext) install upgrades; if migration fails mid-way (disk full during sqlcipher_export, torn source, temp-file permission error) the ORIGINAL plaintext database - the only copy of the user's notes - is deleted, with no `*.corrupt-*` rescue and no recovery screen. Exactly the Phase-09 H2 defect the code fixes for normal opens but not for migration.

## The fix (where & how)

`NoteflowDatabase.kt:191-232` `migratePlaintextIfNeeded` - on migration failure rename the original to `noteflow.sqlite.migrate-failed-<ts>` (preserve bytes), set the corruption flag via `DatabaseSecurityHelper` so the user reaches CorruptionRecoveryScreen instead of silently losing the file, and remove the unconditional `dbFile.delete()` in the catch block (only delete after a verified successful migration file is in place).


## Verification

- Unit test (or documented manual verification path): a forced migration failure (e.g. nonexistent temp dir) leaves the original file intact under the `migrate-failed-<ts>` name and raises the corruption flag. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-DB-2 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-53/REPORT.md` committed: what changed (file:line), the
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
