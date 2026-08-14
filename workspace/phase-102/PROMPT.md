# Phase 102: B2-CRYPTO-01 - Tamper HMAC is compared with non-constant-time... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-CRYPTO-01, LOW) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-CRYPTO-01` (LOW)
- **Area:** Batch 2 - Crypto side-channels & edge cases
- **Evidence:** `DatabaseSecurityHelper.kt:153` (`return stored == current` on hex Strings - Kotlin `==` is `String.equals` with a first-mismatch early exit), while the codebase already ships the correct primitive via `PinnedCertHash.kt:54-58` (`MessageDigest.isEqual`)
- **Exploit scenario:** An attacker who can flip bytes of `noteflow.sqlite` and observe the tamper-banner latency can recover the stored 64-char checksum one nibble at a time via the early-exit comparison. The HMAC key is non-extractable so the recovered value alone can't forge, but it is a textbook unsafe-MAC-compare (CWE-650) gap - trivially fixable.

## The fix (where & how)

`DatabaseSecurityHelper.kt:153` - compare with `MessageDigest.isEqual(stored.toByteArray(), current.toByteArray())` (or fold both through the `PinnedCertHash`-style constant-time bytes comparison); consider `==`/`equals` banned for ANY HMAC/tag/pin comparison in the codebase (audit all crypto comparisons in the same PR).


## Verification

- Unit test: the compare path is constant-time via MessageDigest.isEqual (sanitize/review-level check); existing integrity-check tests stay green. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-CRYPTO-01 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-102/REPORT.md` committed: what changed (file:line), the
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
