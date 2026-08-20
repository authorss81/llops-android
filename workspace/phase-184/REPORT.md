# Phase 184 — GalleryView card proportions: no dead empty space from the rigid 10:16 ratio — REPORT

**Status:** DONE (2026-08-20).

## Problem (user visual review)
Gallery cards enforced a fixed portrait `aspectRatio(10f / 16f)` (was
`ui/components/GalleryView.kt:102`). At the 168dp `GridCells.Adaptive` cell width that
forced a **strict 268.8dp-tall card regardless of content** — a short title + 1-2
preview lines + footer occupied a thin top band and the lower ~60% of every tile was
dead empty space (the "empty bookmark" look). Because the ratio was strict and
unyielding, the date/tags footer could also be clipped by the card's shape clip when
the user's accessibility font scale made the textual content taller than 268.8dp.

## Root cause
The card height was decided by the *layout primitive* (`aspectRatio`), not by the
*content*. In `GalleryCardItem` the flexible preview `Box(weight(1f))` acted as a
sponge that absorbed the leftover height, converting missing content into empty
space instead of shrinking the card.

## Fix — chosen option (balanced ratio, accessibility variant)
The PROMPT's preferred option ("balanced ratio") — a shorter notebook-like
proportion — is implemented in its accessibility-preferred form: **no strict aspect
ratio at all**. The card height is now **content-driven with a minimum floor**:

1. **New pure-JVM policy** `services/GalleryCardLayoutPolicy.kt`:
   - `minCardHeightDp(fontScale)` = `(BASE_MIN_HEIGHT_DP * fontScale).coerceIn(BASE, MAX)`
     with `BASE_MIN_HEIGHT_DP = 180f` and `MAX_MIN_HEIGHT_DP = 288f`.
   - Non-finite / non-positive `fontScale` fail safe to the base floor (1.0), so a
     garbage input can never collapse the tile; monotonic growth up to the cap.
2. **`GalleryView.kt` card modifier** (`:111-115`): `aspectRatio(10f/16f)` → 
   `heightIn(min = minCardHeight)` where `minCardHeight` is derived from
   `GalleryCardLayoutPolicy.minCardHeightDp(LocalDensity.current.fontScale).dp`
   (`:96-101`). `heightIn` is a **floor, not a ceiling** — content taller than the
   floor grows the card, so nothing is ever clipped.
3. **`GalleryView.kt` layout restructure** (`:122-143`): the card body is no longer
   `fillMaxSize`/`weight(1f)` stretched. The wash uses `matchParentSize()` (fills
   whatever the content measures, never drives height); the content `Column` wraps
   its own height; the text preview is a plain `maxLines=3` block; the ink/empty
   placeholder is a fixed **84dp compact band** (centered icon + label) instead of
   stretching across the full leftover height.

### Justification vs the staggered-grid alternative
Kept `LazyVerticalGrid` (`GridCells.Adaptive(168.dp)`, `key = { it.id }`) and did NOT
switch to `LazyVerticalStaggeredGrid`:
- **Same outcome, simpler semantics**: a content-driven-height card already adapts its
  height to the content inside a normal grid; each row simply equalizes to its tallest
  card (standard notebook-tile behavior).
- **Memory/recycle bound (PROMPT constraint)**: the lazy grid keeps its Recycler-style
  item pool and per-item keys; a staggered grid would change recycling/measurement
  semantics for zero benefit here.
- No per-card stroke rasterization anywhere (preview derived only from existing
  title/extractedText/tags/date fields — phase-165 design preserved).

## Before / after
| | Before (phase-165 ratio) | After (phase-184 floor) |
|---|---|---|
| Card height at 168dp cell, 1.0 font scale | `168 ÷ (10/16) = 268.8dp` fixed | `max(180dp floor, content height)` — short note ≈ 180dp |
| Short note (title + 2 preview lines), content ≈ 159dp | 268.8dp fixed → **~110dp dead band (~41%)** (user observed >60% on 1-line notes) | 180dp tile → **~21dp slack (~12%)**, no visible dead band |
| Empty/ink page | placeholder stretched across the whole leftover height | compact centered 84dp placeholder band |
| Large-font accessibility (e.g. 1.3×, 2×) | fixed 268.8dp can clip footer | floor scales (234dp at 1.3×, capped 288dp at ≥1.6×); content taller than the floor still grows the card — footer never clipped |
| Tags/date footer, filter behavior, grid | unchanged | unchanged (`:252-307`, all phase-183/146/165 behavior kept) |

## Tests
- `GalleryCardLayoutPolicyTest` (6, pure JVM): base floor = 180f, monotonic growth at
  1.3×/2× (2× capped at 288f), hard cap at extreme scales, sub-1.0 never drops below
  the floor, NaN/±Inf/0/negative fail safe to the base floor, and the
  floor-vs-old-268.8dp sanity pin.
- `Phase184GalleryProportionTest` (5, source pins): `aspectRatio(10f/16f)` and any
  `Modifier.aspectRatio` gone from the gallery card, `heightIn(min = minCardHeight)`
  present, floor wired to `GalleryCardLayoutPolicy.minCardHeightDp(LocalDensity...fontScale)`,
  policy constants `180f`/`288f` + `coerceIn`, phase-183 preview `maxLines=3` + footer
  `maxLines=1`/Ellipsis kept, `GalleryView` signature + Adaptive grid preserved.

## Verification
- `gradle assembleDebug` — **green** (app-debug.apk, SHA-256 `1dea66e8…`, built in
  run 2026-08-20).
- `gradle testDebugUnitTest` — **2450 tests, 1 failed** = the pre-existing
  `Phase148UiFailureTextScrubTest` UNC-path failure
  (`UNC path must be redacted: err \\fileserver\share\secret-wills.docx` at
  `Phase148UiFailureTextScrubTest.kt:234`), untouched and reproduced on a clean tree
  in earlier phases (146/149/151/153/158/166/177/183 all documented the same single
  failure). 2450 = 2439 previous baseline + 11 new phase-184 tests (6 + 5). All other
  2449 green, including the phase-183 gallery pins
  (`GalleryTitleDisplayPolicyTest`, `Phase183GalleryTypographyTest`).

## Constraints honored
- No `.github/workflows/` edits. No new dependencies. No DB schema change (no
  migration, schema untouched). No settings/permissions changes.
- Kept `LazyVerticalGrid` → per-card stroke rasterization stays out (memory bounded
  for large galleries, per the phase-188 constraint).
- `.done` marker + REPORT committed and pushed.

## Workflow
- Step 1 (inventory): commit `fd5c5d3` — `workspace/phase-184/INVENTORY.md`.
- Step 2 (fix): commit `6fef50c` — `GalleryCardLayoutPolicy.kt` + `GalleryView.kt`
  + 2 test classes.
- Step 3 (proof): this REPORT + docs — commit + push.