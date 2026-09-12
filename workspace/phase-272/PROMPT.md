# Phase 272 — Pan fling with exponential decay (port from AI Studio)

## Goal
Panning today stops dead on finger lift. Port AI Studio's fling: on PAN/black-space drag end, if release velocity exceeds 80px/s, keep panning with `exponentialDecay(frictionMultiplier=0.8f)` per axis — fast scroll, slow settle at the end. Cancel the fling on any new gesture (drag start, second finger, drag cancel).

## Evidence (verified on main HEAD `3710a3a`; reference: AI Studio `AnnotationCanvas.kt:340-341,1460,1863-1864,2066,2336-2369,2654`)
- Main `AnnotationCanvas.kt:329` has `val coroutineScope = rememberCoroutineScope()` + `Job`/`launch` imports (`:102-104`) but NO `velocityTracker`/`flingJob` state — grep `fling|VelocityTracker` in main hits only `MotionPolicy`/`GraphSearchMatchPolicy` comments.
- Main `AnnotationCanvas.kt:2042-2045` (`if (isPanningBlackSpace || currentTool == StrokeTool.PAN)`) only does `updateZoomAndPan(scale, pan + dragAmount)` — no `velocityTracker.addPosition`, no end-of-gesture decay.
- Main two-finger block `:1857`, `onDragStart` `:2157`, `onDragEnd` `:2451`, `onDragCancel` `:2716` have no fling handling.

## Fix (mirror AI Studio, adapted to main's names)
1. State after `:329-330` (`coroutineScope`/`debounceJob`):
   `val velocityTracker = remember { androidx.compose.ui.input.pointer.util.VelocityTracker() }` + `var flingJob by remember { mutableStateOf<Job?>(null) }` (fully-qualified, zero new imports; `animateDecay` resolves from `animation-core` like `Animatable` already does).
2. Two-finger block `:1857` (`if (event.changes.size > 1) {`): first line `flingJob?.cancel()`.
3. `onDragStart` `:2157` (`onDragStart = { offset ->`): first lines `flingJob?.cancel()` + `velocityTracker.resetTracking()`.
4. `onDrag` PAN branch `:2362`: first line `velocityTracker.addPosition(change.uptimeMillis, change.position)`, keep existing `updateZoomAndPan` line.
5. `onDragEnd` `:2451`: after the `isDraggingCard` early-return block, insert PAN fling (AI Studio `:2336-2369` logic): if `isPanningBlackSpace || currentTool == StrokeTool.PAN`, `val v = velocityTracker.calculateVelocity()`; if `abs(v.y)>80f || abs(v.x)>80f`, `flingJob?.cancel(); flingJob = coroutineScope.launch { val decay = exponentialDecay<Float>(0.8f); per-axis Animatable(initialPan) animateDecay(v, decay){ updateZoomAndPan(internalZoomScale, Offset(curX,curY)) } joined }`; then `return@detectDragGestures` (same position as AI: before SELECT branch).
6. `onDragCancel` `:2716`: first line `flingJob?.cancel()`.
- Do NOT touch zoom limits (`0.5f..4.0f`), do NOT add scrollbar state (`lastScrollTimestamp`/`isDraggingScrollbar` are out of scope).

## Tests (pure JVM + source pins)
- `Phase272PanFlingTest`: `flingJob`/`velocityTracker` declared; cancel sites present (two-finger, drag-start, drag-cancel); `exponentialDecay(0.8f)` + `>80f` threshold + `animateDecay` wiring (source pins); decay math spot-check via `exponentialDecay` import-free JVM calculation if feasible, else source-pin only.

## Constraints
- No Room schema change / migration
- No new dependencies (animation-core + coroutines already on classpath; fully-qualified names, no new imports needed)
- No `.github/workflows/` edits
- Follow AGENTS.md (low-RAM: decay work is bounded per-axis, cancelled on new gesture; no perpetual poller)
- `verification-metadata.xml` untouched

## DoD
- `gradle :app:assembleDebug` green
- `gradle :app:testDebugUnitTest` green (all new + existing, 0 failures)
- `gradle :app:lintDebug` 0 errors
- `workspace/phase-272/REPORT.md` with file:line evidence table
