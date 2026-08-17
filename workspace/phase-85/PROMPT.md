# Phase 85: B1-NET-06 - LocalSend: opening the Send dialog actively probes... [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-NET-06, LOW) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-NET-06` (LOW)
- **Area:** Batch 1 - Data-in-transit & network
- **Evidence:** `LocalSendSender.kt:195-218` (legacyHttpScan walks 1..254 of the active /24), `LocalSendSender.kt:230-258` (POST registers carrying senderInfo), `LocalSendSender.kt:73,75-84` (`alias = "InkFlow (Build.MODEL)"`, `deviceModel = Build.MODEL`), `LocalSendSender.kt:136-156` (repeated UDP broadcast announces), `LocalSendSendDialog.kt:73` (discover() runs immediately on open)
- **Exploit scenario:** Just opening the Send dialog makes the device sweep the whole subnet with HTTP POSTs on port 53317 and emit broadcast/multicast announces every ~1.1 s - any LAN host (or passive AP monitoring) detects the app's presence, exact device model, and local IP, without any user confirmation.

## The fix (where & how)

`LocalSendSender.kt:195-218,230-258,75-84,136-156` and `LocalSendSendDialog.kt:73` - require explicit user confirmation before ANY LAN traffic; gate the /24 sweep behind it (or drop the sweep and rely on UDP discovery); remove `Build.MODEL` from the announce (send only a user-set alias).


## Verification

- Unit test + manual: no LAN traffic occurs until the user confirms; announce no longer contains `Build.MODEL`. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-NET-06 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-85/REPORT.md` committed: what changed (file:line), the
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
