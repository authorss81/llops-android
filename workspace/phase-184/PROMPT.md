# Phase 184: GalleryView card proportions — no dead empty space from rigid 10:16 ratio [DONE]

You are working on **InkFlow/Noteflow**. User visual review: gallery cards enforce a
fixed portrait `aspectRatio(10f/16f)` (`ui/components/GalleryView.kt:98-99`), so a short
title + 1-2 lines leaves >60% of the card empty ("empty bookmark" look).

Read `docs/ARCHITECTURE.md` and `docs/phase-status.md` first.

## WORKFLOW RULE
Work in small steps; `git add -A && git commit -m "llops: phase-184 step N: <desc>" && git push`
after EVERY step.

## Step 1 - Inventory (commit it)
- Read `ui/components/GalleryView.kt:90-212` (the `LazyVerticalGrid`/`GridCells`,
  `aspectRatio` on the `Card`, preview + tag + date footer layout).
- COMMIT this step.

## Step 2 - Fix proportions (choose ONE, justify it)
- **Preferred: balanced ratio** — change `aspectRatio(10f/16f)` to a shorter
  notebook-like ratio (`4f/5f` or `3f/4f`), OR
- **Staggered grid** — `LazyVerticalStaggeredGrid` with
  `StaggeredGridCells.Adaptive(minSize = 160.dp)` so card height adapts to content.
- Either way: the card body must not have a huge fixed empty band. If staggered is
  chosen, keep the existing filter/tag/date footer behavior.
- Respect the user's accessibility note: prefer `Modifier.heightIn(min = 180.dp)`
  over a strict unyielding ratio so large font scaling doesn't clip content.
- COMMIT this step.

## Step 3 - Regression proof
- `gradle assembleDebug` green + `gradle testDebugUnitTest` green (except the
  pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure + the 2
  `B1Plat01ReleaseSigningTest` asserts, untouched).
- If a policy class is introduced (ratio/height decision), add pure-JVM tests.

## Definition of done
- Cards show a balanced notebook-tile proportion with no dead empty band; large-font
  scaling cannot clip the date/tags. `workspace/phase-184/REPORT.md` before/after +
  tests.
- Commit + push.

## Constraints
- Do NOT edit `.github/workflows/`. No new dependencies. No DB schema change.
- If switching to staggered grid, keep memory bounded for large galleries
  (no per-card stroke rasterization — see phase-188).