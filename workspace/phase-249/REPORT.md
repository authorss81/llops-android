# Phase 249 — Canvas criticals: wet throttle, dispose fire-and-forget, card-hit tail, eraser quadratic

Date: 2026-08-30. Spec: `workspace/phase-249/PROMPT.md` (verified against repo at `2709453`; HEAD at start = phase-248 `52c85da`).

Closes the four HIGH/CRITICAL canvas issues from `AUDIT_2026-08-30.md` that the user observed as **"dots far from touch"** and **"weird shape"**.

---

## Bug 1 (HIGH) — wet throttle drops real ink via wall-clock stamps + smoothed distance

**Root cause (pre-fix, `AnnotationCanvas.kt:2038-2044`):**
- `lastTime` was **fabricated** — `val lastTime = if (activePoints.size >= 2) System.currentTimeMillis() - 16L else System.currentTimeMillis() - 100L` — unrelated to the sample's real `MotionEvent.eventTime` (uptime clock). `curTime` was also `System.currentTimeMillis()`.
- The `dist >= 6f` floor measured the **stabilizer-curbed / clamped** `drawPoint`, not the raw digitizer delta. `BrushStrokeMath.velocityWidthFactor` + the EWMA attenuate the delta, so a fast stroke's deltas landed under the floor and real ink was dropped ("dots far from touch").

**Fix:**
- New pure-JVM gate `services/WetThrottlePolicy.kt`:
  - `MIN_PX_FOR_WET_SAMPLE = 1.5f` — a distance floor applied to the **RAW digitizer delta** (`docs` `:26`, gate `:62`).
  - `MAX_MS_PER_WET_SAMPLE = 16L` — a **time floor on the real MotionEvent uptime clock** (`:33`, gate `:63`).
  - `shouldProcess(lastRawX, lastRawY, lastSampleTimeMs, rawX, rawY, sampleTimeMs)` **fails open** when any reference is missing (first sample of a stroke) `:44-49`.
- `AnnotationCanvas.kt` wet gate (`:2143-2178`):
  - `curTime = sampleTimestampMs` is the **exact `MotionEvent.eventTime`** already threaded through the passive `pointerInteropFilter` bridge → `StrokeInputBatcher` → node-local drain (the Phase-214 drain variable, unchanged).
  - `lastTime = lastRawWetTimeMs` — the **previous accepted RAW sample's** real stamp, never wall-clock. (Review-fix: the `?: activePoints.lastOrNull()?.timestampMs` fallback was dead code — `lastRawWetX/Y/TimeMs` are set and cleared as ONE unit, so when the timestamp ref is null the position refs are null too and `shouldProcess` fails open; removed for clarity, no behavior change.)
  - The gate is fed the **pre-smoothing, pre-clamping world-space RAW digitizer position** (`rawCanvasX/rawCanvasY`), `lastRawWetX/Y/TimeMs` updated only on acceptance (`:2175-2177`).
  - New block-scope vars `lastRawWetX/Y/TimeMs` (`:1695-1697`), reset to `null` per stroke start (`:1922-1924`) so a fresh stroke never inherits a stale "huge jump" reference that would throttle its first samples.
  - Interpolation path (`:2179-2198`) is untouched — translucent wet layers still deposit full stamps, then `wetBrushEngine.interpolateSegment` fills gaps.
- The change is strictly inside the `isWet` branch — the phase-228 "pen/pencil/charcoal are not wet" gate (`isWet == BrushStrokeMath.isWetRenderedTool(currentTool)`, `:2143`) is byte-identical; non-wet tools still add every live sample.

**Verified:** `grep` proves zero remaining `wetBrushEngine.shouldProcessPoint(` callers and zero `System.currentTimeMillis() - 1...` fabrications in `AnnotationCanvas.kt`.

## Bug 2 (CRITICAL) — `flushPendingSaves` fire-and-forget write loss

**Root cause (pre-fix, `NoteflowViewModel.kt:4034-4043`):** `viewModelScope.launch { ... }` unwrapped — a process kill (low memory, force-stop) during the write window cancelled the job and lost the dispose-flushed stroke.

**Fix:**
- `import kotlinx.coroutines.NonCancellable` (`NoteflowViewModel.kt:146`).
- Body wrapped in `withContext(NonCancellable) { ... }` (`:4043`), preserving the `pendingDebounce?.cancel()` → `join()` ordering relative to the flush (`:4044-4050`). KDoc updated (`:4019-4023`).
- The lock-safe gate semantics are unchanged: `flushEditorPageSave` still persists-or-defers in the same invoked code path, so no plaintext-write regression.

## Bug 3 (HIGH) — card-hit `onDragStart` leaves a ghost predicted tail

**Root cause (pre-fix, `AnnotationCanvas.kt:1753-1755`):** the card-hit early-return `if (isDraggingCard) return@detectDragGestures` did not strip the predictor's preview tail first; a tail left over from a prior freehand stroke rendered as a ghost segment ahead of the next stroke's first real sample.

**Fix:** the card-hit branch in `onDragStart` now calls `dropPredictedTail()` before `isDraggingCard = true` and its early-return (`AnnotationCanvas.kt:1837-1846`). This is a documented **fifth reconcile hop** (frame loop ×2, top of `onDrag`, top of `onDragEnd`, now onDragStart-card-hit).

**Pin update:** `Phase196MotionPredictionTest` pinned `countOccurrences("dropPredictedTail()") == 4+1`; extended to 5+1 with a KDoc note and a new ordering assertion that the strip precedes `isDraggingCard = true` in the `isHittingCard` branch. The `predictedTailTracker.clear() == 4` invariant is untouched.

## Bug 4 (HIGH) — `applyEraser` quadratic stutter

**Root cause (pre-fix, `AnnotationCanvas.kt:1688-1736` + `StrokeSegmenter.kt:110-197`):** each drag sample ran O(strokes × points × eraseSamples) — full stroke list, full polyline, full accumulated erase path — on long eraser drags over stroke-heavy notes → frame stutter → the same "dots far from touch".

**Fix (two independent mitigations per PROMPT):**
- **Sample window:** `applyEraser` now processes only samples accumulated since the last pass, capped at `EraseHitBucketPolicy.MAX_ERASE_SAMPLES_PER_APPLY = 8` (the coalesced-history burst size). New block-scope `lastProcessedEraseSampleIndex` (`AnnotationCanvas.kt:1694`), advanced to `eraseSamples.size` after each pass (`:1739`); reset `= 0` with a fresh bucket on every eraser drag start (`:1974-1975`).
- **Spatial bucket:** new pure-JVM `services/EraseHitBucketPolicy.kt`:
  - `build(strokes, maxStampRadiusPx)` tilts every stroke into a world-space grid; cell size = `max(DEFAULT_CELL_SIZE_PX = 384f, selectionRadius * 3)` (`:136`), selection radius derived from the widest stroke + `EraserGeometryPolicy.coverageRadius/legacyRadius` so every eraser coverage rule is a true subset of the query radius (no false negatives).
  - `candidatesWithinCircle(cx, cy, radiusFor(stroke))` (per-stroke `strokeContainsPoint`-family superset test) limits the segmenter to nearby strokes (`:42-65`).
  - `replaceStrokes(removed, added)` re-tiles **only the changed strokes**, in place (`:74-77`); `detach` is identity-based (`it.stroke === stroke`, `:82`) so a wet partial survivor sharing the removed stroke's id is detached and re-inserted via `added`.
  - Built **lazily** at the first sample of each drag, seeded with the current full list (`AnnotationCanvas.kt:1746-1750`), so a long drag never pays a full-list pass per sample.
- Ordering pass: the single O(strokes) full-list emission happens **only when a stroke actually changed** (preserving z-order), via `bucket.replaceStrokes(removed, added)` (`:1806`).

---

## Tests (16 new)

### `app/src/test/java/com/authorss81/noteflow/Phase249CanvasCriticalsTest.kt` (9)

Behavior on the new pure-JVM gate (`WetThrottlePolicy`):
1. `wet throttle keeps every sample that moved the raw floor regardless of time` — ≥1.5px RAW delta accepts even inside the 16ms window.
2. `wet throttle swallows stationary jitter inside the uptime floor` — sub-floor distance drops a stationary point.
3. `wet throttle accepts stationary samples once the uptime floor elapses` — a resting stamp ≥16ms later is kept: the real-timeline floor cannot starve slow ink.
4. `wet throttle is fail open when a reference is missing (first sample)` — no computed reference ever rejects a stroke's first sample.

Source pins for the Compose wiring (the repo's unit suite is pure JVM — no Compose/Room):
5. `wet gate source pin - stamped sample timestamps instead of fabricated wall clock` — the gate reads `sampleTimestampMs`, `rawCanvasX/rawCanvasY` and `lastRawWet*`; no `System.currentTimeMillis() - 16L` fabrication, no `shouldProcessPoint`.
6. `card hit early return source pin - predicted tail is dropped before the return` — strip precedes `isDraggingCard = true` and the card early-return.
7. `flushPendingSaves source pin - body wrapped in withContext NonCancellable`.
8. `applyEraser source pin - bucket + sample window + hard cap` — windowing via `lastProcessedEraseSampleIndex`, `takeLast(MAX_ERASE_SAMPLES_PER_APPLY)`, lazy bucket, `replaceStrokes`.
9. `batch drain source pin - node local sample coordinates flow through unchanged` — `boxLocalX = sample.x,` / `boxLocalY = sample.y,` and `change.position.x` feed the monotonic `lastIngestedInputTimestampMs` gate; eraser drag-start resets (`lastProcessedEraseSampleIndex = 0`, `eraseHitBucket = null`).

**Phase-240 regression surface** (PROMPT line 74 references `Phase240RotationGateTest` — it does **not exist** in this repo: phase-243 deleted it together with the rotation feature). Test 9 pins the FIFO node-local drain path in the same `detectDragGestures` handler the rotation gate used to guard.

### `app/src/test/java/com/authorss81/noteflow/services/EraseHitBucketPolicyTest.kt` (7)
10. `nearby strokes are candidates and distant strokes are never scanned`.
11. `a stroke spanning many cells is returned exactly once` (multi-cell de-dup, no duplicates across cell boundaries).
12. `candidate set is monotonic as the query radius grows` (larger query radius → superset).
13. `no false negatives - every stroke a coverage hit could reach is a candidate` (vs the `strokeContainsPoint`-style circle rule).
14. `replaceStrokes drops the removed stroke and surfaces the survivor`.
15. `same-id wet partial replacement keeps only the masked copy` (identity-based detach re-inserts the same-id survivor via `added`).
16. `the per-call sample cap is the coalesced burst size of eight` (`MAX_ERASE_SAMPLES_PER_APPLY == 8`).

### Updated (1)
- `Phase196MotionPredictionTest.prediction is drawn through the activePoints preview and reconciled at every real hop`: reconcile-hop count 4 → 5 (`dropPredictedTail()` occurrences 5 → 6) with the phase-249 card-hit hop documented + a new ordering assertion (strip before `isDraggingCard = true`). All other phase-196 pins unchanged (`predictedTailTracker.clear() == 4` intact).

## Verification

- `gradle :app:compileDebugKotlin` — clean (pre-existing deprecation warnings only).
- `gradle :app:testDebugUnitTest` — **3610 tests / 0 failures / 0 errors** = phase-248 baseline 3594 + 16 new. One intermediate run surfaced `Phase196MotionPredictionTest` failing on the stale `dropPredictedTail()` count — fixed by the documented pin extension above; final full run green.
- `gradle :app:assembleDebug` — green.
- `gradle :app:assembleRelease` (R8 + signed, `RELEASE_KEYSTORE_B64`/`KEYSTORE_FILE` env present) — green.
- `gradle :app:lintDebug` — 0 errors.

## Constraints compliance

- No schema change, no new dependencies, `verification-metadata.xml` untouched, `.github/workflows/` untouched, base-APK-size rule intact (two pure-JVM policy classes, no heavy natives).
- The wet-throttle fix is confined to the `isWet` branch; `1e54820`'s "pen/pencil/charcoal are not wet" gate is byte-identical.
- `NonCancellable` = `kotlinx.coroutines.NonCancellable`, already on the classpath.
- Manual DoD probes (fast wet stroke smoothness, ghost-tail after sticky-note drag, 200+ stroke erase) are not executable on CI — covered by the two pure-JVM policy behavior tests + the source pins (same pattern as phases 245/248).

## Files

- `app/src/main/kotlin/com/authorss81/noteflow/services/WetThrottlePolicy.kt` — NEW.
- `app/src/main/kotlin/com/authorss81/noteflow/services/EraseHitBucketPolicy.kt` — NEW (file-level private `Bounds`/`CellEntry` + private `computeBounds`).
- `app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt` — Bugs 1/3/4.
- `app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt` — Bug 2.
- `app/src/test/java/com/authorss81/noteflow/Phase249CanvasCriticalsTest.kt` — NEW.
- `app/src/test/java/com/authorss81/noteflow/services/EraseHitBucketPolicyTest.kt` — NEW.
- `app/src/test/java/com/authorss81/noteflow/Phase196MotionPredictionTest.kt` — pin extended to the phase-249 card-hit reconcile hop.

## Review-fix round (2026-08-30)

Applied the two actionable findings from the phase-249 code review:

- **Finding 2 (LOW/cosmetic — dead code):** removed the inert `?: activePoints.lastOrNull()?.timestampMs` fallback in the wet gate (`AnnotationCanvas.kt:2171`). `lastRawWetX/Y/TimeMs` are always set and cleared as ONE unit (acceptance `:2175-2177`, per-stroke reset `:1922-1924`), so the fallback arm was never reachable and implied a mixed raw/smoothed state that cannot occur. Behavior is byte-identical (when the timestamp ref is null the position refs are null too → `shouldProcess` fails open, so a fresh stroke's first sample is never throttled). Comment updated to document the invariant.
- **Finding 7 (style — `.editorconfig`):** added the missing final newline (`insert_final_newline = true`) to all four new files: `WetThrottlePolicy.kt`, `EraseHitBucketPolicy.kt`, `Phase249CanvasCriticalsTest.kt`, `EraseHitBucketPolicyTest.kt`.

No functional changes; the source-pin tests, unit suite, and builds are unaffected. `gradle :app:testDebugUnitTest` still **3610 / 0 failures / 0 errors**; `assembleDebug` + `lintDebug` 0 errors re-verified green after the fixes.