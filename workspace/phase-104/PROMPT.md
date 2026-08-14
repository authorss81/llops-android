# Phase 104: B2-CRYPTO-03 - Backup v2 payload is NOT bound to its own header by... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-CRYPTO-03, LOW) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-CRYPTO-03` (LOW)
- **Area:** Batch 2 - Crypto side-channels & edge cases
- **Evidence:** `ImportExportService.kt:1185` (wraps the DEK with `EncryptionService.encrypt(key, kek)` which authenticates `FIELD_AAD`), the SAME KEK then encrypts the whole zip payload at `:1188-1190` with a bare `cipher.init(...)` and NO `updateAAD`; header `magic|salt|payloadIv|wrappedDek` written at `:1193-1198`
- **Exploit scenario:** Nothing binds the ciphertext to its own salt/IV/wrapped-DEK header, so a crafted file can splice the header of one legitimate export onto the payload of another; with a reused salt+IV pair the wrapped-DEK and payload GCM uses share a key with zero domain separation. No plaintext recovery demonstrated (IVs random), so this is an authenticated-format integrity/hygiene gap (false-negative 'Incorrect backup password').

## The fix (where & how)

`ImportExportService.kt:1185-1198` - bind the header into the payload GCM: `cipher.updateAAD(rawBytes.copyOfRange(0, headerSize))` (or a canonicalized `magic|salt|payloadIv` buffer) before `doFinal`; give the two KEK uses distinct AAD constants ('backup/dek-wrap' vs 'backup/payload') so cross-use is structurally impossible.


## Verification

- Unit test: splicing a header onto another export's payload fails the tag; the two KEK uses have distinct AAD domains. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-CRYPTO-03 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-104/REPORT.md` committed: what changed (file:line), the
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
