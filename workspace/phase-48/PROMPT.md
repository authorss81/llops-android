# Phase 48: B2-LOG-01 - AppStartupLogger's uncaught-exception handler dumps... [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-LOG-01, HIGH) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-LOG-01` (HIGH)
- **Area:** Batch 2 - Logging / telemetry / info disclosure
- **Evidence:** `MainActivity.kt:85-86` (PrivacyCrashReporter registered first, AppStartupLogger second => AppStartupLogger's handler is OUTERMOST and runs first), `AppStartupLogger.kt:38-42` (captures the previous handler + owns the first shot), `AppStartupLogger.kt:55-77` (`throwable.printStackTrace(PrintWriter)` then `Log.e(TAG, logBlock)` at :75 and `appendToFile` at :76 with ZERO sanitization), `PrivacyCrashReporter.kt:48-57` (its sanitized entry is the only logcat-safe output but never executes first)
- **Exploit scenario:** A crash embedding an app-private path (vault file layout, note-title filenames) is written VERBATIM to logcat. Any party with `adb logcat`/`dumpstate` reads unredacted vault paths and note titles. PrivacyCrashReporter's sanitizer never runs first.

## The fix (where & how)

`AppStartupLogger.kt:38-42,55-77` - remove the crash-dump behavior entirely (AppStartupLogger stays for startup timing events only); let PrivacyCrashReporter own uncaught-exception logging. Alternatively route AppStartupLogger's `logCrash` through the same `sanitizeMessage`/stack-scrubbing and never write the raw trace to logcat.


## Verification

- Unit test: `logCrash` produces no logcat/raw output containing app-private paths; a crash with `/data/user/0/<app>/files/...` appears only sanitized via PrivacyCrashReporter. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-LOG-01 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-48/REPORT.md` committed: what changed (file:line), the
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
