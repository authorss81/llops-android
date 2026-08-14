# Phase 60: B1-PLAT-4 - Auto-lock is OFF by default and lock() fires only on... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-PLAT-4, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-PLAT-4` (MEDIUM)
- **Area:** Batch 1 - Android platform surface
- **Evidence:** `SettingsManager.kt:178-179` (`autoLockTimeoutSeconds` defaults to `0` = disabled), `MainActivity.kt:97-109` (ON_PAUSE only scrubs the clipboard; `viewModel.lock()` only on ON_STOP), `MainActivity.kt:189-199` (inactivity lock only on the NEXT touch), `NoteflowViewModel.kt:2055-2067` (lock() exists but is only reachable via the above)
- **Exploit scenario:** On a no-keyguard device the user leaves the app foregrounded and walks away: notes remain readable indefinitely. Even with a timeout, lock fires only after the next touch, and display-off (which pauses, not stops) leaves the same unlocked notes on resume. FLAG_SECURE (MainActivity.kt:89-93) is also only applied to non-debug builds.

## The fix (where & how)

`MainActivity.kt:89-109,189-199` - lock on ON_PAUSE or hook `ACTION_SCREEN_OFF`/keyguard state (API 27+ broadcast) in addition to ON_STOP; `SettingsManager.kt:178-179` - ship auto-lock ENABLED by default (e.g. 5 min); apply FLAG_SECURE unconditionally (drop the debug-build condition).


## Verification

- Manual + unit verification: with default settings a simulated ON_PAUSE/screen-off transitions to the locked state; FLAG_SECURE is always applied. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-PLAT-4 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-60/REPORT.md` committed: what changed (file:line), the
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
