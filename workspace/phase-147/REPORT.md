# Phase 147 report — CI action pinning (R2-b2b2-DEP-01) — gap report + Dependabot config

Status: **DONE (NOT-APPROVED PATH)** — workflow edits are `PENDING USER APPROVAL`;
the non-workflow deliverables (gap report + Dependabot config) are committed and the
build/test verification is green. Full analysis in `docs/CI_PINNING.md`.

## Approval gate

This phase edits `.github/workflows/`, which AGENTS.md forbids pipeline phases from
touching without explicit user approval. **No explicit approval was present in this
phase's context**, so per the PROMPT's NOT-approved branch the workflow files are
**NOT edited**. The `USER APPROVAL` gate is fully honored — no silent workflow edit.

> **For the approving user:** to execute the fix, add your approval here and either
> apply the SHAs in `docs/CI_PINNING.md §2` yourself or re-run this phase after
> approving. DoD after approval: `git grep "#v[0-9]" .github/workflows` → nothing.

## Finding recap (R2-b2b2-DEP-01, MEDIUM)

All 19 `uses:` references across `release.yml` (5), `android.yml` (3) and `llops.yml`
(11) point at mutable major-version tags (`actions/checkout@v4`,
`setup-java@v4`, `gradle/actions/setup-gradle@v3`, `upload-artifact@v4`, community
`android-actions/setup-android@v3`) with the release keystore secrets in job env
(`release.yml:44-47`, `llops.yml:192-195`) and `llops.yml:20-23` granting
`contents/pull-requests/issues: write` repo-wide. A compromised action version gets
code execution inside the signing job with the keystore secrets in env.

## What was delivered (committed)

1. **`docs/CI_PINNING.md`** — the gap report: every `uses:` with file:line, the
   mutable-tag → commit-SHA mapping computed via the GitHub REST API on 2026-08-18
   (each tag dereferenced to its commit), recommended pinned form with the Dependabot
   `# vX` comment, version-drift notes (setup-java v4 now resolves to an upstream
   **deprecation commit** — operator should pin v5 instead), the trusted/attested
   action guidance for `gradle/actions` + `setup-java`, and the llops.yml
   permission-scope reduction plan (job-level `permissions:`, drop
   `pull-requests: write`).
2. **`.github/dependabot.yml`** — `package-ecosystem: github-actions`, weekly
   schedule, grouped (`github-actions` group) so Dependabot maintains every SHA pin
   (updates SHA + `# vX` comment together) once the one-time pinning is applied.
   This is GitHub's own infrastructure applying the config — **not** a workflow edit.
3. **This report** (`workspace/phase-147/REPORT.md`).

## Resolved SHA mapping (see `docs/CI_PINNING.md` for full table)

| Action | Tag | Commit SHA |
|--------|-----|-----------|
| actions/checkout | v4 | `11d5960a326750d5838078e36cf38b85af677262` |
| actions/setup-java | v4 | `cf277c60eb25467037889841efdb72551f06f6c3` (deprecation commit — pin v5 `b6effb05e454b25005698d916606bdc6ffcbf961` instead) |
| gradle/actions/setup-gradle | v3 | `d9c87d481d55275bb5441eef3fe0e46805f9ef70` |
| actions/upload-artifact | v4 | `ea165f8d65b6e75b540449e92b4886f43607fa02` |
| android-actions/setup-android | v3 | `9fc6c4e9069bf8d3d10b2204b1fb8f6ef7065407` |

## PENDING USER APPROVAL (explicitly deferred)

- Pin all 19 `uses:` to the §2 SHAs (or the current-release majors pinned to SHAs).
- Apply the llops.yml permission-scope reduction (job-level `permissions:`,
  `contents: write` minimum; drop `pull-requests: write` from `run-phase`; `review`
  needs only `contents: write`; `select-phase` read-only).
- Fold in DEP-02's deferred CI wiring: `distribution-sha256-sum` on
  `gradle/actions/setup-gradle` (or switch CI to `./gradlew`) + pin the opencode
  installer (`llops.yml:147,320`).

## Constraints honored

- No app code changes. No DB schema change. No new dependencies.
- The release fail-closed keystore gate (B1-PLAT-1, `release.yml:29-40`) untouched.
- `allowBackup=false` / encryption posture untouched.
- Nothing logged or committed that is secret; the keystore env vars were never read.

## Verification

- `git grep "#v[0-9]" .github/workflows` still matches (workflows untouched — this is
  the documented NOT-approved state; the check flips to *nothing* only after the
  approved edit).
- `gradle testDebugUnitTest` — see below.
- `gradle assembleDebug` — see below.

### Full output

- `gradle testDebugUnitTest` → **BUILD SUCCESSFUL in 6m 14s** — 181 result XMLs,
  **1993 tests, 0 failures, 0 errors, 0 skipped** (system Gradle 8.13, Temurin 21,
  wrapper-pinned distribution per phase-146).
- `gradle assembleDebug` → **BUILD SUCCESSFUL in 2m 55s** — debug APK
  `app/build/outputs/apk/debug/app-debug.apk`, 173,999,374 B, SHA-256
  `beb0b00fb2ffc7c52e31db776ee8a1713e2383dc595e337378b57f5b795278c9`.

No app code changed this phase, so the green suite confirms the repo state after
committing only the two docs + Dependabot config (no workflow edits).

## DoD

- [x] `docs/CI_PINNING.md` gap report committed (every `uses:` with mutable-tag →
      commit-SHA mapping via the GitHub API).
- [x] Dependabot config committed.
- [x] Workflow edits explicitly documented as PENDING USER APPROVAL in this report
      and in `docs/CI_PINNING.md §5`.
- [x] Build + unit-test verification run and reported (NOT-approved DoD branch).
- [ ] (after approval only) all workflows pinned + `git grep "#v[0-9]"` empty.