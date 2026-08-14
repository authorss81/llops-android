# Phase 105: B2-CRYPTO-05 - EncryptionService.decrypt version-byte-guessing... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-CRYPTO-05, LOW) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-CRYPTO-05` (LOW)
- **Area:** Batch 2 - Crypto side-channels & edge cases
- **Evidence:** `EncryptionService.kt:85-93` (a payload whose first byte == PAYLOAD_VERSION and >=13 bytes is decrypted versioned first; ONLY an AEADBadTagException triggers the legacy path `decryptCore(...offset=0, withAad=false)` at :93), `:96-113` (a full AES-GCM doFinal per attempt)
- **Exploit scenario:** A co-located/forensic attacker (or one inducing a controlled decrypt through a plugin) can time field decrypts to classify each ciphertext row as versioned (2 decrypts, AAD-bound) vs legacy (1 decrypt, NO AAD) - identifying the unbound records (see B2-CRYPTO-09). The fallback also means a tag mismatch always gets one retry (malleable-retry behavior).

## The fix (where & how)

`EncryptionService.kt:85-93,96-113` - pick the format DETERMINISTICALLY (legacy formats are exactly 12-byte-IV + ciphertext with no version prefix; versioned are >=13 with byte 0 = 1 - distinguish by a committed length/format marker), so a tag failure never triggers a second decrypt on a guessed layout; drop legacy support / fail closed if feasible.


## Verification

- Unit test: correct versioned and legacy payloads both decrypt through a single chosen path; a tampered payload fails exactly once with no fallback retry. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-CRYPTO-05 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-105/REPORT.md` committed: what changed (file:line), the
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
