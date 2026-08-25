# Phase 205 — Canvas Commit Integrity: Synchronous Stroke Commits, Event-Driven Laser Fade, Direct AGSL Call [BUG/PERF]

Date: 2026-08-25 · Scope: `ui/components/AnnotationCanvas.kt`, `ui/screens/EditorScreen.kt`,
new pure-JVM `services/LaserTrailPolicy.kt` + `services/CanvasCommitListPolicy.kt`, pin updates
in `Phase203SymmetryCaptureBakeTest`. No schema change, no new deps, no workflow edits, base-APK rule intact.

## 1. Stroke commit races → synchronous Main commit + apply-time state derivation

### Before (defect)
The freehand commit launched async from `onDragEnd`:
`coroutineScope.launch(Dispatchers.Default)` (pre-205 `AnnotationCanvas.kt:1389`, scope =
`rememberCoroutineScope()` at the old `:245`) built the stroke + ran shape-snap/RDP off-Main,
then hopped `withContext(Dispatchers.Main)` (old `:1432`) to mutate `activeStrokeList` and emit.
Meanwhile any parent-side change fully refilled the canvas list
(`LaunchedEffect(filteredStrokes) { activeStrokeList.clear(); addAll(...) }`). Three failure modes:

- **(a) lost stroke on disposal** — leaving the editor before the Main hop cancelled the
  `rememberCoroutineScope` coroutine; the finished stroke never reached the parent and autosave
  never saw it.
- **(b) out-of-order commits** — two fast strokes' background computes could finish in either
  order; the Main hops applied them out of gesture order (z-order + undo corruption).
- **(c) resurrection of erased strokes** — the emit rebuilt its payload from
  `strokes.filter { it.pdfPage != pdfPageFilter }` where `strokes` was the value captured by the
  pointerInput closure (frozen for a whole session — pointerInput only restarts on its keys), so
  an erase that landed between capture and apply was overwritten by the stale snapshot.

### After (fix) — `AnnotationCanvas.kt:1428-1520`
- The whole commit pipeline (stroke build → `ShapeRecognitionHelper.trySnapShape` → per-brush RDP
  via `StrokeSimplifyPolicy.epsilonFor` → symmetry twin bake → `activeStrokeList.addAll` → emit)
  runs **inline on Main** before the drag-end handler returns. There is nothing pending to cancel,
  so disposal can no longer drop a committed stroke; ordering IS call order. (RDP/snap are cheap
  geometry passes — the option explicitly blessed by the PROMPT.)
- "Other pages" is derived from **CURRENT state at APPLY time** through the new
  `currentStrokesProvider` param (`AnnotationCanvas.kt:161`; EditorScreen passes
  `{ strokes }` at `EditorScreen.kt:2184` whose delegated state always reads the latest committed
  list) folded into new pure-JVM `services/CanvasCommitListPolicy.emittedList(...)` — byte-identical
  semantics to the old expression, but capture-proof.
- All three full-list emission sites converted: commit `AnnotationCanvas.kt:1503`, eraser
  `:1186`, text dialog `:1803`. Zero `strokes.filter { it.pdfPage != pdfPageFilter }` remain;
  zero `Dispatchers.Default` / `withContext(Main)` remain in the file.

## 2. LASER fade: render-side frame clock, one batched ephemeral removal per wave

### Before (defect)
`AnnotationCanvas.kt` (pre-205 `:315-328`) ran a `while(true) { …filter…; delay(40) }` poll
(25 Hz) keyed on `hasLaserStrokes/strokes.size`. Every expiry emitted a FULL-list
`onStrokesChanged(remaining)` → `EditorScreen.handleStrokesChange` copied the whole list into the
30-deep undo stack (`EditorScreen.kt:863-866` pre-205), cleared redo, recomposed, re-hashed layer
caches, and armed the 1 s autosave → `saveStrokesForPage` Room tx (`NoteRepository.kt:1181-1232`)
— per expired stroke, per fade wave, for an ephemeral pointer highlight.

### After (fix) — `AnnotationCanvas.kt:326-356`
- **Fade is RENDER-SIDE ONLY**: one `LaunchedEffect(hasLaserStrokes)` loop ticks
  `withFrameNanos { }` while any trail exists, bumping `laserFadeTickState`; the main draw pass
  reads it once (`AnnotationCanvas.kt:1915`, draw-phase subscription), so only DRAWING invalidates
  per frame — nothing recomposes and no undo/autosave machinery runs on fade frames.
- **Removal = ONE batched wave** per wake through pure-JVM
  `LaserTrailPolicy.stripExpired(currentStrokesProvider(), now)` — all trails due in the same wave
  leave together in a single emission, order-preserving, computed against CURRENT state.
- **Ephemeral channel**: new `onLaserTrailsExpired` param (`:165`) → EditorScreen's
  `handleLaserTrailsExpired` (`EditorScreen.kt:872-882`) updates `strokes` + arms exactly ONE
  debounced `triggerAutoSave` and NEVER touches `undoStack`/`redoStack`. Undo history is untouched
  by laser expiry by construction.
- The 1800 ms budget is now single-sourced in `LaserTrailPolicy.FADE_DURATION_MS`; the render-side
  alpha ramp consumes the same envelope via `LaserTrailPolicy.fadeFraction`
  (`AnnotationCanvas.kt:4746`), so a trail can never be removed while still visibly drawing.

## 3. AGSL wet-mix carrier — reflection already gone; pinned shut (verify-only)

### Before (historical defect)
Pre-phase-201 code attached the effect via
`Paint::class.java.getMethod("setRenderEffect", …).invoke(p, …)` inside `catch (_: Exception) {}`.
Phase-201's verify step proved `android.graphics.Paint` has NO `setRenderEffect` at ANY API level —
the reflective lookup threw `NoSuchMethodException` every frame, the catch swallowed it, and wet
brushes rendered flat everywhere (never-silent-degradation violation).

### After (verified at HEAD — fixed by phase-201 review fixes, this phase adds the anti-resurrection pins)
- Direct carrier: reusable `RenderNode("inkflow-wet-mix")` with
  `node.setRenderEffect(wetMixingEffect.androidEffect)` (`AnnotationCanvas.kt:4097-4104`, direct
  call at `:4091`), composited via `nativeCanvas.drawRenderNode(node)` inside the dirty-rect
  saveLayer.
- Explicit gates, no silent fallback: AGSL tier `ShaderCapabilityHelper.isAgslSupported` (API 33+,
  gates effect creation `AnnotationCanvas.kt:802-803` and the pass `:3955`) AND hardware-accelerated
  canvas + DrawScope availability (`canUseGpuEffect`, `:4065-4071`); software canvas ⇒ documented
  plain-layer fallback.
- Repo-wide source scan confirms ZERO `.getMethod("setRenderEffect")` / `.getDeclaredMethod(...)` 
  occurrences under `app/src/main/kotlin` (grep evidence below). The prompt's "log once via
  StartupLogPolicy on failure" applied to the deleted reflection path; with reflection gone the
  direct API call behind explicit tier gates leaves no silent-failure surface to log.
- New permanent pins: `Phase205CanvasCommitIntegrityTest` walks every `.kt` under
  `app/src/main/kotlin` and fails if a reflective setRenderEffect lookup ever returns, and asserts
  the direct RenderNode attach stays behind both gates.

Grep evidence (HEAD, post-fix):
```
$ grep -rn "getMethod(\"setRenderEffect\"" app/src/main/kotlin   → (no matches)
$ grep -n "node.setRenderEffect" app/src/main/kotlin/.../AnnotationCanvas.kt
  :4091  node.setRenderEffect(wetMixingEffect.androidEffect)
```

## Tests

New (all green):
- `services/LaserTrailPolicyTest` (10): fade envelope (null-ts/full/linear/clamps), exact
  1800 ms expiry boundary, null-timestamp never expires, non-laser never expires,
  null wave when nothing due, ONE wave removes ALL due trails together, survivor order preserved,
  two-burst model converging in exactly 2 emissions.
- `services/CanvasCommitListPolicyTest` (5): paginated derivation parity (other-pages-first +
  scoped order), continuous-mode passthrough, rapid sequential commits land in GESTURE order,
  erase→commit interleaving never resurrects (incl. cross-page case), plus a counter-model proving
  the captured-snapshot derivation resurrects (documents why the provider matters).
- `Phase205CanvasCommitIntegrityTest` (8 source pins): no `Dispatchers.Default`/Main-hop in the
  canvas, no `coroutineScope.launch` around the drag-end commit, ≥3 emission sites routed through
  `CanvasCommitListPolicy` with provider reads, EditorScreen wires `{ strokes }`, `delay(40)` poll
  gone, inline `/1800f` gone, frame-clock + draw-phase subscription present, ephemeral channel
  wired, `handleLaserTrailsExpired` body contains NO undo/redo writes and exactly one
  `triggerAutoSave(`, repo-wide zero reflective setRenderEffect, direct RenderNode carrier behind
  `isHardwareAccelerated` + `isAgslSupported` gates.

Pin maintenance (invariant preserved, structure updated):
- `Phase203SymmetryCaptureBakeTest`: two pins referenced the pre-205 ASYNC commit mechanics
  (`coroutineScope.launch(Dispatchers.Default)` anchor; `onStrokesChanged(otherStrokes +
  activeStrokeList)` literal). Updated to assert the SAME invariants against the synchronous
  structure: mode+axis-center still frozen BEFORE `val candidateStroke = Stroke(`, and twin batch
  still applied in ONE `onStrokesChanged(` after `addAll(commitBatch)`.

Verification runs (system gradle, CI Linux runner):
- `gradle :app:testDebugUnitTest`: **2801 tests**, 4 failures ALL pre-existing/environmental and
  reproduced independently of this diff:
  - `PaparazziSmokeTest` ×2 — paparazzi layoutlib init crash inside
    `app.cash.paparazzi.internal.Renderer.configureBuildProperties`; **reproduced on a clean
    stash of this phase's entire diff** (`git stash -u` → same failures).
  - `Phase148UiFailureTextScrubTest` — the known UNC-path failure documented in AGENTS.md.
  - `Phase151MarkdownMainThreadPerfTest` — the timing/concurrency flake documented in AGENTS.md
    (phase-177 note); **passes in isolation with this diff applied** (re-run twice, green), and it
    exercises the markdown typing pipeline, which this diff does not touch.
- `gradle :app:assembleDebug`: **green**.

## Out-of-scope observation (follow-up candidate)

The sticky-note (8 sites) and media-embed (9 sites) emit paths share the same frozen-capture
derivation pattern (`stickyNotes.filter { it.pdfPage != pdfPageFilter }` /
`mediaEmbeds.filter { ... }`) but are all SYNCHRONOUS dialog/gesture handlers — there is no async
commit race there, so none of this phase's three defects fire on those paths. Converting them to
the provider pattern is a mechanical follow-up if a defect ever shows up there; deliberately not
bundled here to keep this diff surgical.

## Before/after summary table

| Behavior | Pre-205 | Post-205 |
|---|---|---|
| Pen-up → parent sees stroke | next Main hop after Default-dispatcher compute (cancellable window) | before `onDragEnd` returns (`AnnotationCanvas.kt:1428-1520`) |
| Two fast strokes | could apply out of gesture order | apply in call order, guaranteed |
| Erase during commit window | erased strokes resurrected from captured snapshot | payload derived from provider at apply time (`CanvasCommitListPolicy`) |
| Laser fade animation | driven by recomposition churn from the 25 Hz poll | frame-clock + draw-phase alpha ramp (`:341-356`, `:1915`, policy `fadeFraction`) |
| Laser removal | one FULL-list emit per expired stroke per 40 ms tick | one batched `stripExpired` wave per due set |
| Undo/redo on laser expiry | full-list push + redo clear per tick | never touched (`handleLaserTrailsExpired`) |
| Persistence per fade wave | autosave armed per tick | exactly one debounced arm per wave |
| AGSL wet-mix attach | (pre-201) silent reflective failure; since 201 direct RenderNode | unchanged behavior; regression-pinned repo-wide |
