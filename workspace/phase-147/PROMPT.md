# Phase 147: CI action pinning (R2-b2b2-DEP-01) — commit-SHA pins + trusted publishers [USER APPROVAL REQUIRED] [NOT STARTED]

> **USER APPROVAL REQUIRED (hard rule).** This phase edits
> `.github/workflows/` (`release.yml`, `android.yml`, `llops.yml`), which AGENTS.md
> forbids pipeline phases from touching without explicit user approval. If you are
> the approving user and want this executed, add the approval in this phase's
> REPORT.md and proceed; otherwise DO NOT edit workflows in this phase — instead
> complete ONLY the documented analysis + non-workflow artifacts (a
> `docs/CI_PINNING.md` gap report + a Dependabot config that GitHub's own
> infrastructure applies) and mark the workflow edits explicitly deferred.

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report-round2.md`**
first (finding R2-b2b2-DEP-01, MEDIUM) and `docs/phase-status.md` +
`docs/ARCHITECTURE.md`.

## Source finding (OPEN, MEDIUM)

**R2-b2b2-DEP-01** — Release-signing CI runs unpinned mutable-tag third-party
Actions with the keystore secrets in job env:
`.github/workflows/release.yml:13,32,40,51,61` (`actions/checkout@v4`,
`setup-java@v4`, `gradle/actions/setup-gradle@v3`, `upload-artifact@v4`),
`android.yml:32,36,41`, `llops.yml:124,129,135,140,240,296,301,308,313,363`
(incl. community `android-actions/setup-android@v3` at `:140,:313`). Repo perms
`llops.yml:20-23` (`contents/pull-requests/issues: write`), bot commits
auto-pushed to main (`llops.yml:202-231`), and the release APK is signed with
`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD` in job env (`release.yml:44-47`,
`llops.yml:192-195`).

## The fix (where & how)

- Pin every action `uses:` to a commit SHA (Dependabot `update-git-hashes` +
  release-notes PRs keep them current).
- Prefer trusted-publishers/attested action versions for `gradle/actions` and
  `setup-java`.
- Give the llops agent the minimum `contents: write` scope (review whether the 
  `pull-requests`/`issues: write` perms are needed on every job).

## Verification

- A `docs/CI_PINNING.md` gap report listing every `uses:` with its mutable-tag →
  commit-SHA mapping (computed via the GitHub API or `gh api`); Dependabot
  config committed. If workflow edits are approved, `git grep "#v[0-9]"
  .github/workflows` returns nothing.
- `gradle testDebugUnitTest` then `gradle assembleDebug`, report in
  `workspace/phase-147/REPORT.md`.

## Definition of done

- Either (approved) all workflows pinned + Dependabot config committed, OR
  (not approved) the CI pinning gap report + Dependabot config committed and the
  workflow edits documented as PENDING USER APPROVAL in REPORT.md.
- No app code changes. The release-fail-closed keystore gate is untouched.

## Constraints

- Full faith of the USER APPROVAL gate — do not silently edit workflows.
- NO DB schema change. No new dependencies. Never log keys/passwords/decrypted
  content. Keep `allowBackup=false` and the release fail-closed signing.