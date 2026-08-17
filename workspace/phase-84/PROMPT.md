# Phase 84: B2-CRYPTO-04 - Backup password (6-char minimum, no complexity) KDF... [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-CRYPTO-04, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-CRYPTO-04` (MEDIUM)
- **Area:** Batch 2 - Crypto side-channels & edge cases
- **Evidence:** `ImportExportService.kt:1181` (require `backupPassword.length >= 6` - length only), `ImportExportService.kt:1182-1198` (writes `NFLB2|16B salt|12B payloadIv|wrappedDek(61B)` as a cleartext-readable prefix; wrapped DEK protected ONLY by the password-derived KEK), copied to public `/Download` at `HomeScreen.kt:1196-1199` and WebDAV at `WebDavSyncService.kt:202`; offline decryptable at `ImportExportService.kt:1276-1283`
- **Exploit scenario:** The v2 backup is designed to travel. Anyone with storage permission/USB/MTP reading /Download, or the WebDAV server itself, obtains salt+wrappedDek+payloadIv plus the encrypted vault zip. A 6-7 char backup password is brute-forceable offline with GPU/FPGA PBKDF2 rigs in hours-days; success unwraps the DEK and decrypts the whole vault - no lockout, no device.

## The fix (where & how)

`ImportExportService.kt:1181-1198` - raise the backup-password minimum and add a complexity/entropy check; do NOT write salt/IV/wrappedDEK as raw prefix bytes - use a format that derives the KEK with a DIFFERENT KDF domain or split the DEK-wrapping key (store a random half only inside the payload); `ImportExportService.kt:1276-1283`/verify path updated accordingly; warn loudly that backups in Downloads/cloud are as weak as the backup password.


## Verification

- Unit tests: weak backup passwords are rejected; the exported header no longer allows extracting the wrapped DEK + salt for an offline brute force without the payload-derived key split. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-CRYPTO-04 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-84/REPORT.md` committed: what changed (file:line), the
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
