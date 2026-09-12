# Phase 272 — Pan fling with exponential decay (REPORT)

Panning stopped dead on finger lift. Ported AI Studio's fling: on
PAN/black-space drag end, a release faster than 80px/s keeps panning with
`exponentialDecay(frictionMultiplier=0.8f)` per axis (fast scroll, slow
settle). Any new gesture cancels the fling.

## 1. Evidence table (file:line on the phase-272 tree)

| # | Claim | File:line |
|---|-------|-----------|
| 1 | `velocityTracker` + `flingJob` holder after `coroutineScope`/`debounceJob` | `ui/components/AnnotationCanvas.kt:362-363` |
| 2 | Two-finger block cancels fling first | `AnnotationCanvas.kt:1985-1986` (`if (event.changes.size > 1) {` + `flingJob[0]?.cancel()`) |
| 3 | `onDragStart` cancels fling + `resetTracking()` | `AnnotationCanvas.kt:2289-2290` |
| 4 | PAN branch samples `addPosition(uptimeMillis, position)`, keeps `updateZoomAndPan` | `AnnotationCanvas.kt:2496-2498` |
| 5 | `onDragEnd`: after `isDraggingCard` early-return, PAN fling — `calculateVelocity()`, `abs(y)>80f \|\| abs(x)>80f` AND `CanvasNavigationPolicy.shouldAnimate(reduceMotion)` (review-fix 7b), per-axis `Animatable` + `animateDecay(v, exponentialDecay(0.8f))` driving `updateZoomAndPan`, then `return@detectDragGestures` before SELECT | `AnnotationCanvas.kt:2601-2628` |
| 6 | `onDragCancel` cancels fling first | `AnnotationCanvas.kt:2884` |
| 6b | Tool switch cancels fling (`LaunchedEffect(currentTool)`, review-fix 7c) | `AnnotationCanvas.kt:366-368` |
| 7 | Zoom limits untouched (`0.5f..4.0f`) | `AnnotationCanvas.kt:1990` (same `coerceIn(0.5f, 4.0f)`) |
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

- New `Phase272PanFlingTest` (11 tests): fling holder declared (plain non-State
  array); cancel sites (two-finger, drag-start + reset, drag-cancel,
  tool-switch); reduce-motion gate; PAN-branch sampling; release
  wiring (`calculateVelocity` + `> 80f` + `exponentialDecay`/`0.8f` +
  `animateDecay` + `flingJob[0] = coroutineScope.launch`); zoom-clamp intact;
  threshold boundary semantics; import-free exponential-decay settle math
  (monotone decay, bounded displacement, early-travel majority).
- `gradle :app:assembleDebug` — green.
- `gradle :app:testDebugUnitTest` — **3884 green, 0 failures** (3875 prior +
  9 new). Note: `Phase151MarkdownMainThreadPerfTest` (backtick timing) flaked
  once mid-phase (ratio ~24x on a loaded runner) and passes on re-run on both
  the clean tree and this tree — environmental, untouched by this diff (canvas
  gesture code shares no path with the markdown tokenizer).
- `gradle :app:lintDebug` — 0 errors.

## 6. Review fixes (2026-09-12, findings 7a–7d)

- **7a — fling holder no longer State**: `var flingJob by remember {
  mutableStateOf<Job?>(null) }` → `val flingJob =
  remember { arrayOfNulls<Job>(1) }` (stdlib, no import); all sites use
  `flingJob[0]`. Starting/cancelling a fling can never schedule a
  recomposition now. Pins updated (`Phase272PanFlingTest` state/cancel/release
  assertions + `Phase205CanvasCommitIntegrityTest` fling-block pin).
- **7b — reduce-motion honored**: the release gate ANDs the shared
  `CanvasNavigationPolicy.shouldAnimate(reduceMotion)` gate (same gate as
  `navigateCanvasTo`); under reduce-motion fast releases stop dead (pre-272
  behaviour) instead of animating. Pinned by a new test.
- **7c — tool switch cancels**: new `LaunchedEffect(currentTool) {
  flingJob[0]?.cancel() }` — tool switches restart the gesture `pointerInput`
  scopes but the fling lives in the composition scope, so without this it kept
  panning in the old mode. Pinned by a new test.
- **7d — evidence line numbers re-measured** on the review-fix tree (table
  above); prior ranges were off by up to ~7 lines.
- Re-baselined `Phase254CommentTrimTest` (canvas **8889 raw / 7099 code**,
  +12 / +5, all `//` comments, KDoc openers still 22).

## 7. Constraints honored

No Room schema change, no new dependencies, no `.github/workflows/` edits,
`verification-metadata.xml` untouched. Low-RAM: one bounded job (two float
anims, no poller), cancelled on every new gesture; idle editors cost zero.
