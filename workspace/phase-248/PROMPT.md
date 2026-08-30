# Phase 248 — Minimap inside its own box + ink bar reserves Scaffold topBar

## Goal
Two CRITICAL/HIGH layout defects from `AUDIT_2026-08-30.md` and the 3/5 audit:
1. **Minimap is positioned using `LocalConfiguration.screenWidthDp/screenHeightDp` (the full device screen) instead of its own container's measured size** — off-screen in split-screen / multi-window / freeform / double-pane Expanded width. Fix: use the parent `BoxWithConstraints` bounds, matching the ink bar.
2. **Ink bar's `FloatingToolDock` `constrainWithinSafeArea` clamps to `WindowInsets.safeDrawing.top` only** (≈ status-bar height), not the Scaffold's `topBar` height — so when the user drags the bar to the top, its pointer region overlaps the top app bar's title row, intercepting rename / back taps. Fix: pass the topBar reserved height into the constraint clamp.

## Context — verified at `2709453`

### Bug 1: Minimap off-screen
- `app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt:3359-3362` reads `LocalConfiguration.current.screenWidthDp/screenHeightDp` and `defaultAnchorBottomEnd(screenW=screenW, screenH=screenH, ...)` — but the parent `Box(Modifier.fillMaxSize())` at line 2766 is itself inside `Box(Modifier.fillMaxSize().padding(padding))` at `EditorScreen.kt:2453` where `padding` is the Scaffold's topBar+bottomBar+systemBars. In double-pane (Expanded width, `MainActivity.kt:758-892`): editor pane is `weight(1.6f)` of a Row; on a 1280dp tablet, right pane ≈ 600dp. `defaultAnchorBottomEnd(1280, 800, 120, 140, 16) = (1144, 644)`. Applied inside the 600dp pane → minimap at x=1144dp = 544dp past the right edge.
- The `BoxWithConstraints` at `AnnotationCanvas.kt:1289` already exists and exposes the actual canvas node size; `minimap`'s parent Box is the canvas itself. Use the canvas's own `maxWidth/maxHeight` (or pass it from the surrounding `BoxWithConstraints` in EditorScreen:2712).

### Bug 2: Ink bar overlaps topBar
- `EditorScreen.kt:3521-3575` `pointerInput { ... FloatingWidgetDragPolicy.constrainWithinSafeArea(...) }` clamps to `topInsetPx` which is `WindowInsets.safeDrawing.getTop()` — the status-bar height, not the top app bar height. `DockPosturePolicy.horizontalDefaultAnchor` / `verticalDefaultAnchor` (`services/DockPosturePolicy.kt:39-71`) centre vertically with no topBar reservation either.
- `FloatingWidgetDragPolicy.constrainWithinSafeArea` (`services/FloatingWidgetDragPolicy.kt:77-100`) needs a new `topReservedPx` parameter (default 0 to keep the contract backward-compatible) — when set, the bar is clamped to `>= topReservedPx` regardless of `topInsetPx`.

## Files to change

### 1. `app/src/main/kotlin/com/authorss81/noteflow/services/FloatingWidgetDragPolicy.kt`
- Add `topReservedPx: Float = 0f` to `constrainWithinSafeArea(x, y, ..., topInsetPx, bottomInsetPx, startInsetPx, endInsetPx, topReservedPx)`. The clamp changes from `t = topInsetPx` to `t = (topInsetPx + topReservedPx).coerceAtLeast(0f)`.

### 2. `app/src/main/kotlin/com/authorss81/noteflow/services/DockPosturePolicy.kt`
- `horizontalDefaultAnchor` and `verticalDefaultAnchor` gain an optional `topReservedPx` (default 0). When > 0, the default anchor's y is shifted down by `topReservedPx` (i.e. the resting position is below the top app bar).

### 3. `app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt`
- Wrap the minimap block (currently `if (showMinimap) { ... }` at line 3358) in a `BoxWithConstraints` that captures `maxWidth/maxHeight` for the canvas. Replace `screenW/screenH` reads (line 3359-3362) with `with(mapDensity) { maxWidth.toPx() } / maxHeight.toPx()`. Apply the same correction to the `pointerInput` keys (line 3408) and the `constrainWithinSafeArea` call (line 3416-3421).
- `WindowInsets.safeDrawing` insets remain correct (they're the visible region of the window — but combined with the canvas-pane-local coords, the constraint now reserves only the inset portion of the canvas pane, not the full window).

### 4. `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt`
- Compute the Scaffold's `topBarHeight` once in the same `BoxWithConstraints` at line 2712 (or read `LocalDensity` + measure the topBar's intrinsic height). Pass `topReservedPx = topBarHeight.toPx()` into:
  - `FloatingWidgetDragPolicy.constrainWithinSafeArea(...)` (line 3530-3533)
  - `DockPosturePolicy.horizontalDefaultAnchor(..., topReservedPx)` / `verticalDefaultAnchor(..., topReservedPx)` (line 3460-3464)
- The min edge clamp becomes `topBarHeight + statusBarHeight`, so a user drag to the top stops at the topBar's bottom edge, not at the status-bar baseline.

## New tests

### `app/src/test/java/com/authorss81/noteflow/Phase248MinimapPaneSizeTest.kt` (pure JVM, 5+ tests)
- `MinimapGeometryPolicy.defaultAnchorBottomEnd(paneW=600, paneH=800, mapW=120, mapH=140, marginPx=16)` returns `(600-120-16, 800-140-16) = (464, 644)` — NOT the full-window `(1144, 644)` that the old code would produce if `screenW=1280`.
- Pin `FloatingWidgetDragPolicy.constrainWithinSafeArea(...)` with `topReservedPx=56` (a 56dp topBar): a drag to y=10 clamps to y=56; a drag to y=80 stays at y=80.
- Pin `DockPosturePolicy.verticalDefaultAnchor(width, height, dockW, dockH, topReservedPx=56)` returns `((sh - dh) / 2).coerceAtLeast(56)` (not raw `(sh-dh)/2`).
- Pin `AnnotationCanvas.kt` source: the minimap block does NOT call `LocalConfiguration.current.screenWidthDp` / `screenHeightDp`; the `pointerInput` keys are NOT `screenW, screenH`.
- Pin `EditorScreen.kt` source: the call to `constrainWithinSafeArea` includes a `topReservedPx` argument derived from the Scaffold's topBar height.

## Constraints
- No schema change
- No new dependencies
- No `.github/workflows/` edits
- Backward-compatible: `topReservedPx` defaults to 0 in both `constrainWithinSafeArea` and the DockPosturePolicy anchors; existing call sites without the new arg are unchanged
- `grainDrawAlpha(50)` style anchoring preserved — pre-247 visual contracts unchanged
- No DB, no `verification-metadata.xml` change unless a new test dep requires it (none expected)

## DoD
- `gradle :app:testDebugUnitTest` 3556+ green
- `gradle :app:assembleDebug` + `assembleRelease` green
- `gradle :app:lintDebug` 0 errors
- Manual: on a 1280dp-wide Expanded double-pane, the minimap appears inside the right editor pane (not past the right edge of the screen)
- Manual: on a phone with the top app bar visible, dragging the ink bar to the top stops at the bottom of the top app bar; the page title's rename tap is no longer intercepted
- Manual: on split-screen / freeform, the minimap stays inside the visible canvas box
- `workspace/phase-248/REPORT.md` with file:line evidence
