# Phase 52: B1-NET-05 - HTTPS->HTTP redirect downgrades: default... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-NET-05, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-NET-05` (MEDIUM)
- **Area:** Batch 1 - Data-in-transit & network
- **Evidence:** `PluginManifestFetcher.kt:98` (`instanceFollowRedirects=true`), `HttpsPluginDownloadTransport.kt:62` (same), `WebDavSyncService.kt:138-155` (`createConnection` never disables redirects; `requireSecureUrl` evaluated once on the initial URL), `HttpsTitleFetcher.kt:37`, `DuckDuckGoClient.kt:139`, `WeatherClient.kt:80`, `DictionaryClient.kt:45`
- **Exploit scenario:** An `https://` server answering `307 Location: http://...` makes the app continue over plaintext - WebDAV PUT body over the LAN/ISP in cleartext (plus Basic header on `allowInsecureHttp` configs), or the plugin manifest arriving over cleartext (defeating the HTTPS gate B1-CRYPTO-01/B1-NET-03 rely on). The scheme guard runs before connection creation, not on the redirected connection.

## The fix (where & how)

`PluginManifestFetcher.kt:98`, `HttpsPluginDownloadTransport.kt:62`, `WebDavSyncService.kt:138-155`, `HttpsTitleFetcher.kt:37`, `DuckDuckGoClient.kt:139`, `WeatherClient.kt:80`, `DictionaryClient.kt:45` - set `instanceFollowRedirects=false` on ALL of these connections and implement manual redirect handling that re-runs `requireSecureUrl`/host checks (and the B1-NET-04 blocklist) on EVERY hop; reject any hop that is not `https` for TLS-required transports.


## Verification

- Unit test: an https->http 302/307 redirect is refused for each transport; only same-scheme (https) redirects proceed. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-NET-05 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-52/REPORT.md` committed: what changed (file:line), the
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
