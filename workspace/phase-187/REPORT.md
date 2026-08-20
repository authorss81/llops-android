# Phase 187 — GalleryView: authentic notebook-paper look for ink-note cards

**Status: DONE** (2026-08-20) · commits on `main` pushed incrementally per the
WORKFLOW RULE: `13b8b6b` (step 1 inventory), `76c5cea` (step 2 paper texture),
`<step-3>` (regression proof + REPORT + docs + `.done`).

## Before

Ink canvas notes with no extracted OCR text rendered a **generic placeholder** —
a 44dp circular chip with the `Brush` icon tinted `scheme.outline@0.7` and the
label `"Ink & canvas page"` in `scheme.outline`, on the flat tonal
`surfaceVariant@0.55` card:

```
ui/components/GalleryView.kt (pre-change)
  :295-328 — else-branch of the preview: Box(84dp min) → Column →
      circle chip (Brush icon) + pageTypeLabel(page)
  :400-412 — pageTypeLabel: else -> "Ink & canvas page"   // honest? no OCR claim,
                                                        // but reads as an unpopulated stub
```

A reviewer/user looking at the home tab saw a blank card stub, not a drawing
page — the visual-review complaint this phase addresses.

## After

Every **ink page** (`InkCardPaperPolicy.isInkCanvasPage` = `sourceFileType`
not in {pdf,image,text} — matches `Entities.kt:39`) **without a text preview**
now renders an authentic notebook dot-grid paper across the card:

- **Texture**: new pure-JVM `services/InkCardPaperPolicy.kt` owns the honest
  label, the pattern alphas, and the **bounded** geometry. In
  `GalleryView.kt` a private `Modifier.notebookPaper(...)` `drawBehind`
  extension paints:
  - paper fill: `scheme.surface.copy(alpha = 0.7f)` (the prompt's paper tone)
  - dot grid: `scheme.outlineVariant.copy(alpha = 0.3f)` at `22dp` pitch
    (`GRID_SPACING_DP`), `1.5dp` dot radius (`DOT_RADIUS_DP`).
- **Small draw icon kept**: the 44dp circular chip still shows the `Brush`
  icon (the "small draw icon").
- **Honest label**: `InkCardPaperPolicy.HANDWRITTEN_LABEL` = **"Handwritten
  note"** — never claims OCR text exists.
- **Scope**: the paper applies ONLY when `isInkPage && preview.isEmpty()`; a
  PDF/image/text page with no preview keeps its own `pageTypeLabel` in the flat
  placeholder; any page WITH OCR preview keeps the text preview (preferred).

### Cheap to draw / no per-frame allocation

- Grid geometry comes from `InkCardPaperPolicy.gridColumns/gridRows`, both
  hard-capped `MAX_GRID_COLUMNS = 12`, `MAX_GRID_ROWS = 8` →
  **≤ 96 dots total** on ANY card size (test-pinned, incl. huge tablet cards).
- Pitches/colors are computed **once per composition** (`with(density){dp.toPx()}`
  is off the draw path) and captured in the `drawBehind` lambda — no per-frame
  `Color.copy()`, no list building; the loop bodies use only `DrawScope`
  primitives.
- **No stroke rasterization**: the texture is derived purely from card size +
  constants. `GalleryView.kt` contains ZERO `pointsJson` tokens and never calls
  the stroke repository (phase-188 risk #1, now source-pinned).

### Dark theme

The 0.7-alpha `scheme.surface` fill is layered over the card's
`surfaceVariant@0.55` container, so ~30% of the tonal base still shows through
and the card stays visibly lighter than the near-black surface; the existing
`primaryContainer@0.40` wash + 3dp elevation keep it distinct. Phase-188 adds
the explicit 1dp `outlineVariant@0.35` border on top of this.

## Files touched

| File | Change |
|---|---|
| `app/src/main/kotlin/com/authorss81/noteflow/services/InkCardPaperPolicy.kt` | NEW pure-JVM policy: label, alphas, pattern constants, bounded `gridColumns/gridRows/totalDots`, `isInkCanvasPage` |
| `app/src/main/kotlin/com/authorss81/noteflow/ui/components/GalleryView.kt` | Ink-page paper texture via `Modifier.notebookPaper` (`drawBehind`), precomputed px pitches/colors, honest label |
| `app/src/test/java/com/authorss81/noteflow/InkCardPaperPolicyTest.kt` | NEW (11 pure-JVM tests) |
| `app/src/test/java/com/authorss81/noteflow/Phase187GalleryInkPaperTest.kt` | NEW (4 source pins) |

## Tests

`InkCardPaperPolicyTest` (11): honest label; ink classification (null/pdf/image/
text/unknown); typical-phone grid (8 cols @ 504px/66px); huge-tablet column/row
capping == MAX; dot budget ≤ `MAX_DOT_COUNT` (96) across 100px→20kpx sizes;
degenerate failsafes (0/negative/NaN sizes, non-positive pitch stay bounded);
grid floor 1×1 for tiny cards; alpha/pattern constants == prompt spec.

`Phase187GalleryInkPaperTest` (4 source pins): policy-derived label/classification/
alphas (no inline literals); `drawBehind` + bounded `gridColumns/gridRows` used +
circle-chip retained; zero `pointsJson`/stroke calls in the grid item; paper
gated exactly on `isInkPage && preview.isEmpty()` (text preview preferred).

## Regression proof

- `gradle assembleDebug` — **BUILD SUCCESSFUL in 2m 33s** (no `e:` errors).
- `gradle testDebugUnitTest` — **2478 total, 1 failure** =
  the pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure
  (untouched, reproduced on prior clean phases; `WikiLinkParserCacheUnitTest`
  flaked once then passed — documented timing/concurrency flake that passes in
  isolation).

No schema change, no migration, no new dependencies, `.github/workflows/`
untouched, base-APK-size rule intact.