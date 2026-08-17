# Phase 123 — Immediate effect when selecting colour / layer / tool

**Status:** DONE — 2026-08-17
**Bug:** a new colour/layer/tool selection does NOT take effect for the very next stroke — the user had to switch pens (or perform another gesture) first.
**Verify model:** agent-simulated (`gradle testDebugUnitTest` 1721 app tests green, 0 failures, 0 skipped; `gradle :app:assembleDebug --rerun-tasks` 57/57 executed green).

## Summary

Traced the selection → apply path for all three headline dimensions and found **three real deferral roots**, all of the "stale snapshot / stale closure" family:

1. **Layer (the primary "switch pens" mechanism)** — the drawing `pointerInput` block in `AnnotationCanvas.kt` restarted on `currentTool`/`currentColor`/`currentWidth`/… but **not** on `activeLayerId`/`layers`. Compose only re-launches a `pointerInput` block when one of its *keys* changes, so after a layer switch (unlocked A → unlocked B) the stroke‑commit closure (`val actLayerId = activeLayerId` → `layerId = actLayerId`) kept **capturing A** for every subsequent stroke — until some *other* key (tool/colour/width) forced a restart. That is precisely "the new layer only takes effect after I switch pens". The live preview (which reads `activeLayerId` per frame in the draw pass) showed ink on the correct layer while the committed stroke landed on the old one.
2. **Text-tool colour** — `AnnotationCanvas.kt:247` `var textSelectedColorInt by remember { mutableIntStateOf(currentColor.toArgb()) }` is a **keyless `remember`** that snapshots the brush colour at FIRST composition, so a newly picked brush colour never reaches the NEXT text stroke.
3. **HSV sliders (advanced picker)** — `EditorScreen.kt` (three sliders) called `onColorSelect(derivedColor)` where `derivedColor` is `remember(h,s,v)` from the **previous** composition, so the applied colour always lagged one slider event behind and the FINAL slider position never landed on the stroke.

The plain-swatch SOLID pick, the eyedropper, presets, the mode chips and the tool/eraser-type pickers were verified LIVE already (`currentColor`/`currentTool`/`eraserMode` are pointerInput keys; `isSelected = currentColor.toArgb() == swatch.argb` recalc on recomposition) — no change needed there, and this is documented honestly below.

Fixes: add `activeLayerId, layers` to the gesture-block key list; key the text-colour `remember` on `currentColor`; make each HSV slider convert its **just-changed** channel inline.

## What was implemented (file:line evidence)

### Fix 1 — layer switch applies to the very next stroke (`AnnotationCanvas.kt`)
- `AnnotationCanvas.kt:628-634` — the drawing gesture block:
  - BEFORE: `.pointerInput(currentTool, currentColor, currentWidth, pdfPageFilter, isContinuousMode, activeRawBitmapMap, isLayerLocked, symmetryMode, stabilizerEnabled, eraserMode)`
  - AFTER: `…, eraserMode, activeLayerId, layers)` (with a Phase-123 KDoc comment explaining the failure mode). The commit closure (`AnnotationCanvas.kt:855-877`) is unchanged — `val actLayerId = activeLayerId` → `layerId = actLayerId ?: "layer_default"` — but now the closure is recreated on every layer switch because the layer is a restart key.

### Fix 2 — text-tool colour follows the brush colour (`AnnotationCanvas.kt`)
- `AnnotationCanvas.kt:247`:
  - BEFORE: `var textSelectedColorInt by remember { mutableIntStateOf(currentColor.toArgb()) }`
  - AFTER: `var textSelectedColorInt by remember(currentColor) { mutableIntStateOf(currentColor.toArgb()) }`
  - The text commit (`AnnotationCanvas.kt:1155`) now stamps the synced colour. The six in-dialog swatches (`:1128-1137`) still work as an explicit per-text override (unchanged `textSelectedColorInt = color.toArgb()`).

### Fix 3 — HSV sliders apply the just-moved channel (`EditorScreen.kt`)
- `EditorScreen.kt:3007-3016` (Hue): `onValueChange = { h = it; onColorSelect(Color(android.graphics.Color.HSVToColor(floatArrayOf(it, s, v)))) }` — was `onColorSelect(derivedColor)`.
- `EditorScreen.kt:3022-3031` (Saturation): `floatArrayOf(h, it, v)` — was `onColorSelect(derivedColor)`.
- `EditorScreen.kt:3037-3046` (Value/Brightness): `floatArrayOf(h, s, it)` — was `onColorSelect(derivedColor)`.
- `derivedColor` remains for the "Save to Custom Swatches" button (`EditorScreen.kt:3051-3052`) — that is the correct, settled value.

## Verification

- New `app/src/test/java/com/authorss81/noteflow/Phase123ImmediateSelectionTest.kt` (**12 pure-JVM tests**):
  - **Immediate-effect model** (`GestureCommitModel` = faithful model of the Compose `pointerInput` restart-key semantics: the block is relaunched only when one of its keys changes, and the commit closure re-captures the live selection at relaunch):
    - select colour → the very next commit carries the colour;
    - switch layer → the very next commit lands on the new layer;
    - switch tool → the very next commit is that tool;
    - a full select-layer → pen → colour sequence applies each choice to its very next stroke, and a combined colour+layer+tool change lands all three on one stroke.
  - **Pre-fix reproducer**: with `layer` absent from the keys, switching layer leaves the next stroke on the OLD layer and the pen switch is what heals it — asserting the exact reported symptom and why Fix 1 removes it.
  - **HSV model** (`HsvSliderModel` + a pure-JVM reference HSV→ARGB): pre-fix applied the previous composition's colour (the final position never lands); post-fix the just-moved channel is applied immediately.
  - **Source-level wiring pins**: the `.pointerInput(…)` line contains `activeLayerId` AND `layers`; the commit still reads `val actLayerId = activeLayerId` / `layerId = actLayerId ?: "layer_default"`; `textSelectedColorInt` is keyed on `currentColor`; the HSV slider region has exactly 3 inline `onColorSelect(Color(android.graphics.Color.HSVToColor(…)))` calls, zero `onColorSelect(derivedColor)`, and each triple passes the changed channel first.
- `gradle testDebugUnitTest` — **1721 app tests, 0 failures, 0 skipped** (app 1721 + plugin modules; phase-122 baseline was 1707 app tests).
- `gradle :app:assembleDebug --rerun-tasks` — **BUILD SUCCESSFUL** (57/57 tasks executed, forced clean). (A first incremental `assembleDebug` hit the documented recurring transient packaging failure — the forced rerun is fully green; the same failure has been seen and documented in earlier phases and does not reproduce.)

## Design decisions

- **Fix 1 keyed `activeLayerId` + `layers`, not `rememberUpdatedState`**: the codebase's existing pattern for this block is restart-keys (every other selection dimension is a key); `rememberUpdatedState`-wrapping every captured read would be a bigger, different-pattern change. Restarting mid-drag is already the (accepted) behaviour of a tool/colour/width switch, and a layer switch is a discrete user action outside the drag, so restart-on-layer-change adds no new hazard.
- **Text colour keys on `currentColor`**: selecting a new brush colour re-syncs `textSelectedColorInt`, so the next text stroke uses the newly picked colour. An explicit in-dialog swatch choice still wins for that dialog session (the remember only re-initialises when `currentColor` changes; the swatch tap writes the override directly). This preserves the six-colour quick palette while closing the stale-snapshot bug.
- **HSV sliders convert inline**: each `onValueChange` computes the colour from the just-moved channel + the two untouched channels (the pre-fix `derivedColor` from the previous composition is exactly the one-event lag). `currentColor` still re-keys the `<h,s,v>` remembers, so subsequent events stay consistent.

## Honesty: what was already live vs what this phase changed

The plain-swatch colour pick, eyedropper, brush presets, colour-mode chips, tool picker, eraser-type chips and width picker were **already immediate** in the current tree (verified during the trace):
- `currentColor` and `currentTool`/`eraserMode` are restart keys of the drawing `pointerInput` block; the live preview strokes are rebuilt per draw frame from `currentColor`/`currentTool`/`currentColorMode`.
- Picker highlights recompute on recomposition (`isSelected = currentColor.toArgb() == color.toArgb()` at `EditorScreen.kt:3094/3114`; tool items `val selected = tool == currentTool` at `EditorScreen.kt:2747/2818`; layer cards highlight against `activeLayerId`).

The three genuine deferral/defect paths — the missing layer key, the keyless text-colour snapshot, and the one-event-behind HSV sliders — are exactly what this phase fixed, with the behavioural model, the pre-fix reproducer and source pins covering all three.

## Constraints honoured

No DB schema change · `.github/workflows/` untouched · no new dependencies · no keys/decrypted content logged · `allowBackup=false`, `ClipboardGuard`, FLAG_SECURE untouched. Pure-JVM tests reuse the existing `app/src/test` layout and the repo-standard `repoRoot()` helper.