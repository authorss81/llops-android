# Phase 234 — Documentation + Architecture Doc Update

## Status
COMPLETE (2026-08-28).

## Goal
Finalize the nested-scrollable fix (phases 229–233) by updating the docs with the
phase-229 research findings, the fix, the guard, and the prevention strategy. No app code
changes required.

## What was delivered

### 1. `docs/ARCHITECTURE.md` — UI/layout section
Appended notes to the Core-subsystem UI section documenting the whole nested-scroll effort:
- **Phase-229 research note** (inserted before the phase-230 note): the
  `CheckScrollableContainerConstraints` crash class (a vertically scrollable child measured
  with `Constraints(maxHeight = Infinity)` from a parent verticalScroll); the 48-site
  inventory result (47 SAFE / 1 RISK / 0 CONFIRMED CRASH); the authoritative references
  `workspace/phase-229/INVENTORY.md` + `FIX_STRATEGY.md`; the **modifier-ordering invariant**
  (a height bound MUST precede `verticalScroll`).
- **Phase-233 goldens note**: `Phase233ScrollableGoldenTest` + committed baselines +
  `verifyPaparazziDebug` fail-closed.
- **Consolidated "Implemented in phases 229–234" summary** (the layered 5-part strategy:
  research → fix → runtime canary → static source scan → goldens) + the one-line invariant.

### 2. `docs/phase-status.md`
- Added the **previously-missing phase-229 row** (research — inventory + fix strategy, the
  authoritative references) before phase-230.
- Added the **phase-234 row** at the end: summarizes the deliverable and confirms no app code
  changed.

### 3. `docs/ROADMAP.md` / COMPATIBILITY
- `docs/ROADMAP.md` does not exist in this repo (checked via glob — no such file). There is
  therefore no "nested scrollable" known-issue note to mark resolved there; the fix is fully
  reflected in `docs/ARCHITECTURE.md` + `docs/phase-status.md`.

## DoD
- [x] `docs/ARCHITECTURE.md` updated (phase-229/233/234 notes + consolidated summary) — no code.
- [x] `docs/phase-status.md` updated for phases 229, 230–233 (already done by prior phases) and 234.
- [x] No code changes required.
- [x] `workspace/phase-234/REPORT.md` written (this file).
- [x] Docs updated and pushed.

## Verification
- No Kotlin/Gradle changes — markdown only; `gradle testDebugUnitTest` is unchanged (already
  green after phase-233, 1 pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure).
- Doc link targets confirmed to exist: `workspace/phase-229/INVENTORY.md`,
  `workspace/phase-229/FIX_STRATEGY.md`, `workspace/phase-229/REPORT.md`,
  `workspace/phase-230/REPORT.md`, `workspace/phase-231/REPORT.md`,
  `workspace/phase-232/REPORT.md`, `workspace/phase-233/REPORT.md` all present.
- No schema change, no migration, no new deps, `.github/workflows/` untouched, base-APK-size
  rule intact.

## Timeout
180 minutes — well within budget.
