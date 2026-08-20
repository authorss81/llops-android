# Phase 188 — GalleryView robustness: perf, large-font scaling, dark-theme contrast, tag-chip overflow

Inventory of the current `ui/components/GalleryView.kt` (479 lines, read end-to-end
after phases 183–187 landed) against the 4 risks raised in the user visual review
"exploration" section. Every risk is checked against the CURRENT tree.

> **Review-fix 2026-08-20:** this file is the pre-fix exploration snapshot. The
> risk-#2 "intended mechanism" written below (`weight(1f, fill=false)` seating
> the floor's slack between preview and footer) was later found to be INERT in
> the implemented layout (Compose only redistributes flex slack when the parent's
> main axis is finite; the card is never height-capped). The implemented +
> review-fixed guarantee is structural: min-floor + no height cap means the
> footer can never be clipped. See `REPORT.md` Risk #2.

## Risk #1 — Canvas stroke deserialization overhead

**Status: already clean, needs a dedicated source-pin test.**

`GalleryCardItem` derives its preview ONLY from `NotePageEntity` metadata:
`page.title`, `page.extractedText`, `page.tags`, `page.pinned`,
`page.sourceFileType`, `page.updatedAt`. Verified facts in the current file:

- Zero `pointsJson` tokens (`grep -c pointsJson GalleryView.kt` → 0).
- No `getStrokesForPage` / `strokesForPage` / `loadStrokes` / `deserializeStrokes`
  calls anywhere in `GalleryView.kt`.
- The phase-187 paper texture is card-size + constants only
  (`InkCardPaperPolicy.gridColumns/gridRows`), never stroke geometry.
- No per-card image/thumbnail rasterization; the only `drawBehind` work is the
  bounded ≤96-dot notebook grid.

`Phase187GalleryInkPaperTest` already pins part of this (`gallery` contains no
`pointsJson`, no `getStrokesForPage`). Phase-188 adds a broader, gallery-wide pin
(no stroke-load for thumbnails via ANY name, plus metadata-only preview fields).

## Risk #2 — Large font scaling (1.3x–1.5x) must not clip the date/tags footer

**Status: partially done (phase-184 floor) — the prompt's `weight(1f, fill=false)`
slack mechanism is missing.**

- Phase-184 removed the rigid `aspectRatio(10f/16f)` and applied a font-scaled
  floor: `heightIn(min = minCardHeight)` where
  `minCardHeight = GalleryCardLayoutPolicy.minCardHeightDp(LocalDensity.current.fontScale).dp`
  (`GalleryView.kt:111`, `:106-110`). Floor = `(180f · fontScale).coerceIn(180f, 288f)`.
  This is a FLOOR, never a ceiling, so tall content grows the card — footer cannot
  be *clipped* by construction today.
- BUT the inner body `Column` (`GalleryView.kt:179-184`) is pure
  `fillMaxWidth().padding(14.dp)` — it does NOT participate in the floor, so when
  the card is forced taller than the content by `heightIn(min=...)`, the slack
  collects BELOW the footer (footer is not pinned, no explicit slack seat). The
  phase-188 PROMPT spec (`Column(weight(1f, fill=false))` + `heightIn(min=180.dp)`)
  is the intended mechanism: the preview block absorbs the floor's slack with
  `weight(1f, fill = false)` so the footer is pinned visible under every font scale.
- Phase-184 deliberately removed the old `weight(1f)` (fill=true default) because
  it was the "dead-band sponge" under the rigid ratio. Re-adding it as
  `weight(1f, fill = false)` with a CONTENT-DRIVEN floor is semantically different
  (fill=false never stretches the preview text; it only reserves the floor slack
  before the footer) — this must be documented to avoid a review regression.

## Risk #3 — Dark theme contrast: cards blend into near-black surfaces

**Status: NOT mitigated — currently no border.**

`Card(...)` (`GalleryView.kt:136-146`) uses `containerColor =
scheme.surfaceVariant.copy(alpha = 0.55f)` + 3dp elevation + a `primaryContainer`
wash, but has NO `border`. On dark themes `surfaceVariant` sits close to the
near-black `surface` and phase-187's paper fill (`scheme.surface.copy(alpha=0.7f)`)
flattens it further. The phase-188 spec asks for a visible
`BorderStroke(1.dp, outlineVariant.copy(alpha = 0.35f))` (or equivalent).
Verified: the clickable material3 1.3.1 `Card` overload accepts a `border` param
(`androidx.compose.material3.CardKt` — `Card(Function0<Unit>, ..., CardElevation,
BorderStroke, ...)`).

## Risk #4 — Tag chip overflow on multi-tag notes

**Status: LIVE — currently up to 3 chips in a W-RAPPING `FlowRow`.**

`GalleryView.kt:118-119`: `visibleTags = tags.take(3)`, `hiddenTagCount = size - 3`.
`GalleryView.kt:381`: the chips render in a `FlowRow` (single `horizontalArrangement`
op, no `maxLines`), so on a ~140dp card column at 1.3–1.5x font scale three (or
two long) chips WRAP onto a second line, crowding the `+N` badge / date row. The
phase-188 spec: at most 2 chips + `+N` badge, `maxLines=1` on the chip row so the
update timestamp stays visible.

## Root-cause anchors

| Risk | Current code | Problem |
|------|--------------|---------|
| 1 (no stroke raster) | `GalleryView.kt:127-134` preview = `extractedText` only; `:463-478` paper = card-size only | clean — pin it |
| 2 (large fonts) | `:111` `heightIn(min = minCardHeight)`; `:179-184` body Column has no floor / no slack seat | footer not pinned; missing `weight(1f, fill=false)` |
| 3 (dark contrast) | `:136-146` `Card` has no `border` | cards blend into near-black surfaces |
| 4 (tag overflow) | `:118-119` `take(3)`; `:381` `FlowRow` wraps | 3 chips wrap; badge/date squeezed |

## Plan

1. **Risk #1** — new `Phase188GalleryRobustnessTest` source pins: no `pointsJson` /
   stroke-load call in ANY gallery path; grid preview derives only from
   `NotePageEntity` metadata fields.
2. **Risk #2** — extend pure-JVM `GalleryCardLayoutPolicy` with the large-font
   bounds decision (`measuredCardHeightDp` = content-driven max with the floor;
   `footerAlwaysFits` invariant; line-budget constants). In `GalleryView.kt` give
   the body `Column` the same `heightIn(min = minCardHeight)` floor and wrap the
   preview/placeholder block in `weight(1f, fill = false)` so the floor's slack
   seats between preview and footer — footer pinned visible at 1.3–1.5x.
3. **Risk #3** — add `GALLERY_CARD_BORDER_WIDTH_DP = 1f` + `GALLERY_CARD_BORDER_ALPHA = 0.35f`
   to the policy and set `border = BorderStroke(width, scheme.outlineVariant.copy(alpha = ALPHA))`
   on the `Card`.
4. **Risk #4** — new pure-JVM `services/GalleryTagRowPolicy.kt`: `MAX_VISIBLE_TAGS = 2`,
   `parseTags`/`visibleChips`/`hiddenChipCount` (+`N`), chip text formatting.
   Replace the wrapping `FlowRow` with a single-line `Row` whose chips are
   `weight(1f, fill = false)` + `maxLines = 1`/Ellipsis and whose `+N` badge is
   always rendered last (it is measured before weighted children, so it can never
   be pushed out). Remove the now-unused `ExperimentalLayoutApi` opt-in.
5. Tests: `GalleryTagRowPolicyTest` (pure JVM) + `Phase188GalleryRobustnessTest`
   (source pins) + policy-layout/border assertions.
6. Regression proof: `gradle assembleDebug` + `gradle testDebugUnitTest`
   (1 pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure, untouched).