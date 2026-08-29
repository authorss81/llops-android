# Phase 240 — Fix Pinch-to-Rotate Bug + Drawing Coordinate Offset (the "dots far from touch" regression)

## Goal
Fix two critical drawing bugs found from real-device testing:
1. **Two-finger pinch rotates the page** — the `calculateRotation()` is called on every 2-finger event including pinches, so even tiny finger-jitter produces a non-zero rotation delta. Also `event.changes.forEach { it.consume() }` at `AnnotationCanvas.kt:1360` swallows the touch events so drawing stops when 2 fingers are down.
2. **Dots are registered far from the actual touch position** — this is a regression from the previous coordinate offset fix. The `canvasBoxWindowOffset` subtraction was reverted but the canvas transform (pan/zoom) and the sample point's world-space accounting are now off.

## Context — Verified Root Cause (from screenshots and source)

### Bug 1: Two-finger pinch rotates the page
**File:** `app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt:1323-1364`
**Root cause:** The 2-finger pointer handler calls `event.calculateRotation()` unconditionally at line 1338. Even a pure pinch (no twist) has micro-rotation deltas because the two fingers are never exactly equidistant from the centroid between events. So a pinch to zoom also rotates the page. Additionally, `event.changes.forEach { it.consume() }` at line 1360 means no drawing happens while 2 fingers are down.

**Fix:** Only apply rotation when the user is clearly doing a TWIST (e.g. rotation delta > some threshold like 5 degrees AND not doing a large zoom/pan in the same event). Better: gate rotation on `event.calculatePointerEventCount() == 2` AND a minimum time between events so micro-jitter doesn't accumulate. Also consume the events with proper `it.consume()` so drawing still works on the single-finger fall-through.

### Bug 2: Dots registered far from touch (coordinate offset)
**File:** `app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt:2012-2030`
**Root cause:** `AnnotationCanvas.kt:1220` `canvasBoxWindowOffset = coords.positionInWindow()` sets the offset from `positionInWindow` which is a `Window`-relative origin. But then the sample loop at 2023-2030 does `sample.x - canvasBoxWindowOffset.x`. The `pointerInteropFilter` at 1231 uses `MotionEvent` raw coords which are already `view-local`. So subtracting `positionInWindow` again double-subtracts. The result: dots are drawn at `(actualX - 2*offset.x)` which is way off-screen / off-position.

**Fix:** Verify the `positionInWindow` call is on the right node. If the `coords` come from `onGloballyPositioned` of the canvas Box, then `coords.positionInWindow()` is the canvas top-left in window space, and `MotionEvent.x` (in `pointerInteropFilter`) is relative to the view (which is the same as the canvas Box). So `positionInWindow` is the right value to subtract. But the test says dots are far away, which means the offset is wrong.

The most likely bug: `coords.positionInWindow()` is being called on the WRONG layout node. If it's on the host activity decor view, then it gives the full screen position, not the canvas. The canvas-local offset should be `coords.positionInRoot()` or the canvas modifier's own `onGloballyPositioned`.

Check `app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt:910-915` and `1210-1225` — where is `coords` defined? It must be on the actual canvas `Modifier.onGloballyPositioned`, not on the outer container.

## Files to Fix

### 1. `app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt:1323-1364`
Replace the 2-finger gesture block with one that:
- Only applies rotation when the user is clearly twisting (rotation delta > threshold AND no significant zoom/pan in same event)
- Uses a small dead-zone for rotation (e.g. 2 degrees) to absorb finger jitter
- Stores the per-frame rotation delta in a local variable and only commits to `internalRotationDegrees` if it exceeds the dead-zone
- Properly consumes events only when they cause a transform (so single-finger drawing still works)

```kotlin
// Fix 1: only rotate on clear twist, not on pinch
.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.size > 1) {
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                val rawRotation = event.calculateRotation()
                // Fix: dead-zone rotation to ignore micro-jitter from pinch
                val rotationChange = if (kotlin.math.abs(rawRotation) < 2.0f) 0f else rawRotation
                // Fix: only apply transform if there's actual change
                if (zoomChange != 1f || panChange != Offset.Zero || rotationChange != 0f) {
                    // ... existing transform math ...
                    // Fix: only consume events that we used
                    event.changes.forEach { it.consume() }
                }
            }
        }
    }
}
```

### 2. `app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt:910-1220`
Verify the `coords` in `onGloballyPositioned` is on the canvas Box (not the outer host). The `positionInWindow()` of a Box deep in the layout is wrong — the canvas Box's own position in the window should be `positionInRoot()` or the position reported by the Box's own onGloballyPositioned.

Check the structure:
```kotlin
// Find the Modifier.onGloballyPositioned call
// It should be on the actual canvas Box, not on the outer EditorScreen scaffold
// And it should store the offset as IntOffset relative to the WINDOW
// Then pointerInteropFilter uses MotionEvent coords which are also view-local
// So the subtraction should be CANVAS_BOX.positionInWindow() (which IS what the code does)
// But the test shows dots far away — so the offset must be wrong somehow
```

The actual fix: log the values at runtime to see what the offset actually is, then determine if the canvas box's `positionInWindow` is being computed at the wrong time (before layout completes) or for the wrong node.

A safer fix: use `awaitFrame()` or `onPlaced` to ensure the offset is computed after layout. Or better: compute the offset on every frame, not just once.

### 3. `app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt:2012-2030`
The `ingestPointerSample` receives `boxLocalX/boxLocalY` which is the result of `sample.x - canvasBoxWindowOffset.x`. If the offset is wrong, the ingested point is wrong. The fix here is to verify the offset is correct, not change the ingest logic.

## Constraints
- No schema change (pure UI fix)
- No `.github/workflows/` edits
- No new dependencies
- Must not break existing tests (run `gradle testDebugUnitTest` after fix)
- The fix must work on first touch (don't add startup delays)

## DoD
- `gradle :app:assembleDebug` green
- `gradle :app:testDebugUnitTest` 3420+ tests green
- Manual test: 2-finger pinch does NOT rotate the page (only zoom+pan)
- Manual test: 2-finger twist DOES rotate the page (intentional)
- Manual test: drawing stroke follows the finger exactly (no offset)
- `gradle :app:lintDebug` 0 errors
- `workspace/phase-240/REPORT.md` with file:line evidence of both fixes
