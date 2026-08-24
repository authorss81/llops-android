# Phase 203 — Bake Symmetry Mirrors at Capture Time: Toggling Never Rewrites History

**Date:** 2026-08-24 · **Type:** BUG (user report) · **Status:** DONE

> User report: "If I disable mirror it deletes the previously mirrored strokes; if I
> enable it, it creates symmetric strokes of pre-existing strokes."

## 1. Root cause (verified)

`drawCompositedLayersStrokes` re-drew EVERY committed stroke a SECOND time, mirrored
via `SymmetryHelper.mirrorPoint`, whenever `symmetryMode != OFF`
(pre-fix `drawStrokeWithSymmetry`, old `AnnotationCanvas.kt:3564-3589`). Storage was
never touched — the whole flip-flop was this global render pass:

- enable symmetry → retroactive mirror copies of all old ink appeared;
- disable symmetry → those copies vanished (user-perceived "deletion").

The toggle handler itself was already state-only and correct.

## 2. Fix

### 2.1 Pure-JVM policy — `services/SymmetryCommitPolicy.kt` (new)

- `shouldBakeMirror(mode, tool)` (`SymmetryCommitPolicy.kt:37`) — `mode != OFF && tool != TEXT`.
- `bakedTwin(stroke, mode, centerX, centerY)` (`SymmetryCommitPolicy.kt:54`) — mirrors
  `points`/`start`/`end` via `SymmetryHelper.mirrorPoint`, fresh UUID, every other
  attribute copied unchanged (tool/colorInt/width/filled/text/pdfPage/timestampMs/
  isAdvanced/layerId/colorMode/colorSeed/gradientToColorInt); per-point pressure/
  tilt/timestamp preserved by mirroring each `PointF` through `PointF.copy`.
  No Android imports (pinned by test).

### 2.2 Capture-time twin baking — `ui/components/AnnotationCanvas.kt`

Freehand/shape commit path (the only stroke-producing gesture path):

- `AnnotationCanvas.kt:1380-1387` — BEFORE the background build launches, the mode is
  frozen (`commitSymmetryMode`) and the axis center is resolved ONCE on the UI thread:
  `symmetryCenterFor(size.width.toFloat(), calculatePageYOffset(targetPage))` — the
  EXACT same resolver + page anchor the live preview used for that gesture
  (`LiveStrokePreview` calls `symmetryCenterResolver(size.width, pageTopYResolver())`,
  where `pageTopYResolver = { calculatePageYOffset(activeTargetPage) }`). World space,
  same coordinate space as `candidateStroke.points`, so the twin lands inside its own
  page slab on every page ≥ 1 (the phase-202 world-centre contract now applies to baking).
- `AnnotationCanvas.kt:1436-1449` — AFTER shape-snap/RDP finalizes `newStroke`, the
  twin is baked FROM THE FINALIZED geometry and ORIGINAL + TWIN are added together in
  ONE update: `activeStrokeList.addAll(commitBatch)` → single `onStrokesChanged(...)`.
  `EditorScreen.handleStrokesChange` pushes exactly one undo entry per call
  (`EditorScreen.kt:831-839`), so **one undo removes both twins at once**, and the
  autosave snapshot persists both rows.
- The TEXT dialog commit path (`AnnotationCanvas.kt` ~1745) adds its own row directly;
  `shouldBakeMirror(TEXT) == false` keeps text unbaked (text was never mirrored).

### 2.3 Renderer honesty — committed strokes render exactly once

`drawStrokeWithSymmetry` is GONE. Split into:

- `drawCommittedStrokeOnce(stroke, offsetY)` — `AnnotationCanvas.kt:3592`, one
  `drawSingleStroke`, no mirror. Used by ALL committed loops (5 call sites:
  no-layers cache `:3664`, no-layers direct `:3676`, layer cache `:3793`, layer
  normal `:3819`, layer saveLayer `:3835`).
- `drawLivePreviewWithSymmetry(stroke, offsetY, sMode, …)` — `AnnotationCanvas.kt:3598`,
  keeps the view-time mirror for the LIVE in-progress preview only (classic overlay +
  wet preview), so the user still sees the symmetric effect while drawing. 5 preview
  call sites updated (`:3672, :3679, :3811, :3822, :3838`).

`mirrorPoint` inside AnnotationCanvas is now reachable ONLY from those two live-preview
sites (4 occurrences, all after `private fun LiveStrokePreview(`) plus the commit-site
policy bake — pinned by source tests.

### 2.4 Eraser simplification — plain deletion covers both copies

With real twin rows on disk, per-stroke hit-testing finds each copy independently:

- `erasesStroke` lambda (`AnnotationCanvas.kt:1106-1108`) — view-time mirror-the-
  query-point special-case REMOVED; always plain `strokeContainsPoint(stroke, offset)`.
  Erasing a copy deletes exactly that row; **the other twin remains** (independent rows).
  PARTIAL-mode segmentation composes identically — twins segment like any other strokes.
- STROKE-eraser cursor highlight in `LiveStrokePreview` (`AnnotationCanvas.kt:2963`) —
  same simplification; the highlight now predicts exactly the row(s) the eraser deletes.
- Note: `StrokeSegmenter.hitStrokeAt` retains opt-in mirror params but has ZERO
  production callers (test-only utility since phase-124); left untouched deliberately.

### 2.5 Toggle handler — verified state-only, no change needed

`EditorScreen.kt:2582-2588` writes local `symmetryMode` + `viewModel.settings.symmetryModeKey`
(+ an informational snackbar about GPU wet-mix fallback). It never touches strokes/undo.
Initial read stays `SymmetryMode.fromSettingKey(viewModel.settings.symmetryModeKey)`
(`EditorScreen.kt:588`). Pinned by tests.

## 3. Behavior-contract walkthrough

| Step | Result | Mechanism |
|---|---|---|
| Draw 1 stroke with symmetry OFF → enable | canvas UNCHANGED | committed rows render once (`drawCommittedStrokeOnce`); no retroactive mirror pass exists |
| Draw 1 stroke ON → disable | BOTH strokes remain | original + twin are two persisted rows; renderer ignores mode for committed ink |
| Subsequent strokes while disabled | NO further twins | `shouldBakeMirror(OFF, _) == false`; nothing else consumes the mode |
| Undo after an ON-stroke | removes BOTH twins at once | single `onStrokesChanged` batch → one undo-stack entry |
| Erase either twin while enabled/disabled | only that row is deleted | plain hit-test over independent rows |

## 4. Tests (all green)

New:

- `app/src/test/java/com/authorss81/noteflow/SymmetryCommitPolicyTest.kt` (12):
  twin geometry == exact mirror for all modes (points/start/end); concrete-center
  values; pressure/tilt/timestamp survival; TEXT excluded; OFF excluded for every
  tool; every non-text tool included; FROZEN-center semantics (different centers ⇒
  different twins, each matching its own manual mirror); page-1 world-center twin
  lands inside its page slab; twin id ≠ source id and unique across bakes; full
  attribute round-trip; mirror involution (double bake returns source geometry);
  shape strokes bake anchors.
- `app/src/test/java/com/authorss81/noteflow/Phase203SymmetryCaptureBakeTest.kt` (12)
  — source pins: `drawStrokeWithSymmetry` count == 0; committed helper draws exactly
  once with no mirrorPoint; 5 committed call sites on the single-pass helper; preview
  helper keeps the OFF/TEXT gate + mirror; all `mirrorPoint` references inside
  AnnotationCanvas occur only after the LiveStrokePreview region (== 4, preview-only);
  freeze-before-launch ordering with the exact frozen-center expression; batch
  ordering twin→addAll→single `onStrokesChanged`; no bare freehand add resurrects;
  TEXT dialog region contains no baking; toggle handler writes only state+settings
  (no strokes/onStrokesChanged/undoStack/redoStack); initial read from settings;
  eraser lambda + highlight contain no mirror; policy purity (no android/androidx imports).

Updated (mechanism changed, invariant preserved):

- `Phase202BugFixBatchTest` — mirror pin rewritten: the view-time committed-stroke
  call forms must never resurrect; capture bake must freeze the WORLD centre via
  `calculatePageYOffset(targetPage)`; paginated caller still resolves the per-page
  world centre for the LIVE preview.
- `Phase198LiveStrokeIsolationTest` — eraser-highlight pin moved to the plain
  hit-test form (+ anti-resurrection pin on the old mirror expression).
- `SymmetryHelperTest` — comment block updated to describe the capture-time bake
  contract the math still protects (tests themselves unchanged).

## 5. Verification

- `gradle assembleDebug` — **BUILD SUCCESSFUL**.
- `gradle testDebugUnitTest` — **2742 tests, 3 failed / 2739 green**; all three
  failures reproduced on CLEAN HEAD immediately before this phase's changes:
  - the documented pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure;
  - `PaparazziSmokeTest` ×2 (layoutlib env on this runner:
    `NoSuchElementException` / `UninitializedPropertyAccessException:
    sessionParamsBuilder`) — reproduced 2/2 on stashed clean HEAD.
- One additional intermittent failure surfaced during repeated verification runs:
  `Phase151MarkdownMainThreadPerfTest > typing simulation on a 5k-line note…`
  — the relative wall-clock assertion `t < tOld` comparing best-of-3 spans of
  ~10–15 ms. Flake-rate comparison with an identical protocol (`--rerun-tasks`,
  3 consecutive isolated runs): **failed 2/3 times on STASHED CLEAN HEAD** and
  1/3 with this phase's tree. The phase touches nothing it exercises
  (`MarkdownBlockTokenizer` / `HybridMarkdownEditor` untouched); AGENTS.md has
  documented it as a known timing flake since phase-177. Exempt per DoD.
- No Room schema change, no `.github/workflows/` edits, no new dependencies,
  base-APK-size rule intact.

## 6. Known scope notes

- Laser strokes gain expiring twins like any other tool (they were mirrored before);
  both rows expire independently within one 40 ms sweep tick — visually simultaneous.
- Wet tools under symmetry keep their pre-existing behavior (AGSL path is gated to
  `symmetryMode == OFF`; vector fallback renders), now with baked twins so disabling
  symmetry no longer hides half of what was painted.
- Seamless (non-divided) continuous mode mirrors about the computed world centre —
  a twin can extend the derived page count downward exactly as far as the mirrored
  ink the user already saw while drawing; bounded by `CanvasPageBudgetPolicy` as before.
