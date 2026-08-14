# Phase 69: B1-AUTH-05 - note.sourceFilePath is stored unencrypted and never... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-AUTH-05, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-AUTH-05` (MEDIUM)
- **Area:** Batch 1 - App logic & auth
- **Evidence:** `MainActivity.kt:311-341` (`File(page.sourceFilePath).readText()` / `.writeText(newText)` for every .md/.txt note), `WikiLinkParser.kt:64-73` (readText), `HomeScreen.kt:217,227-236` (imports set sourceFilePath), `ImportExportService.kt:1414-1429` (restoreFromZip validates zip entry NAMES but never re-validates the restored DB's `pages.sourceFilePath` column), `NoteRepository.kt:424-428` (only deletePagePermanently bounds the path, and only by substring)
- **Exploit scenario:** Restoring a malicious backup transplants a DB whose `pages.sourceFilePath` rows point anywhere the app can access. Opening the note surfaces that file's contents (disclosure); saving writes attacker-chosen bytes to an attacker-chosen path the app can write. Re-usable by any plugin via the repository.

## The fix (where & how)

Canonicalize `sourceFilePath` at restore/import time (`ImportExportService.kt:1414-1429`) and confine it under the imports root (reject non-matching absolute paths and any `..`); enforce the same confinement in `updatePageSource` and on every read/write in `MainActivity.kt:311-341` and `WikiLinkParser.kt:64-73`.


## Verification

- Unit test: a restored DB containing `sourceFilePath` outside the imports root is sanitized (or the page is flagged); read/write under the confinement rule works. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-AUTH-05 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-69/REPORT.md` committed: what changed (file:line), the
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
