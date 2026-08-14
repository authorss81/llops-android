# Phase 57: B1-PLAT-1 - Release APK is signed with the Android debug keystore... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-PLAT-1, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-PLAT-1` (MEDIUM)
- **Area:** Batch 1 - Android platform surface
- **Evidence:** `app/build.gradle.kts:28-54` (releaseConfig falls back to `${rootDir}/debug.keystore` decoded from `debug.keystore.base64`, password `android`, alias `androiddebugkey`), `app/build.gradle.kts:63-77` (release buildType uses the debug signingConfig when no release keystore exists), `docs/RELEASE.md`
- **Exploit scenario:** CI/local `gradle assembleRelease` without `KEYSTORE_FILE` produces a release APK signed with the public well-known debug key. Anyone who obtains that keystore can sign a malicious update that the platform accepts with no signature warning; Android also refuses debug-signed releases for distribution, so provenance is unverifiable.

## The fix (where & how)

`app/build.gradle.kts:28-54,63-77` - FAIL the release build when `KEYSTORE_FILE`/release credentials are unset instead of silently falling back to a debug key; never decode a keystore from an in-repo base64 blob; remove the `debug.keystore.base64` fallback. Update `docs/RELEASE.md` to state that debug-fallback builds must never be distributed.


## Verification

- Manual verification: `KEYSTORE_FILE` unset => `gradle assembleRelease` fails loudly (document the exact failure output in REPORT.md, do NOT run it in CI that lacks a keystore); with a real keystore env it builds. Debug build `gradle assembleDebug` unaffected. Do NOT edit `.github/workflows/`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-PLAT-1 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-57/REPORT.md` committed: what changed (file:line), the
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
