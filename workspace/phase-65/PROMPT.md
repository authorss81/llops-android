# Phase 65: B1-CRYPTO-07 - Biometric DEK key is only biometric-gated on API 30+;... [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-CRYPTO-07, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-CRYPTO-07` (MEDIUM)
- **Area:** Batch 1 - Cryptography & key management
- **Evidence:** `SecurityService.kt:38-45` (`setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` applied ONLY when `Build.VERSION.SDK_INT >= R`; on API 26-29 the key uses bare `setUserAuthenticationRequired(true)` - a PIN/pattern/password satisfies it), `BiometricAuthHelper.kt:11-17` (checks only strong-biometric *availability*, not what the key requires), `SecurityService.kt:58-66` (creates the `_auth` key on demand)
- **Exploit scenario:** On API 26-29 with biometrics enabled, a 4-6 digit screen PIN (unlimited attempts at keystore level) can authorize the unwrap and recover the DEK - the UI claims biometric-only protection but the device credential satisfies the key.

## The fix (where & how)

`SecurityService.kt:38-45,58-66` - store an explicit marker of the API level at key creation; refuse to enable biometric lock (or fall back to a clear warning + password-only) when the platform cannot create a biometric-STRONG-bound key; use `AUTH_BIOMETRIC_STRONG` in `setUserAuthenticationParameters` unconditionally once a minimum-SDK bump is feasible.


## Verification

- Unit test: requesting biometric lock on a simulated API 26-29 environment is refused or downgraded with a warning; API 30+ always binds AUTH_BIOMETRIC_STRONG. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-CRYPTO-07 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-65/REPORT.md` committed: what changed (file:line), the
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
