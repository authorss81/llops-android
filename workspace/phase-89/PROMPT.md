# Phase 89: B1-PLAT-5 - PrivacyCrashReporter path-redaction regex targets the... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-PLAT-5, LOW) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-PLAT-5` (LOW)
- **Area:** Batch 1 - Android platform surface
- **Evidence:** `PrivacyCrashReporter.kt:77` (regex `/data/user/\d+/com\.authorss81\.noteflow/\S+`) vs the real runtime data dir `/data/user/0/com.aistudio.inkflow.app.bkxjrz/` (applicationId, `app/build.gradle.kts:15`)
- **Exploit scenario:** The sanitizer matches the namespace while the device uses the applicationId, so the regex never matches any real path and stack-trace messages carry full app-private file paths (SQLCipher DB names, vault layout, imports/exports dirs) into `noteflow_sanitized_crash.log` - defeating the stated 'zero leak' guarantee of the report.

## The fix (where & how)

`PrivacyCrashReporter.kt:77` - build the redaction patterns from `context.packageName` / `context.dataDir.path` at runtime instead of a hardcoded string; keep the namespace-based pattern only as a secondary fallback.


## Verification

- Unit test: a stack trace containing the real `/data/user/0/com.aistudio.inkflow.app.bkxjrz/...` path is fully redacted; the namespace variant also redacts. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-PLAT-5 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-89/REPORT.md` committed: what changed (file:line), the
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
