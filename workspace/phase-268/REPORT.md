# Phase 268 — Build: verification + R8 CI + cache + pins + signing — REPORT

## Scope note (read first)

Two of the PROMPT's five file targets are **forbidden by this phase's own
constraints**: `gradle/verification-metadata.xml` ("untouched") and
`.github/workflows/` ("no edits" — workflow edits need user approval per
AGENTS.md / `docs/CI_PINNING.md` §5). So this phase does what is implementable
in the allowed files (`app/build.gradle.kts`, plus a new source-pinning test),
**verifies every PROMPT evidence claim against HEAD instead of trusting it**,
and records the workflow-side items as explicit pending-approval gaps rather
than theater. Headline result: **four of the seven evidence bullets are stale
(already fixed at HEAD); two shipped here; the rest need workflow edits.**

## Claim / reality / status / evidence

| # | PROMPT claim | Reality at HEAD | Status | Evidence |
|---|--------------|-----------------|--------|----------|
| 1 | CRITICAL `gradle.properties:23 verification=off` → 1470-line metadata dead | **STALE — no such line.** `gradle.properties` (40 lines) contains no `verification=` / `dependency.verification` override at all | PINNED, no change needed | `gradle.properties` (full read); `Phase268BuildTest.gradle properties carry no verification kill-switch` |
| 2 | CRITICAL metadata `:4-5 verify-metadata/signatures=false` + blanket trusted-artifacts | **STALE — both `true`.** `<verify-metadata>true</verify-metadata>` `:4`, `<verify-signatures>true</verify-signatures>` `:5`; trusted-artifacts = exactly 3 signature-less build-tool groups (databinding, tools.build, kotlin — Gradle/AGP/Kotlin-generated, no `.asc` published) | PINNED, file untouched per constraint | `gradle/verification-metadata.xml:4-10`; pins `dependency verification stays switched on`, `trusted artifacts stay limited…` |
| 3 | HIGH 300+ undownloadable-key / unsigned Generated-by-Gradle hashes | **STALE/OVERSTATED.** 620 locked components, 1104 `sha256` entries (checksum covers every component), real publisher `<trusted-keys>` throughout; `<ignored-keys>` = **17** (not 300+), all `Key couldn't be downloaded…` → checksum-only fallback, still sha256-gated; zero `Generated-by-Gradle` entries | PINNED (count 17 — note: naive `grep -c` says 18 because it also matches the `<ignored-keys>` wrapper line) | `verification-metadata.xml:11-29`; pin `ignored keys stay the reviewed set…` (asserts 17 + reason + sha256≥components) |
| 4 | HIGH `settings.gradle.kts:13 jitpack.io` unpinned, no content filter | **STALE — jitpack is gone.** Zero `jitpack` hits in `*.kts`/`*.toml`/`*.properties`/`*.yml` (excl. workspace); google()+mavenCentral() both carry `content{}` allow-lists | PINNED | repo-wide grep (empty); pin `no jitpack repository…` (settings + properties + toml) |
| 5a | HIGH R8 fullMode+shrinkResources never proven | **PROVEN HERE (locally, keystore-less).** `:app:minifyReleaseWithR8` — outside `RELEASE_SIGNING_TASK_NAMES`, so B1-PLAT-1 doesn't block it — exercises R8 fullMode + shrinkResources end-to-end | DONE | `BUILD SUCCESSFUL` 2026-09-12; `app/build/outputs/mapping/release/mapping.txt` 89,934,018 B, sha256 `e524c397…7d7` (+ `configuration.txt`, `usage.txt`, `seeds.txt`); pin `release build keeps the R8…flags` |
| 5b | PR/push assembleRelease-mapping job + keep keystore gate | **PENDING USER APPROVAL** (workflow edit). `release.yml` already runs signed `assembleRelease` on `v*` tags, but no unsigned mapping-proof job and no `mapping.txt` artifact upload exist | GAP (no `.github/workflows/` edits this phase) | `.github/workflows/release.yml:42-62` (APK uploads only, no mapping); `android.yml:28` llops-bot skip intact |
| 6a | CI bypasses wrapper (`gradle-version:8.13` vs wrapper SHA `20f1b11…`) | **PARTLY PINNED.** Full fix (`distribution-sha256-sum` / `./gradlew`) needs a workflow edit → pending. Pinned instead: wrapper SHA present + every workflow's `gradle-version` == wrapper version (8.13), so CI cannot silently drift off the wrapper | PINNED (parity), GAP (sha handoff) | `gradle-wrapper.properties:11-12`; `android.yml:43`, `release.yml:24`, `llops.yml` (×2) all `"8.13"`; pin `wrapper distribution stays SHA-pinned…` |
| 6b | NDK/cmake re-downloaded per llops job (no cache); 30min timeout tight | **PENDING USER APPROVAL** (workflow-only) | GAP | `android.yml:30,45-46` (timeout 30, `rm -rf .cxx` each run, no NDK/cache step) |
| 7a | MEDIUM floating `actions/*@v7/v5` → pin SHA per CI_PINNING.md | **PENDING USER APPROVAL** — `docs/CI_PINNING.md` SHAs predate current majors (`@v7`/`@v6` in tree vs `@v4`/`@v3` in the doc); re-resolve at edit time, don't apply stale SHAs | GAP | `android.yml:32,35,41`, `release.yml`, `llops.yml`; `docs/CI_PINNING.md:75-88` drift notes |
| 7b | `startParameter.taskNames` splits gate breaks config-cache | **ACCEPTED, untouched.** The only CC-safe replacement is a workflow-passed property (`-Pinkflow.*` / env) → needs a workflow edit. Debug/release split behavior verified unchanged per `gradle.properties:17-19` | DEFERRED (same approval bundle) | `app/build.gradle.kts:192` (activation expr unchanged) |
| 7c | `paths:` filter skips `baselineprofile/**` / proguard / workflows | **PENDING USER APPROVAL** (workflow-only). `gradle/**` does not cover `baselineprofile/`; a profile-only change skips CI | GAP | `android.yml:6-11` paths list |
| 7d | V3-only signing; pin V1/V2/V4 explicitly | **DONE HERE.** All four schemes pinned: V1+V2+V3 ON, V4 explicitly OFF (adb-incremental only, not for store/sideload); stale "v1/v4 untouched" comment rewritten | DONE | `app/build.gradle.kts:67-85`; pin `all four APK signature schemes…` |
| 7e | `VERSION_CODE` fallback `2` (Play rejects) → require env in release job | **NOT gated — deliberately.** `release.yml` sets no `VERSION_CODE`, so a Gradle-side hard gate would BREAK tag releases; the fix must export per-release codes in the workflow → pending approval. Debug keeps `?: 2` | GAP (workflow must supply `VERSION_CODE` first) | `app/build.gradle.kts:47` (unchanged); `release.yml:42-48` (no VERSION_CODE env) |
| 7f | Keystore B64 decode unchecked (0-byte passes) | **DONE HERE (Gradle side).** Config demands `isFile && length() > 0` (`:101-105`); `whenReady` backstop throws on 0-byte storeFile (`:327-338`). The shell-side `base64 -d` size check itself needs a workflow edit | DONE (Gradle), GAP (shell one-liner) | `app/build.gradle.kts:99-109,327-338`; pin `release keystore gate refuses empty…` |
| 7g | `rootProject.file()` absolute-path redundancy | **DONE.** Single resolution: absolute KEYSTORE_FILE used as-is, relative stays repo-root-relative (`:99-101`) | DONE | `app/build.gradle.kts:99-101` |

## What changed (2 files)

1. `app/build.gradle.kts`
   - `:67-85` — explicit `enableV1Signing = true` / `enableV2Signing = true` /
     `enableV3Signing = true` / `enableV4Signing = false` + rationale (rotation,
     pre-9 fallback, JAR-signature tooling, AGP-default immunity).
   - `:99-109` — keystore resolved once (absolute as-is, relative =
     repo-root), `isFile && length() > 0` required; `import java.io.File` (`:1`).
   - `:327-338` — execution-time 0-byte backstop in the B1-PLAT-1 `whenReady`
     gate (config-time refusal already above; AGP `validateSigningRelease`
     remains the final backstop).
   - Untouched by design: `versionCode ?: 2` (`:47`), splits activation
     (`:192`), `RELEASE_SIGNING_TASK_NAMES`, debug buildType (no signingConfig).
2. `app/src/test/.../Phase268BuildTest.kt` (new, 9 tests — see KDoc for the
   stale-evidence rationale and the workflow-gap pointer).

## Verification

- `gradle :app:testDebugUnitTest` — **3850 tests, 0 failures, 0 errors,
  0 skipped** (3841 pre-existing + 9 new; `Phase268BuildTest` 9/9 green in
  isolation; `B1Plat01ReleaseSigningTest`, `Phase146BuildIntegrityTest`,
  `B2Deps03DependencyVerificationTest` re-run green — the signing slice
  `substringAfter("create(\"releaseConfig\")")` is brace-safe: env reads still
  precede the first `}`).
- `gradle :app:assembleDebug` — green (`app-debug.apk` 78,411,070 B).
- `gradle :app:lintDebug` — **0 errors**.
- `gradle :app:minifyReleaseWithR8` (keystore-less R8 proof) — green, mapping
  above. No profile committed in-tree, so the guarded `compileArtProfile`
  disable behaved as designed (no ArtProfile crash surface).
- No schema change, no new deps, `verification-metadata.xml` untouched,
  `.github/workflows/` untouched, `allowBackup=false` intact, base-APK rule
  intact (zero dependency lines touched).

## Follow-up bundle (needs user approval — one workflow-edit PR)

Re-resolve action SHAs (current majors, not the doc's `@v4`/`@v3` SHAs) →
`distribution-sha256-sum` (or `./gradlew`) → unsigned `assembleRelease`-mapping
job + `mapping.txt` upload → `VERSION_CODE` from tag → widen `paths:` to
`baselineprofile/**` + `proguard-rules.pro` + workflows → NDK/cmake cache +
timeout review → keystore-decode size check → splits via workflow-passed
property. Until then the REPORT table above is the honest gate ledger.
