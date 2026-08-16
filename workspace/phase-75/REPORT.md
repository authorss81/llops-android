# Phase 75 — B2-DEPS-03 (MEDIUM): No Gradle dependency verification

**Status:** `FIXED` 2026-08-16 — REPORT for `workspace/phase-75`.

## Finding (docs/security-report.md B2-DEPS-03 block, row :872)

> Without a verification lockfile, Gradle accepts whatever the repositories
> publish at each build — a compromised/MITM'd artifact (or poisoned CI cache)
> compiles into the signed APK with no attestation, including ksp/AGP/Kotlin
> compiler plugins.

Evidence that was live: `gradle/` contained only `libs.versions.toml` (no
`verification-metadata.xml`), `dependencyResolutionManagement` in `settings.gradle.kts`
had the google() content-filter ONLY in `pluginManagement` (not mirrored), and CI
provisions Gradle 8.13 with no `distributionSha256Sum`.

### First attempt (b4177d7) — aborted

The first run tried to hand-write a `dependencyVerification { verify = "all" }`
block into `settings.gradle.kts`. That references a **settings DSL element that does
not exist in Gradle 8.13** — verified empirically:

- `gradle help` on the repo failed with `Unresolved reference: dependencyVerification`
  and `Unresolved reference: verify` at `settings.gradle.kts` lines 37–38;
- a minimal repro project (an empty `settings.gradle.kts` containing only
  `dependencyVerification {}`) reproduces the identical compilation failure;
- the Groovy `settings.gradle` variant fails with `Could not find method
  dependencyVerification()` on `DefaultSettings`;
- bytecode scan of the Gradle 8.13 distribution shows the verification DSL is
  file-driven (`gradle-core-api` exposes only the `DependencyVerificationMode`
  enum; `DependencyVerificationExtension`/`VerifyMetadataSettings` and a settings
  accessor are NOT present), and the Gradle 8.13 user guide documents verification
  as **automatically enabled by the presence of `gradle/verification-metadata.xml`**
  — no settings-level toggle exists.

The first commit (b4177d7) left `.no_work` + `.attempts` markers (the phase had no
valid work), but the broken `dependencyVerification` block was committed moments
later in 520d489 and marked the phase DONE **without compiling, without the lockfile,
without tests, and without REPORT.md** — a build-breaking false completion. Those
commit artifacts are what this report's diff corrects.

## Fix (repo-local only, committed)

Gradle 8.13's model is: dependency verification is **enabled by presence of
`gradle/verification-metadata.xml`**, in STRICT mode, for every resolved artifact
(project deps, metadata files, AND build plugins). No `dependencyVerification {}`
settings block exists to toggle it — and none should be invented, since any attempt
breaks settings compilation.

### 1. `settings.gradle.kts`
- **`gradle/verification-metadata.xml` generated + committed** (4,183 lines):
  bootstrapped offline-first with `gradle --write-verification-metadata sha256 help`,
  then incrementally completed with the real task set
  (`gradle --write-verification-metadata sha256 testDebugUnitTest assembleDebug`)
  so task-execution-time resolutions (AGP's platform-suffixed `aapt2-*-linux.jar`,
  lint/`uast`/`play-sdk-proto` artifacts) are pinned too. The committed file is the
  final artifact **before** adding the new unit test, so this phase's own new test
  code is not self-pinned. Verified: 519 `<component>` entries / 913 artifacts,
  covering `com.android.tools.build:*` (AGP, aapt2, lint), `org.jetbrains.kotlin:*`
  (Kotlin + compose compiler) and `com.google.devtools.ksp:*` — i.e. the build
  plugins named in the finding.
- **`dependencyResolutionManagement` google() content-filter mirror**
  (`settings.gradle.kts:17-28`): the `google()` repo now carries the SAME
  `includeGroupByRegex("com\\.android.*" / "com\\.google.*" / "androidx.*")` filters
  the `pluginManagement` block already had, so a polluted google index can never
  publish a fake `org.jetbrains`/`io.coil-kt`/… artifact into the build graph.
  `mavenCentral()` retained for all other groups.
- **Broken `dependencyVerification {}` block REMOVED** (`settings.gradle.kts:32-39`
  now a comment block documenting that verification is file-presence-driven and that
  the settings DSL must never be reintroduced because it cannot compile).

### 2. `.github/workflows/` — NOT touched (out of scope per PROMPT + AGENTS.md)
- The `distributionSha256Sum` pin for Gradle 8.13 in `android.yml`/`release.yml`/
  `llops.yml` and the pinned-checksum `opencode.ai/install` line in `llops.yml:144`
  remain **EXTERNAL notes** (workflow edits were explicitly prohibited by the
  PROMPT and AGENTS.md). Should be scheduled as a non-prohibited follow-up.

## Verification

- `gradle help` — green (settings script compiles — the fix's precondition).
- `gradle assembleDebug` — **green**, and run standalone with NO
  `--write-verification-metadata` flag to prove it now resolves through the
  committed lockfile (earlier, the SAME command failed on the missing aapt2/lint
  checksums; after the final metadata commit it passes purely from the lockfile).
- `gradle testDebugUnitTest` — 1391 total; only the 2 pre-existing
  `B1Plat01ReleaseSigningTest` asserts fail (`docs/RELEASE.md` + `app/build.gradle.kts`
  debug-buildType wording — unrelated to this phase; inputs untouched by this diff);
  the one-off `WikiLinkParserCacheUnitTest` cancellation flake did not recur in the
  final run.
- New pure-JVM test `app/src/test/java/com/authorss81/noteflow/B2Deps03DependencyVerificationTest.kt`
  (5 tests, source-pin style consistent with `B1Plat01ReleaseSigningTest`):
  1. `dependencyResolutionManagement` google() content filters mirror
     `pluginManagement` (balanced-block parse of `settings.gradle.kts`);
  2. `mavenCentral()` stays available for non-google groups;
  3. the non-existent `dependencyVerification {}` settings DSL is absent
     (no `verify =` assignment can silently creep back);
  4. `gradle/verification-metadata.xml` exists, is the real root element, and is not
     a stub (`<components>` + `sha256 value=` present);
  5. the lockfile pins the build-plugin groups `com.android.tools.build`,
     `org.jetbrains.kotlin`, `com.google.devtools.ksp`.

## Checksum / signing / secrets handling

- `verification-metadata.xml` contains only public artifact SHA-256 hashes (all
  `origin="Generated by Gradle"` — generated from a clean resolution, not hand-added),
  never secrets.
- No keys/passwords/decrypted content involved or logged; `allowBackup="false"`,
  `data_extraction_rules.xml`, `ClipboardGuard`, FLAG_SECURE all intact (untouched).
- The lockfile is regenerable and the diff is reviewable (incremental bootstrap
  rewrites only the changed entries), per Gradle docs.

## Out of scope (documented, not fixed here)

- **Gradle-distribution checksum pin + opencode-install checksum pin in CI
  workflows** (`distributionSha256Sum`, pinned `llops.yml` install) — a `.github/workflows/`
  edit is explicitly prohibited by the PROMPT/AGENTS.md; logged as external notes
  above. (AGENTS.md also forbids building with a wrapper jar is not used at all, so
  `distributionSha256Sum` has no offline fallback to fix in-repo.)
- B2-DEPS-04/05 (other dependency findings) are separate phases.

No schema change, no migration, no new dependencies, `.github/workflows/` untouched.