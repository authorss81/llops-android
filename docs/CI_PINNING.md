# CI Action Pinning — Gap Report (R2-b2b2-DEP-01)

> Phase-147 deliverable. Finding **R2-b2b2-DEP-01** (MEDIUM, `docs/security-report-round2.md`):
> release-signing CI runs unpinned mutable-tag third-party Actions with the keystore
> secrets in job env. This document is the **gap report + the executable fix plan**.
>
> **Status of workflow edits: PENDING USER APPROVAL.** Editing `.github/workflows/`
> requires explicit user approval per AGENTS.md (pipeline phases must not touch
> workflows without it). No approval was granted to this phase, so the workflow
> files are **NOT edited** here; this report + `.github/dependabot.yml` are committed
> so the approved fix can be applied mechanically later (exact SHAs below, computed
> via the GitHub API on 2026-08-18).
>
> One-line scope note: DEP-02's deferred CI wiring (`distribution-sha256-sum` on
> `gradle/actions/setup-gradle`, the `curl | bash` opencode installer at
> `llops.yml:147,320`) also lives in these files and is folded into the same
> pending-approval edit set.

## 1. Inventory — every `uses:` in the repo

| File | Line | `uses:` | Mutable ref |
|------|------|---------|-------------|
| `.github/workflows/release.yml` | 13 | `actions/checkout` | `@v4` |
| `.github/workflows/release.yml` | 16 | `actions/setup-java` | `@v4` |
| `.github/workflows/release.yml` | 22 | `gradle/actions/setup-gradle` | `@v3` |
| `.github/workflows/release.yml` | 51 | `actions/upload-artifact` | `@v4` |
| `.github/workflows/release.yml` | 61 | `actions/upload-artifact` | `@v4` |
| `.github/workflows/android.yml` | 32 | `actions/checkout` | `@v4` |
| `.github/workflows/android.yml` | 35 | `actions/setup-java` | `@v4` |
| `.github/workflows/android.yml` | 41 | `gradle/actions/setup-gradle` | `@v3` |
| `.github/workflows/llops.yml` | 124 | `actions/checkout` | `@v4` |
| `.github/workflows/llops.yml` | 129 | `actions/setup-java` | `@v4` |
| `.github/workflows/llops.yml` | 135 | `gradle/actions/setup-gradle` | `@v3` |
| `.github/workflows/llops.yml` | 140 | `android-actions/setup-android` | `@v3` |
| `.github/workflows/llops.yml` | 242 | `actions/upload-artifact` | `@v4` |
| `.github/workflows/llops.yml` | 251 | `actions/upload-artifact` | `@v4` |
| `.github/workflows/llops.yml` | 299 | `actions/checkout` | `@v4` |
| `.github/workflows/llops.yml` | 304 | `actions/setup-java` | `@v4` |
| `.github/workflows/llops.yml` | 310 | `gradle/actions/setup-gradle` | `@v3` |
| `.github/workflows/llops.yml` | 315 | `android-actions/setup-android` | `@v3` |
| `.github/workflows/llops.yml` | 365 | `actions/upload-artifact` | `@v4` |

All 19 `uses:` references resolve to mutable major-version tags; **no commit-SHA pin
exists anywhere**. (`git grep "#v[0-9]" .github/workflows` matches every tag line —
that is the finding's reproducer.)

## 2. Mutable-tag → commit-SHA mapping

Resolved 2026-08-18 via the GitHub REST API (`GET /repos/{owner}/{repo}/git/ref/tags/{tag}`,
annotated-tag dereferenced to the commit). Each entry lists the immutable full SHA to
pin, the commit date, and the resolved commit's first-line message.

| Action | Current ref | Full commit SHA to pin | Tag-commit date | Commit message |
|--------|-------------|------------------------|-----------------|----------------|
| `actions/checkout` | `@v4` | `11d5960a326750d5838078e36cf38b85af677262` | 2026-07-16 | backport fixes to releases-v4 (#2524) |
| `actions/setup-java` | `@v4` | `cf277c60eb25467037889841efdb72551f06f6c3` | 2026-08-04 | Deprecate setup-java v4 (#1194) |
| `gradle/actions/setup-gradle` | `@v3` | `d9c87d481d55275bb5441eef3fe0e46805f9ef70` | 2024-07-15 | [bot] Update dist directory |
| `actions/upload-artifact` | `@v4` | `ea165f8d65b6e75b540449e92b4886f43607fa02` | 2025-03-19 | Merge pull request #685 … |
| `android-actions/setup-android` | `@v3` | `9fc6c4e9069bf8d3d10b2204b1fb8f6ef7065407` | 2024-11-09 | Update dependencies |

### Recommended pinned form (for the approved edit)

Each line becomes `<owner>/<repo>@<full-sha> # v<major>` — the trailing comment is
required so Dependabot can maintain the pin (it rewrites the SHA **and** the comment
together on every update PR):

```yaml
- uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4
- uses: actions/setup-java@cf277c60eb25467037889841efdb72551f06f6c3 # v4
- uses: gradle/actions/setup-gradle@d9c87d481d55275bb5441eef3fe0e46805f9ef70 # v3
- uses: android-actions/setup-android@9fc6c4e9069bf8d3d10b2204b1fb8f6ef7065407 # v3
- uses: actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4
```

### Version-drift notes (operator should confirm before applying)

- `actions/setup-java@v4` **now resolves to a deprecation commit** ("Deprecate
  setup-java v4 (#1194)", 2026-08-04). Pinning to that SHA freezes the deprecation
  notice. The operator should instead pin `actions/setup-java@v5` →
  `b6effb05e454b25005698d916606bdc6ffcbf961` (resolve again at edit time) or verify
  the v4 tag moved past the deprecation commit.
- Newer majors exist for every action: `actions/checkout@v5`
  (`fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09`), `actions/upload-artifact@v5`
  (`330a01c490aca151604b8cf639adc76d48f6c5d4`), `gradle/actions/setup-gradle@v4`
  (tag `v4` in the `gradle/actions` monorepo, verified-actions mode),
  `android-actions/setup-android@v4` (`40fd30fb8d7440372e1316f5d1809ec01dcd3699`).
  The approved edit may bump majors at pin time (prefer the current release family);
  pin whatever version is actually used, to the tag's commit SHA.
- `gradle/actions/setup-gradle` v4+ supports the "trusted/attested build" flow; the
  finding's "prefer trusted-publishers/attested action versions" is satisfied by
  using the gradle-actions v4/v6 line (pinned) + optionally
  `gradle/actions/attest-build-provenance` for release artifacts. `setup-java` has no
  equivalent attestation mechanism; SHA-pinning is its control.

## 3. Dependabot maintenance loop (committed)

`.github/dependabot.yml` is committed (Dependabot is GitHub's own infrastructure —
not a workflow edit). It declares the `github-actions` ecosystem with a weekly
schedule and a single `github-actions` group, so once every `uses:` is SHA-pinned,
Dependabot opens grouped PRs that update the SHA **and** the trailing `# vX` comment
together. Caveat documented in the config: Dependabot does **not** convert floating
tags (`@v4`) to SHAs — the one-time conversion is the operator step above (a
`pinact`-style bulk conversion is the standard tool).

## 4. Permission-scope analysis (llops.yml)

`llops.yml:20-23` sets repo-wide `contents/pull-requests/issues: write` for every
job. The fix plan for the approved edit:

- `select-phase` job: read-only `contents: read` only (it calls the GitHub API with
  `github.token`).
- `run-phase` job: needs `contents: write` (bot commits to `main`) + `issues: write`
  (blocked-phase issue at `llops.yml:272-283`); `pull-requests: write` is **not**
  needed here — remove it.
- `review` job: needs `contents: write` only; it never touches PRs/issues.
- Recommend `permissions:` at the job level (per-job scopes) instead of the
  workflow-level block, and confirm `pull-requests`/`issues` are actually exercised
  before granting them at all.

## 5. Status

- **Committed this phase:** `docs/CI_PINNING.md` (this report), `.github/dependabot.yml`,
  `workspace/phase-147/REPORT.md`.
- **PENDING USER APPROVAL:** editing `.github/workflows/{release,android,llops}.yml`
  to apply §2 SHAs + §4 permissions. After approval, the DoD check
  `git grep "#v[0-9]" .github/workflows` must return nothing.
- **Untouched by design:** the release fail-closed keystore gate (B1-PLAT-1), the
  `llops.yml` bot auto-push cadence, and all app code.