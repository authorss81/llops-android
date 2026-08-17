# Phase 146: Build toolchain & lockfile integrity — checksummed Gradle, signature-verified lockfile, filtered Central, stale-graph cleanup [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report-round2.md`**
first (findings R2-b2b2-DEP-02, R2-b2b2-DEP-04, R2-b2b2-DEP-03) and
`docs/phase-status.md` + `docs/ARCHITECTURE.md`. This phase hardens the build's
supply-chain posture at the toolchain + lockfile + repository-filter level.

## Source findings (all OPEN — LOW, LOW, INFO)

1. **R2-b2b2-DEP-02** (LOW) — The toolchain that signs the release APK is
   downloaded with zero integrity verification: no `gradle/wrapper/`, no
   `gradlew`, `git grep distributionSha256Sum` → none. Gradle 8.13 provisioned
   without a checksum at `android.yml:40-43`, `release.yml:21-24`,
   `llops.yml:134-137`/`:307-310`; the opencode CLI installs via
   `curl -fsSL https://opencode.ai/install | bash` (`llops.yml:147,320`).
   B2-DEPS-03 residual, never actually closed.
2. **R2-b2b2-DEP-04** (LOW) — Lockfile validation is checksum-only
   trust-on-first-use: `gradle/verification-metadata.xml:3-6`
   `<verify-signatures>false</verify-signatures>`, no `<trusted-keys>` block;
   `settings.gradle.kts:28` exposes `mavenCentral()` unfiltered — any non-google
   group resolves from Central.
3. **R2-b2b2-DEP-03** (INFO) — Stale build-graph-only dependency entries
   (`okhttp-3.0.0`, `okio-1.6.0` POM-only at
   `gradle/verification-metadata.xml:2423-2426,2441-2444`; grpc 1.57.0, netty
   4.1.93, flatbuffers 1.12.0, guava 27.0.1-android from the
   `:plugins:llm` graph `:2632,:2669,:2090,:2107`) — not exploitable today, but
   an audit blind spot.

## The fix (where & how)

- **R2-b2b2-DEP-02:** commit a Gradle wrapper with `distributionSha256Sum` (or
  pass `distribution-sha256-sum` to `setup-gradle` in the workflows) and pin the
  opencode installer to a checksum-verified release download. NOTE: grading
  builds run as `gradle` (system) on CI — the wrapper addition is for
  integrity-pinning; do NOT break the CI invocation.
- **R2-b2b2-DEP-04:** enable `verify-signatures` with a committed `<trusted-keys>`
  set (PGP over AGP, Kotlin, KSP), scope `mavenCentral()` with an allow-list
  (`includeGroupByRegex` like the google-direction filter at
  `settings.gradle.kts:22-27`), and document lockfile-regeneration provenance.
- **R2-b2b2-DEP-03:** run `gradle :app:dependencyInsight --dependency okhttp`
  (and per-module) to confirm the POM-only entries are dead resolution
  candidates, then exclude the stale metadata or accept-as-is with a documented
  note + track the LLM-graph lines for the tasks-genai bump.

## Verification

- New/updated pure-JVM/source-pin unit tests: wrapper checksum present; Gradle
  provisioned with `distribution-sha256-sum`; Central allow-list regexp in
  `dependencyResolutionManagement`; trusted-keys block present; a
  dependencyInsight source pin documenting the stale-graph verdict.
- `gradle testDebugUnitTest` then `gradle assembleDebug`, report in
  `workspace/phase-146/REPORT.md`. (If `.github/workflows/` must change for the
  opencode-installer pin, that part is DEFERRED to phase-147 — flag it.)

## Definition of done

- All three findings closed with `file:line` before/after evidence.
- Gradle + opencode installs are integrity-pinned; lockfile uses signatures;
  Central is group-filtered; stale entries either excluded or documented.

## Constraints

- Do NOT edit `.github/workflows/` in THIS phase — anything that requires a
  workflow edit goes to phase-147 (USER APPROVAL REQUIRED). Mark them deferred
  here. Prefer `settings.gradle.kts` + `gradle/` changes.
- NO DB schema change. No new dependencies (the lockfile is a config change).
- Never log keys, passwords, or decrypted note content.
- Do not fix OTHER findings in this phase — document new bugs in REPORT.md.