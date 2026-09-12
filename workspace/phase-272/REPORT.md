# Phase 272 — Pan fling with exponential decay (REPORT)

Panning stopped dead on finger lift. Ported AI Studio's fling: on
PAN/black-space drag end, a release faster than 80px/s keeps panning with
`exponentialDecay(frictionMultiplier=0.8f)` per axis (fast scroll, slow
settle). Any new gesture cancels the fling.

## 1. Evidence table (file:line on the phase-272 tree)

| # | Claim | File:line |
|---|-------|-----------|
| 1 | `velocityTracker` + `flingJob` state after `coroutineScope`/`debounceJob` | `ui/components/AnnotationCanvas.kt:358-361` |
| 2 | Two-finger block cancels fling first | `AnnotationCanvas.kt:1978-1979` (`if (event.changes.size > 1) {` + `flingJob?.cancel()`) |
| 3 | `onDragStart` cancels fling + `resetTracking()` | `AnnotationCanvas.kt:2281-2283` |
| 4 | PAN branch samples `addPosition(uptimeMillis, position)`, keeps `updateZoomAndPan` | `AnnotationCanvas.kt:2488-2491` |
| 5 | `onDragEnd`: after `isDraggingCard` early-return, PAN fling — `calculateVelocity()`, `abs(y)>80f \|\| abs(x)>80f`, per-axis `Animatable` + `animateDecay(v, exponentialDecay(0.8f))` driving `updateZoomAndPan`, then `return@detectDragGestures` before SELECT | `AnnotationCanvas.kt:2591-2621` |
| 6 | `onDragCancel` cancels fling first | `AnnotationCanvas.kt:2871-2872` |
| 7 | Zoom limits untouched (`0.5f..4.0f`) | `AnnotationCanvas.kt:1983` (same `coerceIn(0.5f, 4.0f)`) |
| 8 | No scrollbar state added (no `lastScrollTimestamp`/`isDraggingScrollbar`) | grep-verified absent |

## 2. Deviations from PROMPT (intent honored)

- **One added import** (`animation.core.animateDecay`, `:19`): the PROMPT's
  "fully-qualified, zero new imports" holds for `VelocityTracker` (FQN, `:360`)
  and `joinAll` (FQN, `:2601/2606`), but `animateDecay` is an *extension* on
  `Animatable` — Kotlin cannot resolve an extension call by package qualification
  alone, so the import is required to compile. `exponentialDecay` stays FQN
  (`:2599`). No new Gradle dependency (animation-core already on classpath).
- **Flanked launch shape**: two per-axis `Animatable`s joined with
  `kotlinx.coroutines.joinAll` (child `launch`es of the fling job, so one
  `flingJob.cancel()` stops both axes mid-flight).

## 3. Collateral test updates (honest, minimal)

- `Phase205CanvasCommitIntegrityTest` "no coroutine in drag-end" pin → amended:
  drag-end now launches exactly ONE coroutine and it must sit inside the PAN
  fling block (`flingJob = coroutineScope.launch`); the stroke-commit sync pins
  (`Dispatchers.Default` = 0, `CanvasCommitListPolicy.emittedList` present) are
  unchanged — the fling returns before SELECT/ERASER/commit and never touches
  stroke emission.
- `Phase254CommentTrimTest` re-baselined per repo precedent (all `//`
  comments, no KDoc opener added): AnnotationCanvas **8877 raw / 7094 code**
  (+42 / +32). EditorScreen/HomeScreen untouched.

## 4. Tests

- New `Phase272PanFlingTest` (9 tests): fling state declared; cancel sites
  (two-finger, drag-start + reset, drag-cancel); PAN-branch sampling; release
  wiring (`calculateVelocity` + `> 80f` + `exponentialDecay`/`0.8f` +
  `animateDecay` + `flingJob = coroutineScope.launch`); zoom-clamp intact;
  threshold boundary semantics; import-free exponential-decay settle math
  (monotone decay, bounded displacement, early-travel majority).
- `gradle :app:assembleDebug` — green.
- `gradle :app:testDebugUnitTest` — **3884 green, 0 failures** (3875 prior +
  9 new). Note: `Phase151MarkdownMainThreadPerfTest` (backtick timing) flaked
  once mid-phase (ratio ~24x on a loaded runner) and passes on re-run on both
  the clean tree and this tree — environmental, untouched by this diff (canvas
  gesture code shares no path with the markdown tokenizer).
- `gradle :app:lintDebug` — 0 errors.

## 5. Constraints honored

No Room schema change, no new dependencies, no `.github/workflows/` edits,
`verification-metadata.xml` untouched. Low-RAM: one bounded job (two float
anims, no poller), cancelled on every new gesture; idle editors cost zero.
