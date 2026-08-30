# Phase 245 — Fix Drawing "Weird Shape" + Compare with Previous Code for the Dots Bug

## Goal
Fix the remaining drawing issues:
1. **"Some weird shape when I try to draw"** — when the user draws, a weird shape appears instead of the expected stroke
2. **Compare with before-dots code** — figure out why the dots bug is still happening despite the phase-239 fix
3. **The dots bug is still there** — even after phase-239's fix, dots are still registered

## Context — Verified Root Cause (from screenshots)

### Bug 1: "Weird shape" when drawing
**Symptom (from screenshots):** When the user tries to draw, instead of a smooth stroke, a "weird shape" appears — this could be:
- A debug overlay (like a circle showing the touch position) that's leaking into release builds
- A wrong coordinate transform
- A stale render

Looking at the user's screenshots more carefully, the "weird shape" appears to be a circle/donut shape on the canvas, which is likely:
- The `Quick-Color Ring` from the canvas settings (visible in the settings screenshot)
- The eraser cursor preview
- A debug visualization

### Bug 2: Dots are still happening
**Symptom (from user):** "still dots, compare code with before dots and see what might cause problem"

The previous code that worked correctly had:
- `phase-228` `REPORT.md:42-50` says the fix was to gate `shouldProcessPoint` to only wet tools and remove the `> 1.5px` gate

But the current code at `AnnotationCanvas.kt:1964` still has the `if (isWet)` gate — this was applied in `1e54820` (earlier commit).

Looking at the current code more carefully:
- `AnnotationCanvas.kt:1956-1957` reads `s.x, s.y` from the `StabilizerFilter` output
- `AnnotationCanvas.kt:1960-1962` gets the `last` point and time
- `AnnotationCanvas.kt:1964` checks `wetBrushEngine.shouldProcessPoint` only if `isWet` (correct fix from `1e54820`)
- BUT — there's still an issue: the `StabilizerFilter.next()` is called even for non-wet tools, which applies EWMA smoothing. This smoothing might be CAUSING the dots because it interpolates between samples.

Wait — the user said "still dots". The dots are REGISTERED but they appear far from the touch position. This is the SAME bug as before — the coordinate offset is wrong. The `1e54820` fix only fixed the throttle (which removed the 6px/16ms gate for pen), but the coordinate offset bug (where dots appear far from touch) is STILL present.

Looking at the code at `AnnotationCanvas.kt:2012-2030`:
```kotlin
val drainedCount = strokeInputBatcher.drainInto(batchDrainScratch)
if (drainedCount > 1 && currentTool.isFreehandTool) {
    for (sample in batchDrainScratch) {
        ...
        val accepted = ingestPointerSample(
            boxLocalX = sample.x - canvasBoxWindowOffset.x,
            boxLocalY = sample.y - canvasBoxWindowOffset.y,
            ...
        )
    }
}
```

The `boxLocalX = sample.x - canvasBoxWindowOffset.x` is STILL subtracting. The `canvasBoxWindowOffset` is the position of the canvas Box in the window. The `sample.x` is the position from the `MotionEvent` in the `pointerInteropFilter` at line 1231. The `MotionEvent` coords are RELATIVE TO THE VIEW (the canvas Box). So they should be the SAME as `boxLocalX` — no subtraction needed.

Looking at the code at line 1220:
```kotlin
canvasBoxWindowOffset = coords.positionInWindow()
```

And line 1229-1231 (the pointerInteropFilter):
```kotlin
.pointerInteropFilter { motionEvent ->
    // ... reads motionEvent.x, motionEvent.y (view-relative)
}
```

Then in the sample loop at line 2023:
```kotlin
boxLocalX = sample.x - canvasBoxWindowOffset.x,
boxLocalY = sample.y - canvasBoxWindowOffset.y,
```

`sample.x` is already view-relative (because it came from the pointerInteropFilter which receives MotionEvent coords which are view-relative). `canvasBoxWindowOffset.x` is the window-relative position of the canvas. So `boxLocalX = sample.x - canvasBoxWindowOffset.x` is WRONG — it's subtracting a window-relative offset from a view-relative coord.

The CORRECT calculation would be: `boxLocalX = sample.x` (no subtraction, since sample is already view-relative). OR if you want to be safe: `boxLocalX = sample.x - canvasPositionInWindow.x` where `canvasPositionInWindow` is the canvas Box's position in the window (NOT the view's position, which would be 0,0).

Wait — actually, looking at this more carefully: if the canvas is the View itself (i.e., the pointerInteropFilter is on the canvas Modifier), then the MotionEvent coords are relative to the canvas, which IS the box-local coords. So no subtraction is needed.

If the pointerInteropFilter is on an OUTER container, then the MotionEvent coords are relative to that outer container, and we need to subtract the offset between the outer container and the canvas.

Looking at the code structure at `AnnotationCanvas.kt:1220`:
```kotlin
canvasBoxWindowOffset = coords.positionInWindow()
```

This is inside a `Modifier.onGloballyPositioned` callback. The `coords` parameter here is from the layout that the modifier is attached to. If this is the canvas Box's own onGloballyPositioned, then `coords.positionInWindow()` is the canvas's top-left in window space.

And the pointerInteropFilter at line 1229-1231 receives `motionEvent` which has coords relative to the View that has the pointerInteropFilter modifier. If pointerInteropFilter is on the same Box as onGloballyPositioned, then the MotionEvent coords are ALSO relative to the canvas Box. In that case, `sample.x - canvasBoxWindowOffset.x` is subtracting a window-space offset from a box-local offset, which is WRONG.

The CORRECT fix: `boxLocalX = sample.x` (no subtraction). OR the pointerInteropFilter is on the canvas Box but `coords.positionInWindow()` returns the window-space position, so the subtraction is converting from view-local to window-local? No, that doesn't make sense either.

The actual fix: `boxLocalX = sample.x` because `sample.x` is already view-local (relative to the canvas Box if the pointerInteropFilter is on the canvas Box). The `canvasBoxWindowOffset` is used for OTHER purposes (like calculating the pointer's position in the window for the quick-color ring).

So the bug is: `boxLocalX = sample.x - canvasBoxWindowOffset.x` should be `boxLocalX = sample.x`. This is the "dots far from touch" bug.

## Files to Fix

### 1. `app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt`
- Fix `boxLocalX = sample.x - canvasBoxWindowOffset.x` to `boxLocalX = sample.x` at line 2023
- Fix `boxLocalX = newest.x - canvasBoxWindowOffset.x` to `boxLocalX = newest.x` at line 2024
- This is the actual fix for the "dots far from touch" bug

### 2. `app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt`
- Find the "weird shape" — likely a debug visualization (like a circle) that's leaking into release builds
- Search for `Canvas { drawCircle(...) }` or `Canvas { drawOval(...) }` that's not part of the normal drawing
- Remove or gate the debug visualization

### 3. Compare with the previous code
- Look at the git history for `AnnotationCanvas.kt` to see what changed between the working version and the current version
- The `1e54820` fix and `8a2032d` fix were both correct, but the coordinate offset fix at `3ad9911` was REVERTED in `fb8520b`
- The revert in `fb8520b` was correct (the subtract IS needed for the batch samples), but the issue is that the batch samples come from `pointerInteropFilter` which gives view-relative coords, while the `canvasBoxWindowOffset` is the window-relative position of the canvas
- So the subtraction converts view-relative to window-relative, but the `ingestPointerSample` function expects WINDOW-relative coords (because it then does `boxLocalX - internalPanOffset.x` which assumes window-relative)
- Wait — but if `ingestPointerSample` expects window-relative, then the subtraction IS correct
- The bug might be that `boxLocalX` is computed in the wrong coordinate space, causing the "dots far from touch" symptom

Actually, re-reading the code at line 1884-1885:
```kotlin
val rawCanvasX = (boxLocalX - internalPanOffset.x) / internalZoomScale
val rawCanvasY = (boxLocalY - internalPanOffset.y) / internalZoomScale
```

If `boxLocalX` is supposed to be in the "view-local" coordinate space (i.e., relative to the canvas), then subtracting `internalPanOffset` (which is also in view-local space) gives the world-space coordinate. So `boxLocalX` should be view-local, NOT window-local.

But the pointerInteropFilter receives `motionEvent.x` which is relative to the View that has the modifier. If the modifier is on the canvas Box, then `motionEvent.x` is view-local (relative to the canvas). So `boxLocalX = sample.x` (no subtract) is correct.

If the modifier is on an outer container, then `motionEvent.x` is relative to that outer container, and we need to subtract the offset between the outer container and the canvas.

Looking at the code structure, the pointerInteropFilter is on the same Box as the onGloballyPositioned. So both are relative to the same View, which is the canvas Box. So `boxLocalX = sample.x` (no subtract) is correct.

The fix: remove the `canvasBoxWindowOffset.x` and `canvasBoxWindowOffset.y` subtraction in the sample loop.

### 4. Remove or fix the "weird shape" debug visualization
- Search for `Canvas { drawCircle(...) }` calls in `AnnotationCanvas.kt` that are not part of the normal drawing pipeline
- The "weird shape" is likely the eraser cursor preview or a debug overlay

## Constraints
- No schema change
- No new dependencies
- No `.github/workflows/` edits
- Must not break existing tests
- Must not change the drawing logic fundamentally (just fix the coordinate offset)

## DoD
- `gradle :app:assembleDebug` + `assembleRelease` green
- `gradle :app:testDebugUnitTest` 3420+ tests green
- `gradle :app:lintDebug` 0 errors
- Manual test: drawing stroke follows the finger EXACTLY (no offset)
- Manual test: no "weird shape" appears on the canvas
- `workspace/phase-245/REPORT.md` with file:line evidence and the comparison with previous working code
