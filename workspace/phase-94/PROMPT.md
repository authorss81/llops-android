# Phase 94: B2-LOG-05 - WebDAV failure paths echo raw exception text (which... [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-LOG-05, LOW) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-LOG-05` (LOW)
- **Area:** Batch 2 - Logging / telemetry / info disclosure
- **Evidence:** `WebDavSyncService.kt:189` (`SyncResult(false, "Connection failed: ${e.localizedMessage ?: e.message}")`), `:232` and `:299` (same for upload/download), `:64-70` (throw IllegalArgumentException with `MalformedURLException` message containing the user's raw input), `WebDavSyncDialog.kt:201,246` (syncStatus = res.message rendered on screen)
- **Exploit scenario:** `validateServerUrl`/`normalizeBaseUrl` echo the user's typed string verbatim; if the user pasted `https://user:pass@host/...`, the raw string (with credentials) surfaces in the echoed exception text shown in the dialog - shoulder-surf/screenshot disclosure of the WebDAV password.

## The fix (where & how)

`WebDavSyncService.kt:64-70,189,232,299` and `WebDavSyncDialog.kt:201,246` - never include `e.message` in user-facing sync results; map known failures (connect, auth, HTTP status) to fixed strings; when echoing any URL-derived text, strip `userinfo` and scrub `://host/path` to `host/...`.


## Verification

- Unit test: a `MalformedURLException`/connect error with `user:pass@` in the input produces a status string free of the credentials and userinfo. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-LOG-05 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-94/REPORT.md` committed: what changed (file:line), the
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
