# Phase 80: B2-DOS-04 - AppFacadeHost.httpGet buffering is unbounded during... [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-DOS-04, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-DOS-04` (MEDIUM)
- **Area:** Batch 2 - Resource-exhaustion / DoS
- **Evidence:** `AppFacadeHost.kt:52` (`instanceFollowRedirects = true`), `AppFacadeHost.kt:57-58` (pre-check uses `contentLengthLong`, -1 for chunked/unknown -> skipped), `AppFacadeHost.kt:61-66` (`stream.readBytes(); if (bytes.size > MAX) ...` - whole body already in heap). Contrast the correct streaming cap in `WebPageFetcher.kt:63-81` (`readCapped`)
- **Exploit scenario:** A plugin granted `httpGet` points at a slow-chunked endpoint: `readBytes()` accumulates output with zero cap enforcement - a single call pins hundreds of MB in heap and OOMs the process; redirect chains have no per-hop budget.

## The fix (where & how)

`AppFacadeHost.kt:52-66` - replace `readBytes()` with a bounded streaming loop enforcing `MAX_FACADE_GET_BYTES` DURING the read (abort mid-stream), and set `instanceFollowRedirects = false` with per-hop re-validation (mirror `WebPageFetcher.readCapped`).


## Verification

- Unit test: a slow-chunked stream over MAX_FACADE_GET_BYTES aborts mid-read without exceeding heap budget. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-DOS-04 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-80/REPORT.md` committed: what changed (file:line), the
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
