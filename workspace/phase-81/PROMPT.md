# Phase 81: B2-DOS-05 - Attachment/import ingestion slurps attacker- or... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-DOS-05, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-DOS-05` (MEDIUM)
- **Area:** Batch 2 - Resource-exhaustion / DoS
- **Evidence:** `EditorScreen.kt:720` (photo embed `readBytes()`), `EditorScreen.kt:215,237` (background/texture pickers - same), `ImportExportService.kt:77-83` (readUriBytes for the whole import pipeline + legacy/direct backup-restore, e.g. `NoteflowViewModel.kt:1623` and `2041` sourceZip.readBytes()), `DocumentTextExtractor.kt:35` (`file.readText()`) and `:49-67` (`extractPdfText` readBytes on the whole PDF + a second String copy - no cap; only the `else` branch at :40 carries the 1 MB guard)
- **Exploit scenario:** Reachable via exported ACTION_SEND (B1-PLAT-2) or any picker: a 500 MB 'photo'/PDF/zip is fully readBytes()-ed into the heap (then re-copied for String(...)) -> OOM crash at embed/import time; repeated action is a trivial repeated DoS.

## The fix (where & how)

`EditorScreen.kt:720,215,237`, `ImportExportService.kt:77-83`, `NoteflowViewModel.kt:1623,2041`, `DocumentTextExtractor.kt:35,49-67` - query `OpenableColumns.SIZE`/ContentResolver before reading and reject above a cap (25 MB), or stream-copy to the app-private file with a running-byte counter that aborts over budget (as `copyWithLimit` does, `ImportExportService.kt:1216-1253`); bound DocumentTextExtractor reads to the first N MB.


## Verification

- Unit test: an oversized stream (over the cap) is rejected/aborted without full heap slurp. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-DOS-05 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-81/REPORT.md` committed: what changed (file:line), the
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
