# Phase 198: Isolate Active-Stroke Recomposition + Viewport Culling Polish [PERF 2.1+2.5]

**Goal:** Fix whole-canvas recomposition storm on every pen sample + viewport culling.

**Context:** `AnnotationCanvas.kt:324` `mutableStateListOf<PointF> activePoints` mutated per move invalidates entire 4535-line canvas + `EditorScreen`. `AnnotationCanvas.kt:1766` already has slab culling.

**Steps:**
1. Extract `LiveStrokePreview` composable that only depends on `activePoints` via `snapshotFlow { activePoints.size }` or `derivedStateOf { activePoints.toList() }` + isolated `Modifier.drawWithContent`. Verify with Layout Inspector recomposition counts.
2. Confirm committed-strokes layer (`LayerBitmapLruCache`) not re-rasterized while live stroke draws. Fix `LaunchedEffect(strokes, layers)` incremental.
3. Verify viewport culling `AnnotationCanvas.kt:1766-1782` skips off-screen pages (O(visiblePages) not O(total)).

**DoD:** Recomposition counts: live stroke does not recompose committed layers, `gradle assembleDebug` green, REPORT.md with counts.
