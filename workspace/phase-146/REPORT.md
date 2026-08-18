# Phase 146 report — Build toolchain & lockfile integrity (R2-b2b2-DEP-02 + DEP-03 + DEP-04)

Status: **DONE** — all three findings fixed (verified `gradle testDebugUnitTest` + `gradle assembleDebug` + `gradle :app:compileReleaseKotlin`).

## Findings fixed

### R2-b2b2-DEP-02 (LOW) — The Gradle distribution is downloaded with zero integrity verification (no wrapper, no `distributionSha256Sum`)

**Before:** no `gradle/wrapper/`, no `gradlew`, `git grep distributionSha256Sum` → none. Gradle 8.13
provisioned by mutable `gradle-version` on `android.yml:40-43`, `release.yml:21-24`, `llops.yml:134-137/307-310`.

**Fix (phase-146):** a full wrapper is committed:
- `gradlew` (+`gradlew.bat`), `gradle/wrapper/gradle-wrapper.jar` (43,705 B, executes on Java 21,
  wraps and boots `Gradle 8.13`).
- `gradle/wrapper/gradle-wrapper.properties:7` pins
  `distributionSha256Sum=20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78` — verified
  2026-08-18 against the live `https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256`,
  and re-verified by actually downloading `gradle-8.13-bin.zip` and hashing it locally (both match).
- `./gradlew` run confirms the wrapper honors the pin (downloads + runs 8.13).

**HONEST SCOPE (review-fix):** CI still provisions `gradle-version: "8.13"` with NO checksum on every
path (`android.yml:43`, `release.yml:24`, `llops.yml:137,312`) and the opencode installer
(`llops.yml:147,320`) is still unpinned `curl | bash`. Wiring `distribution-sha256-sum` into
`gradle/actions/setup-gradle` / switching CI to `./gradlew` / pinning the opencode installer are
DEFERRED to phase-147 because they require `.github/workflows/` edits (USER APPROVAL REQUIRED per
AGENTS.md). Until then the wrapper is the integrity source of truth for local/developer provisioning
only; `Phase146BuildIntegrityTest` guards that CI stays on 8.13 so it cannot drift from the pin.

### R2-b2b2-DEP-04 (LOW) — Lockfile validation is checksum-only TOFU; no signatures, no trusted-keys; mavenCentral() unfiltered

**Before:** `gradle/verification-metadata.xml:3-6` → `<verify-signatures>false</verify-signatures>`, no
`<trusted-keys>`; `settings.gradle.kts:28` bare `mavenCentral()` (google filter one-way only).

**Fix (phase-146):**
- `<verify-signatures>true</verify-signatures>` + `<trusted-keys>` (57 keys initially) + committed local
  keyrings `gradle/verification-keyring.gpg` (binary) and `gradle/verification-keyring.keys` (armored),
  exported with `--export-keys` so verification never requires a live key server.
- `mavenCentral()` in `dependencyResolutionManagement` is one-way allow-listed (51 full-match
  `includeGroupByRegex` groups), closing the reverse gap: unknown groups and `androidx.*`/`com.google.*`
  versions google() doesn't host fail fast.
- Lockfile-regeneration provenance documented in `settings.gradle.kts`.

**Review-fix regeneration (2026-08-18)** — re-ran
`gradle --write-verification-metadata sha256,pgp --export-keys testDebugUnitTest assembleDebug` from a
runner with live keyserver access, converting previously-unfetchable signer keys into trusted + exported:
- trusted keys: **57 → 64** (adds the androidx+com.android+androidx.compose umbrella
  `A5F483CD733A4EBAEA378B2AE88979FB9B30ACF2`, `org.eclipse.ee4j`, `com.google.errorprone`,
  `com.google.protobuf`, `org.bouncycastle`, `commons-codec/io` + `org.apache.commons`,
  `org.jetbrains.intellij.deps:trove4j`).
- PGP-bound artifacts: **10 → 11** `<pgp>` records; keyring export **65 → 73 master keys**
  (133 → **155 fingerprints**, binary == armored, verified via `gpg --show-keys --with-colons`).
- All **64** trusted keys are present in the committed keyring.
- Note: the same 16 key short-IDs remain in `<ignored-keys>` (Gradle merges, never prunes, on
  regeneration) — trusted-lookup takes precedence at verify time, so this is harmless; the strict
  build passes with the regenerated file (below). ~374/941 artifacts remain checksum-only because their
  signers publish no fetchable key (or no `.asc`), and every remaining artifact keeps full sha256 binding,
  so there is no regression — the PGP layer binds wherever signatures + keys exist and the whole graph
  stays checksum-pinned regardless.

**Review-fix (2026-08-18) — plugin resolution also filtered:** `pluginManagement`'s `mavenCentral()`
is now one-way allow-listed with the SAME 51 literal groups (pluginManagement is pre-evaluated and
cannot reference script-level declarations, so the list is duplicated literally there), so a poisoned
Central index can inject neither an unknown dependency group nor an unknown plugin group. The guard tests
now scan BOTH blocks.

### R2-b2b2-DEP-03 (INFO) — Stale build-graph-only dependency entries are an audit blind spot

**Fix (phase-146):** run `gradle :app:dependencyInsight --dependency okhttp` → **`okhttp:3.0.0 -> 4.12.0`**
(a losing candidate of `com.google.mlkit:translate`: the 3.0.0 **jar never resolves**, only its `.pom`
metadata, so the CVE-2021-0341 range is NOT reachable in any shipped artifact). The POM-only entries are
therefore KEPT (deleting them breaks resolution: Gradle must checksum the `.pom` the losing candidate
still requests) and DOCUMENTED:
- `settings.gradle.kts` R2-b2b2-DEP-03 comment block (verdict + "their jars NEVER resolve").
- `grpc 1.57.0`, `netty 4.1.93.Final`, `flatbuffers-java 1.12.0`, `guava 27.0.1-android` are tracked as
  `:plugins:llm` tasks-genai-graph-only (never packaged, `plugins/llm/build.gradle.kts:90-122`) for the
  next tasks-genai bump.
- `Phase146BuildIntegrityTest` source-pins the retained entries + documented verdict.

## Review-fix addendum (2026-08-18)

All nine review findings for phase-146 (previous review pass) were addressed:

1. **Missing `workspace/phase-146/REPORT.md`** → this file (see also the settings.gradle.kts references
   to it, now valid).
2. **Docs/tracking not updated** → `docs/phase-status.md` phase-146 row = `DONE`;
   `docs/ARCHITECTURE.md` "Build / CI essentials" + gotchas updated ("Implemented in phase-146" note);
   `docs/security-report-round2.md` quick-lookup + DEP-02/03/04 detail rows marked
   `FIXED in phase 146` with before/after evidence.
3. **DEP-02 partially closed** → honesty fix: `Phase146BuildIntegrityTest` class doc + test now state
   explicitly that CI provisioning is version-pinned but NOT yet checksum-pinned (deferred to phase-147);
   settings/ARCHITECTURE/security-report repeat the same residual-gap note.
4. **pluginManagement unfiltered** → fixed: literal one-way allow-list added to `pluginManagement`
   `mavenCentral()`; `Phase146BuildIntegrityTest` now pins both blocks (importantly: Gradle's
   pre-evaluated `pluginManagement` cannot reference script-level declarations, hence the literal
   duplication — a loop over a shared top-level `val` is a settings compilation error, empirically
   confirmed).
5. **PGP binds only a minority** → regeneration above (trusted 57→64, pgp 10→11, keyring 65→73 keys),
   plus honest pre/post numbers here. Remaining checksum-only artifacts keep full sha256 binding —
   no regression, claim now matches reality.
6. **`assembleRelease` never verified** → validated `gradle :app:compileReleaseKotlin` green against the
   regenerated lockfile + allow-list (release dependency graph resolves; the signed APK itself fails
   closed without `RELEASE_KEYSTORE_B64`, by design). Full `assembleRelease` must be proven on CI with
   the real keystore (phase-57 B1-Plat-01 path).
7. **Duplicated allow-list across tests** → single source: `app/src/test/.../CentralAllowlist.kt`
   (`CentralAllowlist.groups`, full 51); `B2Deps03DependencyVerificationTest` and
   `Phase146BuildIntegrityTest` both read it, so the weaker subset cannot silently pass.
8. **Allow-list precision** → `com\.github.*` tightened to `com\.github\.pemistahl` (exact group;
   no other com.github group resolves). `com\\.google\\.android` (exact match) retained as-is — harmless
   and removing it risks breaking resolution of that group.
9. **settings.gradle.kts trailing newline** → restored.

## Verification

- `gradle testDebugUnitTest` — **green, 0 failures** (full suite, incl. `Phase146BuildIntegrityTest` (6),
  `B2Deps03DependencyVerificationTest` (5), `Phase131MetadataAlignmentTest` (11) and the whole
  phase-126..145 suites). Executed --rerun-tasks against the REGENERATED lockfile.
- `gradle assembleDebug` — **green** (90 tasks).
- `gradle :app:compileReleaseKotlin` — **green** (release dependency resolution vs regenerated lockfile).
- `gradle --write-verification-metadata sha256,pgp --export-keys testDebugUnitTest assembleDebug` —
  **green** (regeneration, strict verification active).
- Keyring integrity: `gpg --show-keys --with-colons` → 155 fingerprints, binary == armored,
  all 64 trusted keys covered.
- checksum: wrapper `distributionSha256Sum` == official `gradle-8.13-bin.zip.sha256`.

## Definition of done

- [x] All three findings closed with `file:line` before/after evidence (above + `docs/security-report-round2.md`).
- [x] Gradle wrapper is checksum-pinned; CI variant documented + deferred to phase-147 (workflow edits need approval).
- [x] Lockfile verifies signatures against committed `<trusted-keys>` + local keyring; Central is group-filtered in
      dependency AND plugin resolution; regeneration provenance documented.
- [x] Stale entries documented (dependencyInsight verdict) + tracked, not silently deleted.
- [x] No DB schema change; no new dependencies; no `.github/workflows/` edits.