# Phase 72: B2-UI-2 - In-app lock paths (manual 'Lock Vault Now', idle... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-UI-2, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-UI-2` (MEDIUM)
- **Area:** Batch 2 - Compose/UI, concurrency, TOCTOU
- **Evidence:** `MainActivity.kt:99-106` (ON_PAUSE -> ClipboardGuard.scrubIfOwnCopy; ON_STOP -> viewModel.lock() - separate events, the lock itself never scrubs), `MainActivity.kt:189-199` (idle auto-lock with no scrub), `Dialogs.kt:562-572` ('Lock Vault Now' with no scrub), `ClipboardGuard.kt:21-35` (60 s scrub window), `MediaEmbedComponents.kt:352-354` and `OcrResultDialog.kt:149-150` (copy sources)
- **Exploit scenario:** The user copies a code block or OCR text (decrypted content) then locks in-app; ON_PAUSE may never fire (app stays foreground) so the decrypted snippet sits on the system clipboard where ANY installed app (clipboard-readers/'smart paste') can read it. Distinct from when lock fires (B1-PLAT-4) - this is that the clipboard is not cleared when it does.

## The fix (where & how)

`NoteflowViewModel.lock()` (`NoteflowViewModel.kt:2055-2067`) - call `ClipboardGuard.scrubIfOwnCopy(context)` (via applicationContext) inside `lock()`, or in every `viewModel.lock()` call site (auto-lock `MainActivity.kt:189-199`, manual `Dialogs.kt:562-572`, ON_STOP `MainActivity.kt:103-106`); clear the guard timestamp on scrub so foreign copies aren't wiped.


## Verification

- Unit test: recordCopy -> lock -> clipboard is cleared; a foreign (non-app) copy is not wiped by lock. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-UI-2 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-72/REPORT.md` committed: what changed (file:line), the
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
