# Phase 203: Bake Symmetry Mirrors at Capture Time — Toggling Never Rewrites History [BUG]

**User report:** "If I disable mirror it deletes the previously mirrored strokes; if I enable it, it creates symmetric strokes of pre-existing strokes. Mirroring must take effect only on strokes generated AFTER turning it on, and turning it off must keep existing mirrored strokes — just stop generating new ones."

**Goal:** Make symmetry a true drawing aid. A stroke drawn while symmetry is ON persists TWO independent strokes (original + mirrored twin). Toggling symmetry ON/OFF afterwards NEVER changes, adds, or removes anything already on the canvas.

## Root cause (verified file:line)

- **Render-time mirror of ALL committed strokes**: `ui/components/AnnotationCanvas.kt:3275-3304` — `drawStrokeWithSymmetry()` draws every committed stroke a SECOND time, mirrored via `SymmetryHelper.mirrorPoint`, whenever `symmetryMode != OFF`. Consequence: enabling symmetry retroactively shows mirror copies of old strokes; disabling makes them vanish (user-perceived deletion). Nothing touches storage — the flip-flop is purely this global render pass.
- **Stroke commit site**: `AnnotationCanvas.kt:1352-1390` (candidate build → shape-snap/RDP → `activeStrokeList.add(newStroke)` → `onStrokesChanged(...)`). Continuous-mode equivalents near `:1133` and `:1684`; paginated draw call sites passing `symmetryMode`/center at `:1877-1879`, `:1942-1944`, `:2085-2087`.
- **Toggle handler** (state-only, correct today): `ui/screens/EditorScreen.kt:2582-2586` (+ `:588` initial read from `settings.symmetryModeKey`).
- **Erase-through-mirror special-casing** (view-time era): `AnnotationCanvas.kt:1073-1084` and `:2115-2121`.
- **Known related hazard**: the audit's multi-page symmetry bug (mirror center computed in LOCAL page coords vs WORLD stroke points on page >= 1) — assigned to phase-202. Capture-time baking MUST resolve coordinates in the SAME space the stroke points are stored in, or the twin lands wrong/off-page.

## Steps

1. **Capture-time twin baking**: at every stroke-commit path (paginated `:1352-1390`, continuous `:1133`/`:1684`), when `symmetryMode != OFF` AND `tool != StrokeTool.TEXT`: construct the mirrored twin (`SymmetryHelper.mirrorPoint` over `points`/`start`/`end`) using the EXACT center the live preview used for that gesture (freeze it at capture; mind pageTopY world-vs-local — the twin's points must be in the same coordinate space as `candidateStroke.points`), give it its own UUID + same layer/tool/color/width/colorMode, and add ORIGINAL + TWIN together in the single `onStrokesChanged` update (one undo step removes both).
2. **Renderer honesty**: committed strokes render exactly ONCE — remove the unconditional second mirrored pass for committed strokes in `drawStrokeWithSymmetry` (`:3279-3304`). KEEP the mirror for the LIVE in-progress preview stroke only (so the user sees the symmetric effect while drawing, before lift-off).
3. **Eraser simplification**: with real twin rows on disk, plain per-stroke hit-testing covers both copies. Remove/neuter the view-time mirror erase special-casing (`:1073-1084`, `:2115-2121`) so eraser + symmetry compose through plain stroke deletion; verify erasing either twin leaves the other (independent strokes) and document that in REPORT.md.
4. **Pure-JVM policy + tests**: new `services/SymmetryCommitPolicy.kt` — `shouldBakeMirror(mode, tool)`, `bakedTwin(stroke, mode, centerX, centerY)` (pure geometry, no Android imports). Tests: twin geometry == exact mirror of source points; TEXT excluded; OFF excluded; center frozen at capture values; twin id != source id; same layerId/tool/color/width round-trip.
5. **Regression proof**: unit/source-pin test that NO draw path applies a mirror transform to COMMITTED strokes anymore (grep-level pin: mirrorPoint reachable only from live-preview path + commit baking + policy), and that the toggle handler writes only settings state.

**Behavior contract to verify:** draw 1 stroke with symmetry OFF → enable → canvas unchanged; draw 1 stroke ON → disable → both strokes remain, no further twins appear on subsequent strokes; undo after an ON-stroke removes both twins at once.

## DoD

- `gradle assembleDebug` green; `gradle testDebugUnitTest` green (new SymmetryCommitPolicyTest + regression pins, 0 new failures; pre-existing documented flakes exempt).
- `workspace/phase-203/REPORT.md` with file:line evidence of: twin baking sites, removed committed-stroke render pass, eraser verification, and the behavior-contract walkthrough.
- No Room schema change, no `.github/workflows/` edits, base-APK-size rule intact, AGENTS.md hard rules respected.
