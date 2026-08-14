# Phase 109: B1-NET-08 - WebDAV credential store: keystore key not bound to any... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-NET-08, INFO) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-NET-08` (INFO)
- **Area:** Batch 1 - Data-in-transit & network
- **Evidence:** `WebDavCredentialStore.kt:49-61` (`KeyGenParameterSpec` without `setUserAuthenticationRequired`), `WebDavCredentialStore.kt:74-94` (save() catches all exceptions and returns silently - a failed write leaves previous credentials while the UI believes they were saved), `:100-127`
- **Exploit scenario:** Any code in the app process (including a downloaded plugin that obtains the facade) or a device thief running the app on an unlocked device can decrypt the stored WebDAV password with no biometric/pin challenge - the key is non-extractable but usable whenever the process runs. (Informational: at-rest encryption holds; only the auth gate is missing.) The silent-failure path also keeps stale credentials.

## The fix (where & how)

`WebDavCredentialStore.kt:49-61,74-94,100-127` - optionally add `setUserAuthenticationRequired(true)` (+ `setInvalidatedByBiometricEnrollment(true)`) when the user opts into biometric unlock; AT MINIMUM surface save failures to the UI so stale credentials are not silently kept.


## Verification

- Unit test: a simulated keystore write failure propagates to the caller/UI instead of being swallowed. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-NET-08 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-109/REPORT.md` committed: what changed (file:line), the
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
