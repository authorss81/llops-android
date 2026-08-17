# Phase 62: B1-CRYPTO-03 - Salt and wrapped-DEK are written in two non-atomic... [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-CRYPTO-03, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-CRYPTO-03` (MEDIUM)
- **Area:** Batch 1 - Cryptography & key management
- **Evidence:** `NoteflowViewModel.kt:1794-1795` (`settings.masterPasswordSalt = ...` then `settings.masterPasswordWrappedDek = ...` as two independent SharedPreferences writes), `NoteflowViewModel.kt:1829-1830` (same in `changeMasterPassword`), with the SQLCipher DB already created/re-keyed under the new DEK before these prefs are committed
- **Exploit scenario:** A kill (low-memory, crash, battery pull) exactly between the two pref writes leaves e.g. new salt + old/missing wrapped DEK. Every subsequent verify hits AEADBadTag permanently, the H2 handler quarantines the DB as `*.corrupt-*`, and the user loses the entire vault from a single unlucky kill. No checksum detects the half-written pair.

## The fix (where & how)

`NoteflowViewModel.kt:1794-1795,1829-1830` - store salt + wrappedDEK (+ format) as ONE versioned blob in a single `commit()` (disk-sync-acknowledged), or write the new pair to a scratch pref key and atomically swap; validate a round-trip (decrypt the wrapped DEK) before reporting success.


## Verification

- Unit test: simulating a crash between the two writes (via an injected pref-store that fails on the second write) leaves the vault still unlockable or cleanly restorable, never bricked. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-CRYPTO-03 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-62/REPORT.md` committed: what changed (file:line), the
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
