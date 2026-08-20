# Phase 188: GalleryView — robustness: perf, large-font scaling, dark-theme contrast, tag-chip overflow [NOT STARTED]

You are working on **InkFlow/Noteflow**. User visual review "exploration" section raised
4 risks for the gallery that phases 183-187 must not regress into:

1. **Canvas stroke deserialization overhead** — the gallery must NEVER rasterize real
   `pointsJson` inside grid items (jank on 50+ notes). Rely on text/metadata previews
   only, or a pre-rendered tiny thumbnail if one already exists. No per-card stroke parse.
2. **Large font scaling (1.3x-1.5x)** — card body must not clip the date/tags footer;
   use `Column(weight(1f, fill=false))` + `Modifier.heightIn(min=180.dp)` instead of a
   strict unyielding ratio.
3. **Dark theme contrast** — cards must stay distinct from near-black surfaces: add a
   visible `BorderStroke(1.dp, outlineVariant.copy(alpha=0.35f))` border (or equivalent)
   so cards don't blend into the background.
4. **Tag chip overflow on multi-tag notes** — show at most 2 tag chips + a `+N` badge,
   `maxLines=1` on the chip row, so the update timestamp stays visible.

Read `docs/ARCHITECTURE.md` and `docs/phase-status.md` first. Code:
`ui/components/GalleryView.kt`.

## WORKFLOW RULE
Work in small steps; `git add -A && git commit -m "llops: phase-188 step N: <desc>" && git push`
after EVERY step.

## Step 1 - Inventory (commit it)
- Read `ui/components/GalleryView.kt` end-to-end after phases 183-187 land: confirm
  no stroke parsing, no unyielding ratio, no tag-row unbounded growth, card
  contrast in dark theme.
- COMMIT this step.

## Step 2 - Apply the four mitigations
- (1) Verify/guarantee the grid uses only text/metadata previews (never `pointsJson`
  raster). Add a source pin test that no gallery path calls the stroke-load for
  thumbnails.
- (2) Card layout uses weight/heightIn so large font scaling cannot clip footer.
- (3) Dark-theme border on cards for distinct separation.
- (4) Tag row capped at 2 chips + `+N` badge, single line.
- COMMIT this step.

## Step 3 - Regression proof
- `gradle assembleDebug` green + `gradle testDebugUnitTest` green (except the
  pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure + the 2
  `B1Plat01ReleaseSigningTest` asserts, untouched).
- Pure-JVM tests: tag-cap policy (2 + N badge), large-font layout bounds policy,
  dark-theme border decision, and source pins for no-stroke-raster + bounded tag row.

## Definition of done
- All four risks mitigated with policy classes where useful; tests green.
  `workspace/phase-188/REPORT.md` per-risk evidence + tests.
- Commit + push.

## Constraints
- Do NOT edit `.github/workflows/`. No new dependencies. No DB schema change.
- Keep the gallery lightweight — no per-frame allocations, no stroke parsing on the
  main thread.