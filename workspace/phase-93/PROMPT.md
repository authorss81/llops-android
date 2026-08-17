# Phase 93: B2-LOG-04 - Plugin download/install failure messages echo the... [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-LOG-04, LOW) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-LOG-04` (LOW)
- **Area:** Batch 2 - Logging / telemetry / info disclosure
- **Evidence:** `PluginDownloader.kt:133-137` (failure text contains the raw `entry.downloadUrl`), `DownloadablePluginInstaller.kt:105` (forwards `message.substringBefore('.')` - the URL-bearing string - into `logger.error(...)`), `PluginStoreController.kt:206,235` (forward `result.reason` manifest strings), `AndroidPluginLogger.error` `PluginLogger.kt:40` (writes `detail` verbatim to logcat); `PluginLogger.kt:16-21` KDoc promises 'never plugin content/never messages/stack traces'
- **Exploit scenario:** A malicious manifest supplies `downloadUrl: "https://attacker.example/steal?..."`; the full URL - including attacker-embedded data - is printed to logcat on refusal, and a `
` in a manifest-controlled id/URL can forge arbitrary logcat lines (log injection).

## The fix (where & how)

`PluginDownloader.kt:133-137`, `DownloadablePluginInstaller.kt:105`, `PluginStoreController.kt:206,235` - enforce the PluginLogger contract mechanically: log `e::class.java.simpleName`-style tokens or a fixed reason code, NEVER `message`/`reason` strings that embed URLs; reject `
`/`
` in plugin ids and log fields.


## Verification

- Unit test: a failure for a URL-bearing manifest produces a logcat line containing no URL and no newline injection. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-LOG-04 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-93/REPORT.md` committed: what changed (file:line), the
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
