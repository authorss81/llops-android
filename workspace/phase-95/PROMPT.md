# Phase 95: B2-UI-4 - lock() clears the session StateFlows but unlock()... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-UI-4, LOW) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-UI-4` (LOW)
- **Area:** Batch 2 - Compose/UI, concurrency, TOCTOU
- **Evidence:** `NoteflowViewModel.kt:1123` (`dataInitialized = false`), `:1125-1127` (`initializeData()` early-returns when set), `:1156-1172` (init block sets the flag on first start), `:1889-1896` (verifyMasterPassword success -> initializeData() silently no-ops), `:2055-2067` (lock() nulls `_selectedNotebook`, `_selectedSection`, `_sections`, `_pages`), `:1246-1253` (observePages only (re)created from selectSection)
- **Exploit scenario:** After every lock->unlock cycle the app unlocks into an empty home list: observeSections/observePages are only started from initializeData() (first run) or explicit taps, and Room flows re-emit only on DB change, not DEK change - the user must manually re-navigate to see notes. The 'fresh authorization boundary' is half-implemented: state is cleared but the persistent activeSectionId/activePageId prefs still point at the previous session.

## The fix (where & how)

`NoteflowViewModel.kt:1123-1127,1156-1172,1889-1896,2055-2067,1246-1253` - reset `dataInitialized = false` in `lock()` and have unlock success call `initializeData()` (restoring active notebook/section from prefs and re-arming `observeSections`/`observePages`) before any page content is re-shown; alternatively keep observers alive and emit a key/state generation that forces re-collection on unlock.


## Verification

- Unit test: lock then unlock re-populates the home lists without manual navigation. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-UI-4 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-95/REPORT.md` committed: what changed (file:line), the
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
