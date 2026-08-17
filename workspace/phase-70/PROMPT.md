# Phase 70: B2-LOG-02 - app_startup.log is appended without any size cap,... [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-LOG-02, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-LOG-02` (MEDIUM)
- **Area:** Batch 2 - Logging / telemetry / info disclosure
- **Evidence:** `AppStartupLogger.kt:79-88` (append-only FileWriter, no length check, no rotation, no delete), `AppStartupLogger.kt:17` (LOG_FILE_NAME), every lifecycle event appended forever (`MainActivity.kt:98`) plus every raw crash block (`AppStartupLogger.kt:75-76`); contrast `PrivacyCrashReporter.kt:62-64` which caps at 500KB
- **Exploit scenario:** Weeks of lifecycle events + a few raw crash dumps grow `app_startup.log` into many MB on the same partition as the encrypted vault (contributing to ENOSPC), and the crash stack traces of B2-LOG-01 (vault paths, note-title filenames) are retained indefinitely with no user-visible purge.

## The fix (where & how)

`AppStartupLogger.kt:79-88` - cap the file (reuse the ~500KB budget), rotate on size (keep last N), prune on init, and exclude raw stack dumps (see phase-48/B2-LOG-01). `AppStartupLogger.kt:90-112` (getLogs/clearLogs dead code) - if any 'export/share logs' UI is ever added this file must be sanitized before leaving the device; note that in a comment or remove the dead code.


## Verification

- Unit test: exceeding the size budget triggers rotation and the log never grows past the cap. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-LOG-02 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-70/REPORT.md` committed: what changed (file:line), the
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
