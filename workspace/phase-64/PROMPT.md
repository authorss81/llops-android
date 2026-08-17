# Phase 64: B1-CRYPTO-05 - getOrCreateDek silently mints a brand-new DEK when the... [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-CRYPTO-05, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-CRYPTO-05` (MEDIUM)
- **Area:** Batch 1 - Cryptography & key management
- **Evidence:** `SecurityService.kt:134-144` (readDek returns null on ANY failure incl. keystore key loss -> generateDek() -> storeDek OVERWRITES the pref), `SecurityService.kt:104-106` (storeDek swallows every exception with no signal), `NoteflowDatabase.kt:335-343` (factory then uses the brand-new DEK as the SQLCipher passphrase)
- **Exploit scenario:** AndroidKeyStore aliases do not survive app-data restores/ROM migrations/keystore resets on some OEMs. If prefs survive but the keystore key does not, `readDek()`->null, a fresh DEK is minted, and the next DB open fails against the still-encrypted vault -> the real vault is quarantined as `*.corrupt-*` and genuinely survivable data is permanently unrecoverable, with no diagnostic distinguishing 'key lost' from 'data corrupt'.

## The fix (where & how)

`SecurityService.kt:134-144,104-106` - distinguish 'no blob stored' from 'blob present but not decryptable' (return a sealed result / throw a typed `KeystoreKeyLostException`); on key loss go to the explicit recovery screen (with the restore-from-backup path) instead of silently minting a new key; persist a non-secret marker of which keystore alias/version wrapped the current blob.


## Verification

- Unit tests: readDek with a missing vs corrupt blob reports differently; a deleted keystore key routes to recovery instead of re-key; getOrCreateDek never overwrites an existing-but-undecryptable blob. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-CRYPTO-05 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-64/REPORT.md` committed: what changed (file:line), the
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
