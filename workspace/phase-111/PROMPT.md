# Phase 111: B2-LOG-07 - JankStatsHelper logs screen names and per-frame timing... [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-LOG-07, INFO) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-LOG-07` (INFO)
- **Area:** Batch 2 - Logging / telemetry / info disclosure
- **Evidence:** `JankStatsHelper.kt:38` (`Log.w(TAG, "Jank detected on $screenName!...")`, threshold 16 ms -> fires on every slow frame on low-end devices), `MainActivity.kt:112` (MonitorJank(MainActivity)), `MainActivity.kt:98` (AppStartupLogger.logEvent on every lifecycle event, appended to logcat via `AppStartupLogger.kt:48`)
- **Exploit scenario:** A logcat observer (adb, dumpstate, device-owner debug) can reconstruct an activity/timeline profile - when the user opens/leaves the app, session lengths, and load periods. Privacy-relevant behavioral metadata for a local-first notes app; the constant jank logging also feeds B2-LOG-02's unbounded growth.

## The fix (where & how)

`JankStatsHelper.kt:38` and `MainActivity.kt:112` - gate jank logging behind `BuildConfig.DEBUG` (or an opt-in developer flag); `MainActivity.kt:98` - reduce lifecycle events to a single process-start event; never log screen names of screens that reveal note titles.


## Verification

- Unit test/grep-verification + documented manual: release builds emit no jank/lifecycle logcat lines. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-LOG-07 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-111/REPORT.md` committed: what changed (file:line), the
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
