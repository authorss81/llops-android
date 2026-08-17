# Phase 51: B1-NET-04 - SSRF in Web Capture and Citation title-fetch: no host... [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-NET-04, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-NET-04` (MEDIUM)
- **Area:** Batch 1 - Data-in-transit & network
- **Evidence:** `WebPageFetchPolicy.kt:31-58` (validateUrl only checks scheme+host presence - `localhost`, `127.0.0.1`, `192.168.*`, `10.*`, `169.254.169.254` all pass), `WebPageFetcher.kt:21-61` (manual redirect loop re-checks only scheme at :25-28), `HttpsTitleFetcher.kt:32-48` (same)
- **Exploit scenario:** A malicious note/link or a public page answering `302 Location: http://169.254.169.254/...` makes the app fetch internal services (cloud metadata, localhost admin, LAN devices) and store the body into the vault or display it. On VPN/enterprise networks this reaches real internal services from the device.

## The fix (where & how)

`WebPageFetchPolicy.kt:31-58` - add a loopback/link-local/private/metadata blocklist (RFC1918, `127.0.0.0/8`, `169.254.0.0/16`, `::1`, `.local`) at `validateUrl` AND on every redirect hop in `WebPageFetcher.kt:21-61` and `HttpsTitleFetcher.kt:32-48` (re-parse the resolved Location and re-apply validation before connecting); consider HTTPS-only for capture; keep the 5-hop cap.


## Verification

- Unit tests: `validateUrl` rejects localhost/private/metadata/`::1`/`.local`; a redirect hop to a blocked host is refused. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-NET-04 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-51/REPORT.md` committed: what changed (file:line), the
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
