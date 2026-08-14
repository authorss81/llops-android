# Phase 88: B1-DB-8 - Decrypt-failure fallbacks return RAW CIPHERTEXT as... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-DB-8, LOW) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-DB-8` (LOW)
- **Area:** Batch 1 - Data-at-rest & DB
- **Evidence:** `NoteRepository.getStrokesForPage` `NoteRepository.kt:449-457` (catches any decrypt exception and returns `rawText` - the ciphertext blob), `decryptPageIfNeeded` `NoteRepository.kt:810-828` (returns the page unchanged/encrypted on failure), same pattern for embeds `NoteRepository.kt:609-613` and versions `NoteRepository.kt:800-807`
- **Exploit scenario:** After a re-key, cross-device restore with a mismatched DEK, or partial DB manipulation, the app silently displays base64 AES-GCM garbage as if it were the real note title/text - misleading the reader and masking the underlying tamper/re-key problem the integrity checks are meant to catch.

## The fix (where & how)

`NoteRepository.kt:449-457,810-828,609-613,800-807` - on decrypt failure return an explicit error marker (e.g. 'Unreadable (decryption failed)') and surface a recovery/re-key promotion rather than raw ciphertext; treat persistent decryption failure as a corruption/restore event.


## Verification

- Unit test: a decrypt-failing row renders the error marker, not the raw blob. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-DB-8 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-88/REPORT.md` committed: what changed (file:line), the
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
