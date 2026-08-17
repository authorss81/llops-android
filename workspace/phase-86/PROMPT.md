# Phase 86: B1-NET-07 - WebDAV download: no size cap, 'latest' chosen by XML... [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-NET-07, LOW) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-NET-07` (LOW)
- **Area:** Batch 1 - Data-in-transit & network
- **Evidence:** `WebDavSyncService.kt:263-274` (`matches.last()` = last href in XML document order, not newest), `WebDavSyncService.kt:276-284` (streams downloadConn.inputStream to targetLocalFile with no size limit), `WebDavSyncService.kt:170-177,202-203` (remoteFolderName interpolated into the URL path without percent-encoding)
- **Exploit scenario:** A malicious WebDAV server streams an unbounded response -> disk-exhaustion DoS (webdav_download_import.nfb); non-chronological hrefs -> 'Download & Restore' silently restores an OLDER backup (rollback); a folder name like `../../Other` hits unintended server paths.

## The fix (where & how)

`WebDavSyncService.kt:263-284,170-177,202-203` - cap the download at a fixed max and abort beyond it; parse the timestamp from the filename and pick the maximum (not XML order); URL-encode `remoteFolderName` as a single path segment and reject `.`/`..`/control characters.


## Verification

- Unit test: a listing with out-of-order hrefs picks the newest by timestamp; an oversized response aborts at the cap; a traversal folder name is rejected/encoded. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-NET-07 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-86/REPORT.md` committed: what changed (file:line), the
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
