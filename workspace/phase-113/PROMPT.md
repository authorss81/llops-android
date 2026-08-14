# Phase 113: B2-CRYPTO-07 - No Unicode normalization for master/backup passwords -... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-CRYPTO-07, INFO) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-CRYPTO-07` (INFO)
- **Area:** Batch 2 - Crypto side-channels & edge cases
- **Evidence:** `EncryptionService.deriveKey` feeds `PBEKeySpec(password.toCharArray(), ...)` (`EncryptionService.kt:31-35`) with no Normalizer; all six call sites pass the raw String (`NoteflowViewModel.kt:1789,1825,1885,1927`; `ImportExportService.kt:1183,1276,1321,1370`); zero `Normalizer`/`normalize()` usages on passwords in app/src/main
- **Exploit scenario:** No path asymmetry exists (one derivation path, so two different byte sequences can never be silently accepted), but a password typed once as NFC `é` (U+00E9) permanently fails when the user later types NFD `e`+U+0301 on another keyboard/IME - silently locking the vault and any password-protected backups with no diagnostic. `length` is code-unit, not grapheme, based.

## The fix (where & how)

`EncryptionService.kt:31-35` and every call site in `NoteflowViewModel.kt:1789,1825,1885,1927` + `ImportExportService.kt:1183,1276,1321,1370` - normalize to NFKC (and document it) at `setMasterPassword`/`changeMasterPassword` AND at every verify/decrypt call site; cap password length at a sane maximum and measure it in graphemes.


## Verification

- Unit test: an NFC password set and the equivalent NFD password typed at verify both unlock; the derive path is single and documented. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-CRYPTO-07 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-113/REPORT.md` committed: what changed (file:line), the
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
