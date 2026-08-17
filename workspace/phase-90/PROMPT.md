# Phase 90: B1-PLAT-8 - Master password minimum length of 6; on-device lockout... [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-PLAT-8, LOW) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-PLAT-8` (LOW)
- **Area:** Batch 1 - Android platform surface
- **Evidence:** `NoteflowViewModel.kt:1773-1774` (`MIN_PASSWORD_LENGTH = 6`), `:1778` (enforced), `:1883-1908` (PBKDF2-HMAC-SHA256 600k, 5 attempts then exponential lockout persisted in plain SharedPreferences, `SettingsManager.kt:58-63`)
- **Exploit scenario:** A physical/rooted attacker, or one with adb-backup-style data access, copies prefs + SQLCipher vault off-device and brute-forces the master password with GPUs. A 6-char short password collapses in minutes-hours; the 5-attempt lockout only throttles on-device attempts and gives false assurance (restoring to a rooted emulator defeats it entirely).

## The fix (where & how)

`NoteflowViewModel.kt:1773-1797,1883-1908` - enforce a stronger minimum (>=10 chars) and reject common/sequential/prefix-suffix patterns; document (in-code comment + `docs/RELEASE.md` note) that offline brute force is only mitigated by password entropy, not by the on-device lockout.


## Verification

- Unit tests: <10 char and common-pattern passwords are rejected at set/change. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-PLAT-8 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-90/REPORT.md` committed: what changed (file:line), the
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
