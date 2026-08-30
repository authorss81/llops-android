# Phase 249 — Canvas criticals: wet throttle, dispose-fire-and-forget, card-hit tail

## Goal
Close the HIGH/CRITICAL canvas issues from `AUDIT_2026-08-30.md` and the 2/5 audit that the user observed as "dots far from touch" and "weird shape":

1. **Wet throttle uses wall-clock + smoothed distance** (`AnnotationCanvas.kt:2038-2044`): the throttle's `lastTime = now() - 16L` is fabricated (not the sample's real `eventTime`), and the `dist >= 6f` check operates on the smoothed/curbed sample — both contribute to dropping real ink and producing "dots far from touch."
2. **`flushPendingSaves` is fire-and-forget** (`NoteflowViewModel.kt:4034-4043`): the `viewModelScope.launch { ... }` is not wrapped in `withContext(NonCancellable) { ... }` — a process kill (low memory, force-stop) during the write window loses the dispose-flushed stroke.
3. **`onDragStart`'s card-hit early-return** (`AnnotationCanvas.kt:1753-1755`) does NOT call `dropPredictedTail()` first, leaving a ghost tail at the start of a new freehand stroke.
4. **`applyEraser` is O(strokes × points × eraseSamples) per drag sample** (`AnnotationCanvas.kt:1688-1736` + `StrokeSegmenter.kt:110-197`): quadratic on long eraser drags on stroke-heavy notes → frame stutter → "dots far from touch."

## Context — verified at `2709453`

### Bug 1: wet throttle
- `AnnotationCanvas.kt:2038-2044`:
  ```kotlin
  if (isWet) {
      val lastWet = activePoints.lastOrNull()
      val lastTime = if (activePoints.size >= 2) System.currentTimeMillis() - 16L else System.currentTimeMillis() - 100L
      val curTime = System.currentTimeMillis()
      if (!wetBrushEngine.shouldProcessPoint(lastWet?.let { Offset(it.x, it.y) }, Offset(drawPoint.x, drawPoint.y), lastTime, curTime)) {
          return true
      }
  }
  ```
  The fabricated `lastTime = now() - 16L` is unrelated to the actual `MotionEvent.eventTime`. `BrushStrokeMath.velocityWidthFactor` + the StabilizerFilter EWMA attenuate the delta, so `dist >= 6f` (the actual rule) drops sub-6px ink at speed.
  The fix is to thread the real sample timestamp (the `MotionEvent.eventTime` from the `pointerInteropFilter` at line 1304) into the `shouldProcessPoint` call. Update the gate to compute `lastTime` from the previous real sample's stored timestamp (NOT wall-clock). If you need to keep the gate `dist >= MIN_PX_FOR_WET_SAMPLE` (e.g. 1.5f), make it operate on the raw digitizer distance, not the smoothed distance.

### Bug 2: `flushPendingSaves` fire-and-forget
- `NoteflowViewModel.kt:4034-4043`:
  ```kotlin
  fun flushPendingSaves(...) {
      viewModelScope.launch {
          pendingDebounce?.cancel()
          pendingDebounce?.join()
          flushEditorPageSave(pageId, strokes, stickyNotes, embeds, layers)
      }
  }
  ```
  Wrap the body in `withContext(NonCancellable) { ... }` so the write completes even if `viewModelScope` is cancelled. Preserve the existing `cancel(); join()` ordering relative to the debounce.

### Bug 3: card-hit `onDragStart` no tail clear
- `AnnotationCanvas.kt:1753-1755`:
  ```kotlin
  if (isDraggingCard) {
      return@detectDragGestures
  }
  ```
  Add `dropPredictedTail()` before the `return@detectDragGestures`.

### Bug 4: `applyEraser` quadratic
- Pre-bucket the strokes by bounding box at drag start (use the first `eraseSample`'s position + the user's current brush width as the bounding box). For each drag sample, only iterate the strokes whose bounding box intersects the eraser circle. This is `O(candidates_in_radius × points × samples)` and is bounded by the spatial bucket size. As an immediate mitigation, add a hard cap on `eraseSamples` per `applyEraser` call (e.g. only process the last N samples) to avoid unbounded growth.

## Files to change

### 1. `app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt`
- Wet throttle: thread the real `MotionEvent.eventTime` through the `pointerInteropFilter` → `StrokeInputBatcher` → drain path; update the `lastTime` calculation; document the new contract.
- Card-hit `onDragStart`: add `dropPredictedTail()`.
- `applyEraser`: add a bounding-box pre-filter; cap `eraseSamples` processed per call to the last 8 samples (the coalesced-history burst size).

### 2. `app/src/main/kotlin/com/authorss81/noteflow/data/NoteflowViewModel.kt`
- `flushPendingSaves` body wrapped in `withContext(NonCancellable) { ... }`. KDoc note: the write is now guaranteed to complete even if the activity is destroyed.

### 3. Optional: `services/StrokeSegmenter.kt` + a new `EraseHitBucketPolicy.kt`
- Pure-JVM policy class that buckets strokes by world-space bounding box. `applyEraser` uses the bucket to limit the segmenter to nearby strokes.

## New tests

### `app/src/test/java/com/authorss81/noteflow/Phase249CanvasCriticalsTest.kt` (pure JVM, 6+ tests)
- Pin `AnnotationCanvas.kt` source: the wet-throttle block uses a real stored timestamp (NOT `System.currentTimeMillis() - 16L`).
- Pin `AnnotationCanvas.kt` source: the `onDragStart` card-hit early-return calls `dropPredictedTail()` BEFORE `return@detectDragGestures`.
- Pin `NoteflowViewModel.kt` source: `flushPendingSaves` body is wrapped in `withContext(NonCancellable) { ... }`.
- New `EraseHitBucketPolicyTest`: world-space bucketing is monotonic and bounds `applyEraser` to strokes within a circle of radius `currentWidth + stroke.width`.
- Pin: `applyEraser` caps `eraseSamples` to the last 8 samples per call.
- `Phase240RotationGateTest` and the existing `HistoryBatchTest` continue to pass (drain path uses node-local coords; the new threshold changes do not regress them).

## Constraints
- No schema change
- No new dependencies
- No `.github/workflows/` edits
- The wet-throttle fix must NOT regress `1e54820`'s "pencil/pen/charcoal are not wet" gate. The fix is in the `isWet` branch only.
- `assembleDebug` + `assembleRelease` (R8) must remain green; the `NonCancellable` import is `kotlinx.coroutines.NonCancellable` (already on classpath).
- `verification-metadata.xml` untouched (no dep changes)

## DoD
- `gradle :app:testDebugUnitTest` 3556+ green
- `gradle :app:assembleDebug` + `assembleRelease` green
- `gradle :app:lintDebug` 0 errors
- Manual: drawing a fast wet stroke does not produce dropped ink (no "dots far from touch")
- Manual: starting a stroke right after dragging a sticky note does not show a ghost tail
- Manual: long eraser drags on a note with 200+ strokes do not stutter
- `workspace/phase-249/REPORT.md` with file:line evidence
