# Phase 112: B2-DEPS-02 - security-crypto 1.1.0-alpha06: unmaintained alpha of a... [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-DEPS-02, INFO) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-DEPS-02` (INFO)
- **Area:** Batch 2 - Dependencies / CVE / supply chain
- **Evidence:** `gradle/libs.versions.toml:12` (securityCrypto = "1.1.0-alpha06", lib at :56), `app/build.gradle.kts:142` (`implementation(libs.security.crypto)`); grep of app/src/main for androidx.security.crypto/EncryptedSharedPreferences/MasterKeys = ZERO usages. NOTE: CVE-2024-37150 is a Deno npm-registry bug, NOT an androidx.security CVE - do not cite it
- **Exploit scenario:** Not exploitable today (unused). Forward-looking risk: any future wiring of EncryptedSharedPreferences into this 3-year-old unmaintained alpha would reintroduce Tink keyset-manager failure classes (AEADBadTagException on backup-restore/key-loss) with zero future security maintenance.

## The fix (where & how)

`app/build.gradle.kts:142` + `gradle/libs.versions.toml:12,56` - delete the unused `implementation(libs.security.crypto)` line and its version/catalog entry (a dependency REMOVAL, no new deps). If encrypted prefs are ever needed later, use AndroidKeyStore + Tink directly - do not adopt the deprecated library.


## Verification

- `gradle assembleDebug` still builds after removal (compile-tree proves it was unused) + `gradle testDebugUnitTest`. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-DEPS-02 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-112/REPORT.md` committed: what changed (file:line), the
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
