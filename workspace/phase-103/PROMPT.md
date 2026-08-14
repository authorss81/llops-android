# Phase 103: B2-CRYPTO-02 - Plugin artifact SHA-256 verification uses... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-CRYPTO-02, LOW) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-CRYPTO-02` (LOW)
- **Area:** Batch 2 - Crypto side-channels & edge cases
- **Evidence:** `ArtifactSignatureVerifier.kt:57` (`if (!sha256.equals(expectedSha256.trim(), ignoreCase = true))` - lexical early-exit compare), while the adjacent pin check correctly uses `PinnedCertHash.matches` -> `MessageDigest.isEqual` (:68-69). Positive controls verified: `PluginDigest.sha256Hex` streams the full file (:42-55) and `PinnedCertHash.parse` rejects non-32-byte digests (:44-49)
- **Exploit scenario:** An attacker iteratively corrupts a downloaded artifact one byte at a time, observes the rejection latency, and reconstructs the expected sha256 char-by-char. The expected digest is public today, so honest impact is a hardening gap - but any future scenario where the expected digest is sensitive (or this pattern is copied to a MAC compare) inherits the weakness.

## The fix (where & how)

`ArtifactSignatureVerifier.kt:57` - compare digests with `MessageDigest.isEqual` on the raw/hex bytes (drop `ignoreCase`; normalize case once at parse time); enforce a single constant-time comparison helper for ALL digest/pin checks (reuse `PinnedCertHash.matches`).


## Verification

- Unit test: digest comparison goes through the constant-time helper; case-insensitivity is normalized at parse, not compare. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-CRYPTO-02 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-103/REPORT.md` committed: what changed (file:line), the
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
