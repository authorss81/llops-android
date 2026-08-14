# Phase 49: B2-UI-1 - Post-lock autosave / dispose-flush saves write... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-UI-1, HIGH) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-UI-1` (HIGH)
- **Area:** Batch 2 - Compose/UI, concurrency, TOCTOU
- **Evidence:** `NoteflowViewModel.kt:2055-2067` (`lock()` never cancels pending saves), `EditorScreen.kt:392-402` (`DisposableEffect` onDispose launches `NonCancellable+Dispatchers.IO` saves with no authenticated check), `EditorScreen.kt:464-471` (`triggerAutoSave` delay(1000) survives lock), `NoteRepository.kt:549-563` (`encryptionKey == null -> encrypt else rawText` plaintext fallback), `NoteRepository.kt:697-703,778-785` (same fallback), `NoteflowViewModel.kt:1997-2001` (createNoteVersion unguarded)
- **Exploit scenario:** On auto-lock the enqueued save coroutines run with `encryptionKey == null` and persist stroke `textContent`, `pointsJson`, sticky-note/embed textContent and full `note_versions` bodies as PLAINTEXT rows inside the SQLCipher DB - deterministic on auto-lock (the 1 s delay outlives the lock). A forensic/root attacker then recovers handwritten/OCR stroke text with no password.

## The fix (where & how)

`NoteRepository.kt:549-563,697-703,778-785` - FAIL CLOSED: when `encryptionKey == null`, throw (or skip and mark pending) instead of storing plaintext. `NoteflowViewModel.kt:2055-2067` (`lock()` cancels `saveJob` and joins the dispose-flush) and `EditorScreen.kt:392-402,464-471` - gate both flushes on `viewModel.authenticated.value` and re-queue them to run encrypted after the next unlock.


## Verification

- Unit test: a post-lock save attempt with a zeroized key neither writes plaintext nor crashes the vault - it is queued or rejected; rows persisted after an unlock cycle are all decryptable with the real key. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-UI-1 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-49/REPORT.md` committed: what changed (file:line), the
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
