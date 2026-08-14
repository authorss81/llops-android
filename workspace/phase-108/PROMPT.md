# Phase 108: B2-CRYPTO-10 - Blank/empty plaintext fields are stored raw and... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-CRYPTO-10, LOW) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-CRYPTO-10` (LOW)
- **Area:** Batch 2 - Crypto side-channels & edge cases
- **Evidence:** `createPage` stores empty extractedText as raw `""` (`NoteRepository.kt:358-362`); `saveStrokesForPage` stores blank text/points unencrypted (`:551-563`); `isFieldEncrypted` returns true for ANY blank value (`:150-151`) so `reencryptPlaintextFields` (`:165-230`) never touches blank columns; all decrypt paths skip blanks (`:449,460,609,800-802`); short inputs <12 bytes throw IllegalArgumentException at `EncryptionService.kt:79` before any tag check
- **Exploit scenario:** An attacker who can zero a field's ciphertext column silently erases a note's title/body to '' - at the field layer indistinguishable from a legitimately-blank record (no tag for blanks) and displayed as an empty note rather than flagged tampered; empty plaintext values are asserted 'encrypted and fine' by any logic relying on `isFieldEncrypted`.

## The fix (where & how)

`NoteRepository.kt:150-151,165-230,358-362,551-563` - encrypt empty strings as a real AEAD payload (AES-GCM of empty plaintext is a valid 28-byte ciphertext) so blanks carry a tag; replace the `isBlank` 'encrypted?' probe with a structural format check (payload length >= 13 and version marker), never content-blankness; on any store, authenticate the full field row including its blank-ness.


## Verification

- Unit test: a blank field round-trips as an encrypted blob that fails decryption if the ciphertext column is zeroed; reencryptPlaintextFields now re-encrypts blank rows. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-CRYPTO-10 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-108/REPORT.md` committed: what changed (file:line), the
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
