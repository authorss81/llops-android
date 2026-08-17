# Phase 98: B2-DOS-08 - WebDAV PROPFIND listing is read into memory via... [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-DOS-08, LOW) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-DOS-08` (LOW)
- **Area:** Batch 2 - Resource-exhaustion / DoS
- **Evidence:** `WebDavSyncService.kt:250-259` (`listConn.inputStream.bufferedReader().use { it.readText() }` at :259 - full response buffered with no boundary; the subsequent regex over the whole string). Distinct from B1-NET-07's .nfb download cap
- **Exploit scenario:** A malicious/misconfigured WebDAV server answers the Depth-1 PROPFIND with a multi-GB XML document (or endless drip - readText() waits for EOF); the app buffers it all for the regex -> OOM on sync. Low likelihood (hostile server the user already configures) but trivially triggered.

## The fix (where & how)

`WebDavSyncService.kt:250-259` - read the response into a capped buffer (e.g. 4 MB hard limit) and fail the sync listing if exceeded; process hrefs incrementally rather than buffering the whole document.


## Verification

- Unit test: a PROPFIND response over the cap fails the listing without full buffering. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-DOS-08 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-98/REPORT.md` committed: what changed (file:line), the
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
