# Phase 268 — Build: verification + R8 CI + cache + pins + signing

## Goal
Turn supply-chain theater into real gates; prove release on CI.

## Evidence
- CRITICAL `gradle.properties:23 verification=off` → 1470-line `verification-metadata.xml` dead. CRITICAL `verification-metadata.xml:4-5 verify-metadata/signatures=false` + blanket `trusted-artifacts` (databinding/tools/kotlin). HIGH 300+ `A key couldn't be downloaded`/`not signed` Generated-by-Gradle hashes (attacker replaces artifact+hash undetected).
- HIGH `settings.gradle.kts:13 jitpack.io` unpinned/unauthenticated, no `content{}` filter (dependency confusion; plugin seed metadata loads from this graph).
- HIGH R8 fullMode+shrinkResources never proven on CI (`android.yml:28` skips llops-bot; `build.gradle.kts:232-236` admits zero signed fullMode runs) → ArtProfile crash could resurface. Fix: PR/push `assembleRelease`-mapping job (unsigned OK for mapping) + keep keystore gate.
- HIGH CI bypasses wrapper (`gradle-version:8.13` hardcoded vs wrapper SHA `20f1b11...`): pass `distribution-sha256-sum` or use `./gradlew`; NDK/cmake re-downloaded per llops job (no cache); `30min` timeout tight.
- MEDIUM floating `actions/*@v7/v5` (tag-reuse) → pin SHA per `docs/CI_PINNING.md`; `startParameter.taskNames` splits gate breaks config-cache; `paths:` filter skips `baselineprofile/**`/`proguard-rules.pro`/workflows; V3-only signing (pin V1/V2/V4 explicitly); `VERSION_CODE` fallback `2` (Play rejects) → require env in release job; keystore B64 decode unchecked (0-byte file passes); `rootProject.file()` absolute-path redundancy.

## Files
- `gradle.properties`, `gradle/verification-metadata.xml`, `settings.gradle.kts`, `app/build.gradle.kts`, `.github/workflows/android.yml` (CI-only proof job; keep llops.yml retrigger intact)

## Tests
- `Phase268BuildTest`: verification on + metadata true (source pins); wrapper SHA used in CI (workflow pin); release mapping produced (CI artifact check).

## Constraints
- No Room schema change / migration
- No new dependencies (pure Kotlin/Compose only)
- No `.github/workflows/` edits
- Follow AGENTS.md hard rules (allowBackup=false stays, no plaintext rows, fail closed)
- `verification-metadata.xml` untouched

## DoD
- `gradle :app:assembleDebug` green
- `gradle :app:testDebugUnitTest` green (all new + existing, 0 failures)
- `gradle :app:lintDebug` 0 errors
- `workspace/phase-NNN/REPORT.md` with file:line evidence table (claim / reality / status / evidence)
