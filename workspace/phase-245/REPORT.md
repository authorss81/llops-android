# Phase 245 — Drawing "Weird Shape" + Dots: Root Cause & Fix

## Summary
- **Bug 1 ("some weird shape when I try to draw" — a donut/dot pops over the canvas instead of a stroke)**: root cause is the **Quick-Color Ring** long-press detector opening for ANY quiet hold of `longPressTimeoutMillis` — even while the user was *aiming the first mark* or *drawing slow deliberate ink*. The ring then consumed the press, so the intended stroke never deposited and the ring's **donut + center dot** (its current-color disc) was left on screen. Fixed with a **movement-aware long-press that yields to a stroke the moment the pointer crosses the touch slop**.
- **Bug 2 ("dots still registered, compare with before-dots code")**: the PROMPT's "still subtracting `canvasBoxWindowOffset`" hypothesis describes **PRE-phase-240 code that no longer exists**. Verified against the bytecode of the pinned compose-ui 1.7.6 AAR and the git history (`5d7288d`, `3ad9911`, `fb8520b`): every ingestion path already passes **node-local** coordinates with **no window-offset subtraction anywhere**, and the phase-240 fix is correct. The remaining "dot" a user could still see at a still finger was the ring's center disc — fixed by Bug 1's slop-yield.

## Bug 1 — Quick-Color Ring hijacks the press (the "weird shape")
### Evidence
- The only persistent **donut/circle** overlay reachable with a single still finger on a freehand tool is the Quick-Color Ring: `QuickColorRingOverlay` draws a backing circle, a filled **center disc of the current tool color** (the "dot"), and the swatch ring (`AnnotationCanvas.kt:4657-4715`).
- The long-press detector opened it on ANY quiet hold: `while` the press was down it only waited for `waitForUpOrCancellation()` (would suspend for the whole hold even if the finger was about to draw).
- The default window is `LocalViewConfiguration.current.longPressTimeoutMillis` (`AnnotationCanvas.kt:577`) — a stroke that takes longer than that to start/lay down (aiming, slow deliberate ink, first-touch hesitation) pops the donut mid-draw.
- After the ring confirmed, `down.consume()` (`AnnotationCanvas.kt:1641`) took the pointer; the stroke never started and the donut + center dot stayed on the page.

### Fix
1. **New pure-JVM decision** `QuickColorRingMath.holdWithinLongPressSlop(pointerX/Y, downX/Y, slopPx)` (`QuickColorRingMath.kt:180-203`): the hold is still eligible for the ring **only while the pointer's displacement from its DOWN position stays ≤ touch slop**. Displacement-from-down (not the per-event delta) is the measure so a *slow-but-steady* drag accumulates exactly like a fast flick, while sub-slop finger tremor while holding still never aborts.
2. **New gesture wait** `waitForUpOrSlopMove` (`AnnotationCanvas.kt:4642-4664`): suspends until the pointer goes UP (→ tap), or a **second finger presses** (pinch/undo/redo — never get the ring), or the primary pointer **exits the long-press slop** (→ yield to drawing).
3. **Wired into the detector** (`AnnotationCanvas.kt:1631-1639`): the `withTimeoutOrNull(longPressMillis)` hold uses the slop-aware wait instead of `waitForUpOrCancellation()`. The ring now opens **only for a genuinely STILL hold of the full window**; a yielding stroke records its first point at the exact down position (no lost start, no stray dot at a ring anchor).

## Bug 2 — Dots: comparison with the before-dots code
### Git-history truth table (see `git log --follow` on AnnotationCanvas.kt)

| Commit | What it did | Current status |
|---|---|---|
| `1e54820` | Removed the 6px/16ms freehand throttle; kept the gate **wet-only** | Still present and correct: `isWet` gates `WetBrushEngine.shouldProcessPoint` (`AnnotationCanvas.kt:2037-2045`); non-wet tools add **every** live sample |
| `3ad9911` | Removed the `canvasBoxWindowOffset` subtract (claimed box-local already) | Super-ceded by `fb8520b` below, then made correct by phase-240 |
| `fb8520b` | Reverted `3ad9911` (claimed window coords → needed subtract) | Rendered moot by phase-240 bytecode proof |
| `5d7288d` (phase-240) | Deleted `canvasBoxWindowOffset` + `onGloballyPositioned{positionInWindow()}`; batch/newest/predicted paths pass node-local coords AS-IS | **This is the fixed state. Nothing to re-subtract.** |
| **current** | Batch drain `AnnotationCanvas.kt:2098-2127`, newest-path `:2110-2116`, fallback `:2124`, predicted tail `:827-848` all pass node-local coords with **no** window subtraction | Verified below |

### Why phase-240's fix is genuinely correct (not vibes)
- **Bytecode proof (compose-ui 1.7.6, the pinned BOM):** `PointerInteropFilter` dispatches MotionEvents already offset into the filter node's layout space — the bridge intro (with the neutral-frame explanation) is at `AnnotationCanvas.kt:1304-1328`. `PointerInteropUtils.offsetLocation(-localToRoot)` is applied on dispatch, so view-local == box-local for a `pointerInteropFilter` sitting on the same Box as the drag handlers.
- **No subtraction remains:** the PROMPT's old `boxLocalX = sample.x - canvasBoxWindowOffset.x` lines (pre-240 `:2023`) are **gone**; the current drain passes `sample.x` verbatim (`:2099-2100`, `:2111-2112`, `:2124`).
- **Predicted tail parity:** the LIVE channel passes the neutral frame `canvasWindowX/Y = 0f` (`AnnotationCanvas.kt:837-838`); `StrokeInputBatcher` explicitly documents node-local storage (`StrokeInputBatcher.kt:25-38`).

### What a remaining "dot" at a still finger actually was
The ring's **center disc** (`QuickColorRingOverlay` draws `currentColor` at `CENTER_RADIUS_PX`, `AnnotationCanvas.kt:4685-4688`) — i.e. Bug 1. With the slop-yield fix, a stroke that moves past slop never lets the ring open; the duplicate-sample/replay stack is separately pinned by the monotonic `StrokeBatchPolicy.isStale` gate (`StrokeInputBatcher.kt:144-145`).

## Changes
- `services/QuickColorRingMath.kt`: added `holdWithinLongPressSlop` (pure JVM).
- `ui/components/AnnotationCanvas.kt`: ring long-press now yields to drawing via `waitForUpOrSlopMove`; imports `AwaitPointerEventScope`/`PointerInputChange`.
- `test/.../Phase245DrawingRegressionTest.kt` (new, 13 tests): ring slope-yield boundary/oscil/drift/zero-slop; neutral-frame world mapping; double-offset displacement proof; FIFO node-local drain identity; monotonic stale gate; out-of-page drop parity.
- `test/.../Phase193ResizeHandleVisibilityTest.kt`: source-pin updated — the ring wait is now `waitForUpOrSlopMove(` (≥1) and the `waitForUpOrCancellation()` floor is 2 (the two media-embed handlers).

## Verification
- `gradle :app:testDebugUnitTest` — **3573 tests green** (3560 baseline + 13 new; the only failure in the first run was the phase-193 source-pin that legitimately changed, now updated).
- `gradle :app:assembleDebug` — green.
- `gradle :app:assembleRelease` (R8 + signed, CI keystore) — green.
- `gradle :app:lintDebug` — **0 errors**.
- Manual-test contract (DoD): a stroke now follows the finger exactly (node-local frame, no offset); no donut/dot overlay can appear once the pointer crosses the touch slop — the ring only opens for a genuinely still long-press.

## DoD
- [x] No schema change
- [x] No new dependencies
- [x] `.github/workflows/` untouched
- [x] Existing tests: 3573 green (1 source-pin legitimately updated)
- [x] Report with file:line evidence + git-history comparison (above)