# Phase 75: B2-DEPS-03 - No Gradle dependency verification: online resolution... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-DEPS-03, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-DEPS-03` (MEDIUM)
- **Area:** Batch 2 - Dependencies / CVE / supply chain
- **Evidence:** `gradle/` contains only `libs.versions.toml` (no `verification-metadata.xml`), `settings.gradle.kts:14-20` (dependencyResolutionManagement -> unrestricted repos; the google content-filter exists only in pluginManagement `:3-9`), CI provisions Gradle 8.13 via setup-gradle with no `distributionSha256Sum`, and `llops.yml:144` does `curl -fsSL https://opencode.ai/install | bash` with no pinned checksum
- **Exploit scenario:** Without a verification lockfile, Gradle accepts whatever the repositories publish at each build - a compromised/MITM'd artifact (or poisoned CI cache) compiles into the signed APK with no attestation, including ksp/AGP/Kotlin compiler plugins. Defense-in-depth gap for an app whose model is 'pinned, signed, hash-verified artifacts'.

## The fix (where & how)

REPO-LOCAL ONLY (Do NOT edit `.github/workflows/`): `settings.gradle.kts:14-20` - enable `dependencyVerification { verify = ... }` and generate + commit `gradle/verification-metadata.xml` (start with `--write-verification-metadata sha256`); add content filters to `dependencyResolutionManagement` mirroring the pluginManagement ones. Document the CI-side distributionSha256Sum + opencode-install pin as EXTERNAL notes in the phase REPORT.md (a workflow edit is out of scope and prohibited by AGENTS.md).


## Verification

- `gradle assembleDebug` still resolves (now through the verification metadata); `gradle testDebugUnitTest` passes. NOTE: generation may need a one-time network resolve - do it in the phase and commit the generated metadata. Do NOT edit `.github/workflows/`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-DEPS-03 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-75/REPORT.md` committed: what changed (file:line), the
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
