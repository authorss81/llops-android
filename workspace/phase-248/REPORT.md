# Phase 248 — Minimap inside its own box + ink bar reserves Scaffold topBar

## Goal
Two layout defects from `AUDIT_2026-08-30.md` (verified at commit `2709453`):
1. **Minimap anchored to the full device screen** (`LocalConfiguration.screenWidthDp/screenHeightDp`) instead of its own canvas container bounds — off-screen in split-screen / freeform / double-pane Expanded width.
2. **Floating ink bar clamps to `WindowInsets.safeDrawing.top` only** (~status-bar height) instead of the Scaffold `topBar` — a drag to the top leaves the bar's pointer region over the app bar's title row, intercepting rename/back taps.

Both fixed below; verified green on the full suite + debug/release builds + lint. No schema change, no new deps.

## Bug 1 — Minimap anchors to the canvas PANE, not the device screen

### Evidence
`AnnotationCanvas.kt` minimap block previously read `LocalConfiguration.current.screenWidthDp/screenHeightDp` and called `defaultAnchorBottomEnd(screenW=screenW, screenH=screenH, …)`. On a 1280dp-wide Expanded double-pane (`MainActivity.kt:758-892`) the right editor pane is `weight(1.6f)` ≈ 600dp, so `defaultAnchorBottomEnd(1280, 800, 120, 140, 16) = (1144, 644)` put the minimap ~544dp past the pane's right edge.

### Fix
- **Source** `app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt`:
  - The canvas already exposes the real box via the surrounding `BoxWithConstraints` (line 1291). The minimap block now binds to those:
    - `val paneW = canvasBoxW` / `val paneH = canvasBoxH` (`:3393-3394`) — pane-local names so the bindings can never be read as window dims (legacy `screenW = paneW` naming hazard removed).
    - `defaultAnchorBottomEnd(screenW = paneW, screenH = paneH, …)` (`:3410-3411`).
    - minimap drag `pointerInput(minimapDraggable, minimapWidthPx, minimapHeightPx, paneW, paneH)` (`:3440`) and its `constrainWithinSafeArea(…, paneW, paneH, …)` (`:3451`).
    - Every other `screenW`/`screenH` reference **inside** the minimap block (previously aliases of the minimap's locals) became `paneW`/`paneH`: zoom-viewport math (`:3512-3513`, `:3518`), zoom-to-fit (`:3614`, `:3621`), both 2D-map `pointerInput` key lists + `computeCanvasWorld(paneW)` (`:3654-3655`, `:3676-3677`, `:3695`), both map pan/pinch handlers (`:3667-3668`, `:3688-3689`), and the viewport-frame box (`:3769-3770`). `grep` confirms no bare `screenW`/`screenH` reference survives outside named callee parameters (`computeCanvasWorld`/`symmetryCenterFor`, `:623`/`:700`/`:3903`).
  - `WindowInsets.safeDrawing` usage is unchanged — it is window-space, but combined with pane-local coords it now reserves only the inset portion of the visible pane (correct).
- **No policy change**: `MinimapGeometryPolicy.defaultAnchorBottomEnd` already takes the anchor in the caller's coordinate frame — the bug was the frame (window vs. pane), not the math.

## Bug 2 — Ink bar reserves the Scaffold topBar band

### Evidence
`EditorScreen.kt` `FloatingToolDock` clamped its drag with `constrainWithinSafeArea(x, y, …, top = topInsetPx, …)` where `topInsetPx = WindowInsets.safeDrawing.getTop()` — the status-bar height, not the app bar. `DockPosturePolicy.horizontalDefaultAnchor` / `verticalDefaultAnchor` had no topBar reservation either, so both the drag clamp AND the resting anchor could sit the bar over the topBar's back/rename strip.

### Fix
- **`app/src/main/kotlin/com/authorss81/noteflow/services/FloatingWidgetDragPolicy.kt`**:
  - `constrainWithinSafeArea` gains `topReservedPx: Float = 0f` (last param, default 0 = backward compatible) (`:94`); the effective top clamp becomes `top = top + topReservedPx` (`:100`). KDoc updated (`:77-82`).
- **`app/src/main/kotlin/com/authorss81/noteflow/services/DockPosturePolicy.kt`**:
  - `horizontalDefaultAnchor` / `verticalDefaultAnchor` gain `topReservedPx: Float = 0f` (`:47`, `:71`); both y-results clamp to `coerceAtLeast(topReservedPx)` (`:55`, `:79`). The two default margins were already positional constants; they are now named constants `DEFAULT_BOTTOM_MARGIN_PX = 20f` / `DEFAULT_END_MARGIN_PX = 20f` (`:83`, `:86`).
- **`app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt`**:
  - `FloatingToolDock` receives the Scaffold topBar's measured content height: `var topBarHeightPx by remember { mutableFloatStateOf(0f) }` (`:186`), the topBar `Surface` reports it via `.onSizeChanged { topBarHeightPx = it.height.toFloat() }` (`:1751`), and the call site passes `topBarHeightPx` (`:2758`).
  - Inside `FloatingToolDock` the reserved band is derived as `topReservedPx = (topBarHeightPx - topInsetPx).coerceAtLeast(0f)` (`:3489` — the topBar is `statusBarsPadding() + 56dp` Row, so subtracting the inset yields the pure content height; the clamp's `top` already = `safeDrawing.top`). The reservation feeds BOTH the resting anchors (`horizontalDefaultAnchor`/`verticalDefaultAnchor`, `:3491-3499`) and the drag clamp (`constrainWithinSafeArea(…, topReservedPx = topReservedPx)`, `:3587`). A drag to the top therefore stops at `statusBar + topBar` = the app bar's bottom edge; rest-time anchors are clamped below it too.

## Tests
New `app/src/test/java/com/authorss81/noteflow/Phase248MinimapPaneSizeTest.kt` (11, pure JVM):

Policy matchers:
- `defaultAnchorBottomEnd(600, 800, 120, 140, 16)` → `(464, 644)` and **not** the window-wide `(1144, 644)`; pane-smaller-than-map stays in pane.
- `constrainWithinSafeArea` with `topReservedPx = 56`: `y=10` → `56`, `y=80` → `80` (reserve above the insets).
- `constrainWithinSafeArea` composes inset + reserve: `top=48, topReservedPx=56` → effective `104`.
- no-reserve calls reproduce the Phase-129 snapshots byte-for-byte (backward-compatible contract).
- oversized-widget + reserve anchors at the reserved safe top (mirrors the Phase-129 tolerant case).
- `horizontalDefaultAnchor`/`verticalDefaultAnchor` clamp at the reserved line on short windows; bottom-centre pill and default-margin behavior unchanged; `DEFAULT_BOTTOM_MARGIN_PX`/`DEFAULT_END_MARGIN_PX == 20f`.

Source pins (edit-guards so the rebound can't silently return):
- `AnnotationCanvas.kt` minimap block contains NO `LocalConfiguration.current.screenWidthDp`/`screenHeightDp` anywhere; the minimap drag `pointerInput` keys are `paneW, paneH` (never `screenW, screenH`); the drag clamp passes `paneW, paneH, minimapWidthPx, minimapHeightPx`.
- `EditorScreen.kt` derives `topReservedPx = (topBarHeightPx - topInsetPx).coerceAtLeast(0f)`, feeds it with `topReservedPx = topReservedPx` in the `constrainWithinSafeArea` call, and measures the topBar via `onSizeChanged`.

## Verification (all Linux/CI, system `gradle`)
- `gradle :app:testDebugUnitTest` — **3594 tests / 0 failures / 0 errors** (baseline 3583 + 11; `Phase129InkBarMinimapPolicyTest`, `Phase244InkBarDrawingPolicyTest`, `Phase166LayoutOverflowTest` all green).
  - Note: the `Phase148UiFailureTextScrubTest` UNC-path flake historically seen in some runs was green this run.
- `gradle :app:assembleDebug` — green (all 4 ABI + universal APKs produced).
- `gradle :app:assembleRelease` — green (R8 + shrinkResources, signed; fails-closed keystore rule untouched).
- `gradle :app:lintDebug` — BUILD SUCCESSFUL, **0 errors**, 0 `Error`/`Fatal` issues in `lint-results-debug.xml`. One transient in-co-run `lintDebug` OOM was observed while `assembleRelease` (R8) + lint ran concurrently against the same heap; the identical lint task passed cleanly in isolation (no issue reported during the failed run either). No lint *finding* at any point.
- Manual items from the PROMPT DoD (1280dp double-pane minimap visibility / rename-tap not intercepted / freeform) are **not executable on the CI runner**; the pane-bound anchor math + source pins above are the automated proxy for all three.

## Constraints honored
- No schema change / migration; no new dependencies; `.github/workflows/` untouched; base-APK-size rule intact.
- `topReservedPx` defaults to `0f` in all three APIs — every existing call site is source-compatible.
- No canvas drawing / gesture logic touched; `.editorconfig` final-newline rule honored on every file touched.
