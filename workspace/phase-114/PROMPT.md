# Phase 114: B2-CRYPTO-08 - RNG hygiene audit: fresh SecureRandom per IV/salt/DEK... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-CRYPTO-08, INFO) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-CRYPTO-08` (INFO)
- **Area:** Batch 2 - Crypto side-channels & edge cases
- **Evidence:** Every IV/salt/DEK comes from a fresh `SecureRandom()` (`EncryptionService.kt:24-29,37-42,61-62`; backup payload IV `ImportExportService.kt:1186-1187`); re-saving always re-encrypts with a brand-new random IV (`NoteRepository.kt:356,359,552,560`) so there is NO IV/nonce reuse; the only seeded `Random` instances are cosmetic (brush texture, confetti). Gap: no `SecureRandom.getInstance`/provider pin anywhere
- **Exploit scenario:** No demonstrated vulnerability - this is a positive verification plus a hardening note. A 96-bit random IV has a collision probability ~= 2^-48 over 2^48 encryptions (beyond this app's scale) and per-call `new SecureRandom()` is the correct pattern; only a hostile platform provider (out of app control) could weaken it.

## The fix (where & how)

(Optional hardening) centralize IV/salt generation through ONE helper (e.g. `EncryptionService.newIv()/newSalt()`) so a future provider pin is a single-point change; optionally use `SecureRandom.getInstanceStrong()` once and share it; keep the guaranteed fresh-IV-per-encryption invariant (already held).


## Verification

- Unit test: the centralized helper always returns fresh, correctly-sized IV/salt; existing encryption tests stay green. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-CRYPTO-08 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-114/REPORT.md` committed: what changed (file:line), the
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
