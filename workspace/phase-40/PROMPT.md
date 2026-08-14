# Phase 40: B1-NET-01 - WebDAV sync: server-controlled PROPFIND href steers... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-NET-01, HIGH) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-NET-01` (HIGH)
- **Area:** Batch 1 - Data-in-transit & network
- **Evidence:** `WebDavSyncService.kt:263-274` (regex over server XML; absolute-URL branch `latestRemotePath.startsWith("http")` at 271-274 uses the server-supplied host), `WebDavSyncService.kt:276` (connects to that host), `WebDavSyncService.kt:151-153` (`createConnection` sets `Authorization: Basic base64(user:pass)` on ANY host), `WebDavSyncService.kt:119-127` (`requireSecureUrl` only gates the scheme, never compares the destination host to `config.serverUrl`)
- **Exploit scenario:** A compromised/malicious WebDAV server answers PROPFIND with `<d:href>https://attacker.example/...nfb</d:href>`; the app connects there and ships the user's Basic credentials and the encrypted backup bytes. With `allowInsecureHttp=true` (`WebDavSyncDialog.kt:44,117`) the href can be `http://169.254.169.254/...` or an arbitrary private IP and the credentials travel in cleartext - the documented 'opt-in bypass' guard checks scheme + target local-network property but never that the target is the configured server.

## The fix (where & how)

`WebDavSyncService.kt` — in `downloadLatestEncryptedVault`/`uploadEncryptedVault` resolve every `href` against the configured `config.serverUrl` origin (normalized scheme+host+port); reject any absolute URL whose host differs (re-resolve relative hrefs against the base path). Set `instanceFollowRedirects=false` on `createConnection` and strip `Authorization` on any cross-host redirect hop.


## Verification

- Unit test: PROPFIND responses offering an off-origin or private-IP href are rejected; relative hrefs resolve under the configured origin; no Basic header is ever attached to a non-configured host. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-NET-01 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-40/REPORT.md` committed: what changed (file:line), the
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
