# Phase 71: B2-LOG-03 - ImportExportService Log.e/w(..., e) passes the full... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-LOG-03, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-LOG-03` (MEDIUM)
- **Area:** Batch 2 - Logging / telemetry / info disclosure
- **Evidence:** `ImportExportService.kt:256,261,345,350,1076,1740,1773,1816,1891,1945,2008,2076,2133` - all pass the exception object, so logcat receives the full stack INCLUDING exception messages embedding note-title file paths (e.g. `filesDir/noteflow/imports/Cancer_Treatment_Plan_<ts>.md` per B1-DB-4)
- **Exploit scenario:** Any failed save/export/import of a note writes its title-as-filename path into logcat via the Log.e/Log.w exception path. A party reading logcat (adb/dumpstate) learns real note titles and the vault file layout. No sanitizer touches these because they bypass PrivacyCrashReporter.

## The fix (where & how)

`ImportExportService.kt` at every call site listed above - log only the sanitized exception CLASS NAME (never the exception object/message that embeds paths), or wrap I/O in a routine that converts exceptions to user-facing text without the path; audit every `Log.(e|w)(tag, msg, e)` call site to strip embedded app-private paths.


## Verification

- Unit test/grep-verification: no `Log.e/w(..., e)` call in ImportExportService outputs message text containing a path; REPORT.md lists the audited call sites. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-LOG-03 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-71/REPORT.md` committed: what changed (file:line), the
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
