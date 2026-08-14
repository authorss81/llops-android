# Phase 44: B1-DB-4 - Markdown/text note BODIES are stored as PLAINTEXT... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-DB-4, HIGH) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-DB-4 + B1-AUTH-06` (HIGH)
- **Area:** Batch 1 - Data-at-rest & DB (+ B1-AUTH-06 same root cause)
- **Evidence:** `ImportExportService.kt:55-75` (persistFile writes plaintext to `filesDir/noteflow/imports`), `ImportExportService.kt:77-83` (readUriBytes), `MainActivity.kt:339` and `MainActivity.kt:436` (`File(path).writeText(newText)` on every save - the file is authoritative), `HomeScreen.kt:217,227-236`, `WikiLinkParser.kt:64-73` (reads the plaintext file), `NoteRepository.kt:356-362` (only the DB copy `extractedText` is field-encrypted)
- **Exploit scenario:** The full body of every markdown/text note sits in cleartext in app-private storage even with a strong master password. An attacker with `run-as`/root/forensic image reads every note's complete text without touching the SQLCipher DB, the password, or the DEK. The vault encryption is a no-op for the entire note-body class. B1-AUTH-06 (MEDIUM) documents the same root issue and is FIXED BY THIS PHASE.

## The fix (where & how)

`ImportExportService.kt:55-83` (persistFile/readUriBytes), `MainActivity.kt:311-341,410-438` (readText/writeText save loops), `HomeScreen.kt:217,227-236`, `WikiLinkParser.kt:64-73`. Store note bodies ONLY in the field-encrypted `extractedText` column; stop persisting plaintext `.md`/`.txt` files as the authoritative content. Materialize temp files transiently if the rendering pipeline needs them and delete them on close (or, at minimum, encrypt imported source files with the DEK and decrypt on read). Re-encrypt on every save so no plaintext bytes ever persist in `imports/`.


## Verification

- Unit test: after save+reload the note body is only ever retrievable through the encrypted DB column; no plaintext note-body file exists under `filesDir` after an edit cycle (assert `imports/` contains no readable note bodies). `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-DB-4 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-44/REPORT.md` committed: what changed (file:line), the
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
