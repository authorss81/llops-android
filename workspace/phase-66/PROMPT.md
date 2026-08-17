# Phase 66: B1-CRYPTO-08 - Artifact signer pin binds only ONE entry's cert... [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-CRYPTO-08, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-CRYPTO-08` (MEDIUM)
- **Area:** Batch 1 - Cryptography & key management
- **Evidence:** `ArtifactSignatureVerifier.kt:89-105` (findSignerCertificate iterates entries in JarFile order, takes `certs.firstOrNull()` of the LAST signed entry; entries outside manifest `Name:` sections are skipped), `ArtifactSignatureVerifier.kt:100-102` (no assertion that every loaded entry is signed by the pinned cert, no chain build, no `checkValidity()`, no key-usage check)
- **Exploit scenario:** The pin check only proves 'at least one entry was signed by the pinned cert' - a JAR signed by the genuine cert on one benign entry and the attacker key on `classes.dex` passes if iteration ends on the genuine entry. Today neutralized by the whole-file SHA-256 pin, this becomes a real bypass the moment the sha256 trust is perturbed (B1-CRYPTO-01). An expired/revoked pinned cert is also silently accepted.

## The fix (where & how)

`ArtifactSignatureVerifier.kt:89-105` - require the FULL signer certificate set of every non-META-INF entry to be exactly the one pinned cert (reject any multi-signer or unsigned entry in verified jars); validate cert validity period and key usage for signature; fail hard if the verified signer set is empty instead of falling back to 'the last entry seen'.


## Verification

- Unit tests: a jar with two signers/mixed unsigned entries fails verification; an expired pinned cert fails; a single-pinned-signer jar passes. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-CRYPTO-08 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-66/REPORT.md` committed: what changed (file:line), the
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
