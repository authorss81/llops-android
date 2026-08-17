# Phase 45: B1-CRYPTO-02 - Master password is bypassable: a... [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-CRYPTO-02, HIGH) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-CRYPTO-02` (HIGH)
- **Area:** Batch 1 - Cryptography & key management
- **Evidence:** `NoteflowDatabase.kt:335-343` (factory falls back to `security.getOrCreateDek()`), `SecurityService.kt:134-144` (`getOrCreateDek` mints a DEK and `storeDek(dek, authRequired=false)`), `SecurityService.kt:21-50` (`getOrCreateKey(false)` -> `setUserAuthenticationRequired(false)`), `NoteflowViewModel.kt:1785-1797` (`setMasterPassword` re-uses that non-auth DEK but NEVER calls `security.clearDek()`)
- **Exploit scenario:** On first open a random DEK is wrapped under a non-auth AndroidKeyStore key and stored in plaintext prefs. Setting a master password only adds a second wrapping of the SAME DEK; the unauthenticated copy is left in place, so a root/forensic attacker or an in-process plugin recovers the DEK with no credential, no biometric, no lockout. The '5-fail lockout' is theater for this access class.

## The fix (where & how)

`NoteflowViewModel.kt:1785-1797` (`setMasterPassword`) and on every password unlock. Call `SecurityService.clearDek()` so the only at-rest wrapping of the DEK is under the password-derived KEK. Re-persist the device copy ONLY when the user explicitly enables biometrics, and then with `authRequired = true` (biometric-gated key).


## Verification

- Unit test: after `setMasterPassword`, the `noteflow_sec_dek` pref is absent (or contains no decryptable non-auth blob) and `readDek()` without a password returns null / fails closed. Existing password set/verify/change tests stay green. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-CRYPTO-02 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-45/REPORT.md` committed: what changed (file:line), the
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
