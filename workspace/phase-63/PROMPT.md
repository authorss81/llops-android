# Phase 63: B1-CRYPTO-04 - Weak-password policy + process-local-only lockout =>... [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-CRYPTO-04, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-CRYPTO-04` (MEDIUM)
- **Area:** Batch 1 - Cryptography & key management
- **Evidence:** `NoteflowViewModel.kt:1773` (`MIN_PASSWORD_LENGTH = 6`), no complexity/entropy check; `EncryptionService.kt:31-35` (only protection is 600k-iteration PBKDF2-HMAC-SHA256); `NoteflowViewModel.kt:1875-1913` (lockout counters are process memory + benign prefs, trivially reset; `isMasterPasswordValid` at :1920-1937 has NO attempt accounting)
- **Exploit scenario:** The salt + wrapped-DEK + SQLCipher DB sit on the normal data partition. An attacker who obtains a data copy cracks the wrapped DEK offline with GPU/FPGA PBKDF2 rigs; a 6-7 char lowercase/numeric password falls in hours-to-days. The on-device 5-fail lockout never fires because no attempt happens through the UI.

## The fix (where & how)

`NoteflowViewModel.kt:1773-1774,1778,1883-1913,1920-1937` - enforce a stronger minimum (length + diversity or an entropy check) at `setMasterPassword`/`changeMasterPassword`; document that lockout is UI-only and vault contents are only as strong as the password; consider TEE-bound attempt gating or Argon2id as a follow-up note (do not add deps without justification).


## Verification

- Unit tests: weak/sequential/short passwords are rejected at set/change; the rejected+accepted sets are covered by unit tests. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-CRYPTO-04 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-63/REPORT.md` committed: what changed (file:line), the
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
