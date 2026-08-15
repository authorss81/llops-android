# Phase 115: Document fix - final status & consistency sweep [NOT STARTED]

You are working on **InkFlow/Noteflow**. This is the FINAL phase of the
Phase-33-generated security-fix pipeline (phases 39-114 fixed the
`docs/security-report.md` findings; this phase closes the pipeline by making
every status doc reflect the TRUE post-fix state of the repo). **Read
`docs/security-report.md`, `docs/phase-status.md`, `ROADMAP.md`, and
`workspace/SECURITY_FIX_PLAN.md` (workspace manifest) first.**

This phase writes NO application code - it is a documentation-only status audit,
mirroring Phase 20's method (then check every workspace phase heading and every
ROADMAP heading against actual code/commits/tests).

## Step 1 - Verify, then re-run the status audit of ALL phases

- Re-verify every workspace `phase-NN` (1-115) against code/commits/tests, like
  Phase 20 did. For the security-fix phases 39-114, confirm each PROMPT.md's
  `Definition of done` actually landed (unit tests exist, vulnerability path
  closed with `file:line` evidence, REPORT.md present).
- Mark EVERY heading beside itself with a status marker:
  `[DONE]` / `[DEFERRED]` / `[BLOCKED]` / `[PARTIAL]` / `[NOT STARTED]` /
  `[CANCELLED]` - the repo vocabulary. Do not trust stale ROADMAP `[x]` claims
  (see the POST-AUDIT TRUTH TABLE rule in AGENTS.md).

## Step 2 - Update final truth in the tracking docs

- `docs/phase-status.md`: update the workspace pipeline table (add phases
  39-115 with their verified status) and correct any stale rows.
- `ROADMAP.md` / `AGENTS.md`: remove every stale claim now superseded by the
  security fixes. At minimum:
  - AGENTS.md still may claim plugin/pin behaviors the fix phases changed - align
    them with the code.
  - Add/keep an authoritative pointer to `docs/security-report.md` resolution
    status and `workspace/SECURITY_FIX_PLAN.md`.
- `docs/security-report.md`: verify EVERY finding row is marked with the phase
  that fixed it (or `resolved at triage`), and that B1-CRYPTO-01 /
  B1-NET-03-class "top risks" now say what the code actually does. No finding
  may remain unmarked.
- `docs/general-audit-report.md`: if it exists, mark every item resolved or
  filed; if it does not exist, note that in the REPORT (Phase 33 referenced it,
  but no such file is present in the repo).
- `workspace/PHASES.md`: extend the phase list table through phase-115.

## Step 3 - Consistency sweep

- Grep the repo for claims that contradict the post-fix code (e.g.
  "encryption is a no-op", "never from the network", "5-fail lockout", old
  `com.authorss81.noteflow` path strings fixed by B1-PLAT-5, `app_startup.log`
  raw dumps, plaintext-not-yet-encrypted voice/import notes, non-atomic prefs,
  `getOrCreateDek` minting a fresh key, HTTPS-only-with-redirect-following
  claims, etc.). Fix every stale doc claim found.
- Confirm no fixing-phase finding remained "open" without a status row.

## Step 4 - Build the release APK (target for phase-117 Kali pentest)

The next phases (116 = round-2 source audit, 117 = Kali full-environment
dynamic pentest) need a FRESH release APK to attack. Trigger it here so it is
ready:

- Verify the release build works: run `gradle assembleRelease` is CI-only, so
  trigger the existing `Release APK` workflow instead:
  `gh workflow run release.yml` (workflow_dispatch, `.github/workflows/release.yml`).
- Watch the run until it completes and record its run id + the artifact name
  (`noteflow-release-apk` / `noteflow-apk`) and APK SHA256 if available into
  `docs/security-report-round2.md` "APK target" section (see phase-116/117 for
  the shared file) so phase-117 knows exactly what to download.
- If the workflow cannot be triggered from this phase (no `gh` token), document
  the exact `gh workflow run release.yml` command + expected artifact names in
  `workspace/phase-115/REPORT.md` so a human or the next phase can run it.

## Definition of done

- Every workspace phase heading (1-115) carries an accurate,
  code-verified status marker.
- Every finding in `docs/security-report.md` is marked fixed (with its phase) or
  `resolved at triage`; the "resolved at triage" list cites the Phase-33 reason.
- `docs/phase-status.md`, `ROADMAP.md`, `AGENTS.md`, `workspace/PHASES.md`
  (as applicable) contain no contradictory stale claims.
- `gradle testDebugUnitTest` + `gradle assembleDebug` still pass (doc-only
  changes must not affect the build; re-run to prove it).
- The `Release APK` workflow was triggered (or the trigger command + artifact
  names documented in REPORT.md) so phase-117 has a fresh release APK target.
- `workspace/phase-115/REPORT.md` documents the per-phase verification matrix.
- Commit + push.

## Constraints

- Documentation only - NO application code changes. (Triggering the release
  workflow via `gh` is allowed - it is not an app code change.)
- Do NOT edit `.github/workflows/`. No new deps. No build config changes.
- Never write real paths/secrets/content into docs beyond what the code already
  documents. Keep the honest-claims discipline from Phase 20 (no rubber-stamping
  `DONE` on unverified items).
