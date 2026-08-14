# Phase 91: B1-CRYPTO-06 - DatabaseSecurityHelper tamper check fails OPEN... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-CRYPTO-06, LOW) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-CRYPTO-06` (LOW)
- **Area:** Batch 1 - Cryptography & key management
- **Evidence:** `DatabaseSecurityHelper.kt:146-154` verifyDatabaseIntegrity: stored==null -> `updateStoredChecksum(context); return true` (silently re-baselines whatever is on disk); `computeDatabaseHmac` returns null on any error -> `?: return true` (line 152); checksum + state live in the same unencrypted pref file the attacker can edit
- **Exploit scenario:** An attacker who can delete the `db_hmac_checksum` pref (root or prefs tampering) gets the app to re-baseline against a current (possibly tampered/forged) file and report 'verified' - removing the last tripwire. Combined with an in-memory Frida hook this defeats tamper-evidence.

## The fix (where & how)

`DatabaseSecurityHelper.kt:146-154` - FAIL CLOSED: a missing or un-computable checksum should report 'cannot verify / possibly tampered' to the recovery UI rather than re-arm the baseline; keep the checksum pref write-only through the helper and never re-baseline from a file that arrives un-trusted.


## Verification

- Unit test: missing/undecryptable stored checksum yields a 'cannot verify' result, not `true`. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-CRYPTO-06 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-91/REPORT.md` committed: what changed (file:line), the
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
