# Phase 240 — Two-finger pinch no longer rotates the page; stroke dots land exactly on the touch

Status: **DONE**
Date: 2026-08-29

## Bugs fixed

Two drawing-touch regressions in `AnnotationCanvas.kt`:

1. **Two-finger pinch rotated the page AND swallowed drawing events.**
2. **Registered dots / stroke points landed far from the actual touch** (a coordinate-offset regression).

## Bug 2 (dots far from touch) — root cause

Compose **already localizes** the MotionEvent it delivers to the app's
`Modifier.pointerInteropFilter`. Verified against the compose-ui **1.7.6**
sources (this repo's pinned version, BOM 2024.12.01):

- `PointerInteropFilter.android.kt` calls `pointerEvent.toMotionEventScope(offset = this.layoutCoordinates?.localToRoot(Offset.Zero))`.
- `PointerInteropUtils.android.kt` applies `motionEvent.offsetLocation(-localToRoot)`,

so the batcher's drained samples are already **canvas-box node-local**. The old
drain path (`AnnotationCanvas`) subtracted `canvasBoxWindowOffset`
(`positionInWindow()`) a second time → **dot lands at
`actual − 2 × (boxOffset)`**. The phase-228 comment claiming "batch samples ARE
window coords" was wrong (it conflated the throttle fix `1e54820`); the fix
re-validates commit `3ad9911` (already-box-local, no subtract), not `fb8520b`.

### Fix (Bug 2)

- Batch drain + newest-sample paths now hand `sample.x / sample.y` to
  `ingestPointerSample(boxLocalX, boxLocalY, …)` **as-is** — no subtraction
  (`AnnotationCanvas.kt` two-finger-batch drain and the single newest fallback).
- The **phase-196 predicted tail** had the same latent double-subtract
  (`motionPredictor` records the SAME node-local MotionEvent, so its
  extrapolation is node-local too): the live channel now passes the **neutral
  frame (`canvasWindowX = canvasWindowY = 0f`)** and relies on the pure-JVM
  `predictedWorldPoint` map (pan/zoom only). Its `canvasWindowX/Y` parameters
  remain for the policy's unit tests.
- Deleted the now-dead `canvasBoxWindowOffset` state, its
  `onGloballyPositioned { … positionInWindow() }` capture hook, and the unused
  `positionInWindow` import. One ingestion frame (node-local) everywhere.

## Bug 1 (pinch rotates page + swallows events) — root cause

`PointerInputScope.calculateRotation()` (compose foundation) returns the
**per-event rotation delta in DEGREES**, weighted by distance from the
centroid. A pure radial pinch still reports a small non-zero delta **every
frame** (the fingers are never exactly equidistant from the centroid between
frames). Those sub-threshold deltas were accumulated unconditionally → a slow,
inexplicable page rotation. And because ANY non-zero delta entered the
transform branch, the handler also ran `event.changes.forEach { it.consume() }`
→ while two fingers were down, drawing stopped.

### Fix (Bug 1)

Pure-JVM rotation-intent gating in `services/CanvasRotationPolicy.kt`:

- `ROTATION_DEAD_ZONE_DEGREES = 2f` — per-event micro-jitter gate (pinch deltas
  stay sub-threshold).
- `ZOOM_DOMINANCE_THRESHOLD = 0.03f` — an event whose zoom deviates >3% from
  `1f` is a spread/squeeze (PINCH), rotation suppressed.
- `PAN_DOMINANCE_PX = 12f` — an event whose centroid translates >12px is a PAN,
  rotation suppressed.
- `gatedRotationDelta(rawDeltaDeg)` / `intentionalRotationDelta(rawDeltaDeg,
  zoomChange, panDistancePx)` — existing `sanitize`/`accumulate` untouched.

Wired into the two-finger handler:
`CanvasRotationPolicy.intentionalRotationDelta(event.calculateRotation(),
zoomChange, panChange.getDistance())`. A **genuine** twist (stable separation,
stationary centroid, real angular delta) still clears all three gates and
rotates. A jitter/no-transform multi-touch event now produces
`zoomChange == 1f ∧ panChange == Offset.Zero ∧ rotationChange == 0f`, so the
handler no longer enters the transform branch or consumes the changes →
single-finger drawing keeps working. Real pinch/pan/twist still consume and
transform (correct 2-finger-gesture territory).

## Files changed

- `app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt`
  — Bug 2 (drain + predicted-tail frame), Bug 1 (rot-gate wiring), deleted
  `canvasBoxWindowOffset` + capture hook + unused import, comments updated.
- `app/src/main/kotlin/com/authorss81/noteflow/services/CanvasRotationPolicy.kt`
  — dead-zone + zoom/pan-dominance gates.
- `app/src/main/kotlin/com/authorss81/noteflow/services/StrokeInputBatcher.kt`
  — `RawInputSample` KDoc corrected: samples are canvas-box-LOCAL (node-local)
  at consumption, NOT raw window coords.
- `app/src/main/kotlin/com/authorss81/noteflow/services/MotionPredictionPolicy.kt`
  — contract 3 + `predictedWorldPoint` KDoc: live channel passes the neutral
  frame; recorded/extrapolated events are node-local.
- `app/src/test/java/com/authorss81/noteflow/Phase240RotationGateTest.kt` — NEW,
  11 tests (dead-zone pure-pinch jitter, boundary-inclusive threshold,
  non-finite inputs, zoom-dominance radial pinch suppressed, ≤3% zoom wobble
  keeps twist, 12px-pan dominance suppressed, small centroid drift coexists,
  real twist accumulates, long pinch leaves `accumulate` untouched, non-finite
  zoom/pan suppressed, canvas wiring source pins).
- `app/src/test/java/com/authorss81/noteflow/HistoryBatchTest.kt` — drain pins
  updated to the node-local contract (ingest `sample.x/sample.y` as-is) with
  **negative** anti-regression pins for the old double-subtraction.

## Verification

- `gradle testDebugUnitTest` — **3556 tests / 0 failures / 0 errors**
  (phase-239 baseline 3545 + 11 new).
- `gradle assembleDebug` — BUILD SUCCESSFUL.
- `gradle lintDebug` — 0 errors.

No schema change, no migration, no new/removed dependencies, `.github/workflows/`
untouched, base-APK-size rule intact.

## Review fixes (2026-08-29, commit `llops: phase-240 review fixes`)

Review findings from the phase audit were applied:

1. **Stale comment corrected** — `AnnotationCanvas.kt:1254` (coalesced-history
   ingestor) still read *"Coordinates stay in RAW window space; world mapping
   happens at drain time."* — the exact opposite of the phase-240 fix, which
   removed the window-offset subtraction because the bridge already delivers
   node-local samples. Rewritten to state the samples are NODE-LOCAL (not raw
   window coords), that parent data drains via `ingestPointerSample` (no
   window-offset subtraction anywhere), and to document the SAME-NODE
   precondition (see Finding 6). `StrokeInputBatcher.kt` KDoc gained the same
   explicit precondition paragraph.

2. **Same-node precondition documented (Finding 6)** — the node-local parity
   between the `pointerInteropFilter` MotionEvent and the drag handlers'
   `change.position` holds only while they sit on the SAME canvas Box (same
   `localToRoot` of the filter node). Both the `AnnotationCanvas.kt` drain
   comment and the `StrokeInputBatcher.kt` class doc now call this out, so if a
   future refactor ever moves them onto nodes with different root offsets, the
   re-introduced window-offset subtraction requirement is not silently lost.

3. **Slow-twist trade-off (Finding 2) — documented, logic deliberately kept.**
   The review flagged that the 2° per-event dead-zone means a very slow twist
   (<2°/event) never accumulates and thus never rotates. We decided NOT to
   change the gating logic: a time-windowed accumulator for sub-threshold
   deltas would re-expose the ORIGINAL Bug 1 (random-walk jitter under a
   stationary two-finger hold drifting the page), which this phase exists to
   fix. The trade-off is documented in the `ROTATION_DEAD_ZONE_DEGREES` KDoc in
   `services/CanvasRotationPolicy.kt`. No test/behavior change.

Net behavior unchanged from the phase-240 fix; only comments/docs updated.
Re-verified below.

## Verification (review-fix round)

- `gradle :app:testDebugUnitTest --tests "*Phase240RotationGateTest"` — GREEN.
- `gradle testDebugUnitTest` — full suite green (3556 / 0 failures / 0 errors).
- `gradle assembleDebug` — BUILD SUCCESSFUL.
- `gradle lintDebug` — 0 errors.

No logic change, no schema, no deps, `.github/workflows/` untouched.