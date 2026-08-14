# Phase 110: B1-NET-09 - User-Agent / metadata fingerprinting: app+version+OS... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-NET-09, INFO) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-NET-09` (INFO)
- **Area:** Batch 1 - Data-in-transit & network
- **Evidence:** `WebDavSyncService.kt:154` (`User-Agent: Noteflow-Android-WebDAV-Sync/2026`), `PluginManifestFetcher.kt:100` + `HttpsPluginDownloadTransport.kt:162` (`Noteflow-Plugin-Runtime/2026`), `LocalSendSender.kt:76-79` (`Build.MODEL`), `WebPageFetcher.kt:85-86` (`InkFlow/1.0`)
- **Exploit scenario:** A monitoring server (e.g. a malicious WebDAV server per B1-NET-01) can fingerprint the exact app, version and device model, then serve version-specific malicious payloads or pick an adjacent-component exploit. The LocalSend announce also discloses Build.MODEL to every LAN host.

## The fix (where & how)

`WebDavSyncService.kt:154`, `PluginManifestFetcher.kt:100`, `HttpsPluginDownloadTransport.kt:162`, `WebPageFetcher.kt:85-86`, `LocalSendSender.kt:76-79` - use a generic, version-less User-Agent; remove `Build.MODEL` from LocalSend announces (coordinate with phase-85/B1-NET-06 which removes it too).


## Verification

- Unit test/grep-verification: no transport sends an app-identifying version string or Build.MODEL. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-NET-09 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-110/REPORT.md` committed: what changed (file:line), the
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
