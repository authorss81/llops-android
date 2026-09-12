# Phase 257 — Undo resurrect + unkeyed page state + load-vs-save race

## Summary
Phase 257 verified and completed the fixes for three canvas-data hazards and
(additionally) repaired the phase-256 eraser regressions that were shipped
broken (`996be91`) and keeping the unit suite red.

1. **Undo resurrect** — the commit snapshot is now reconciled against the canvas'
   live/lastSeen state (`CanvasStrokeReconcile`), so removing a stroke and
   undoing can never re-add it.
2. **Unkeyed page state** — `layers` / `activeLayerId` in `EditorScreen` were the
   last two `remember {}` pieces not keyed to `page.id`; a page switch could
   bleed layer state across pages.
3. **Load-vs-save race** — `NoteRepository.loadEditorCanvasPage` wraps all of its
   reads in the same per-page `pageSaveLocks` mutex that saves hold
   (`d05b4cb`), and the ViewModel delegates through it.
4. **Phase-256 fallout (pre-existing, fixed in this phase)** — the phase-256
   commit shipped a stroke eraser whose `segment` carved DENSIFIED points
   (deleting ink outside the round mask), dropped the point-less shape-stroke
   anchor fallback in `hitStrokeAt`/`strokeTouchedBy`, and pinned source counts
   that never matched HEAD. All repaired + re-baselined; the suite is green.

## DoD results
| Check | Result |
|---|---|
| `gradle :app:assembleDebug` | BUILD SUCCESSFUL |
| `gradle :app:testDebugUnitTest` | 3683 tests, 0 failures |
| `gradle :app:lintDebug` | 0 errors |

## Evidence table

| Claim | Reality | Status | Evidence |
|---|---|---|---|
| `AnnotationCanvas` strokes effect merges committed + pending-local instead of blind `clear(); addAll()` | 3 effects (strokes / sticky notes / media embeds) now call the shared pure-JVM `CanvasStrokeReconcile.reconcile` after their `LaunchedEffect` pre-seed | VERIFIED + extended | `ui/components/AnnotationCanvas.kt:637-642` (`activeStrokeList` + `lastSeenStrokeIds` + reconcile), `:679-682`, `:696-699` |
| Undo/reopen cannot resurrect a removed stroke | `CanvasStrokeReconcile.reconcile`: appends incoming committed snapshot FIRST (undo order-authoritative), then appends only pending local ids never seen before; an id in `lastSeen` but absent from the incoming snapshot is dropped, so undo can never re-add ink the commit removed | IMPLEMENTED | `services/CanvasStrokeReconcile.kt` (new, pure JVM) |
| `layers` also needs `remember(page.id)` (cross-page layer bleed) | `layers` / `activeLayerId` now `remember(page.id)`; page switch re-keys them and the load callback re-primes `activeLayerId` from the fresh page's layers | IMPLEMENTED | `ui/screens/EditorScreen.kt:560-561`, `:886` |
| `strokes/stickyNotes/mediaEmbeds/undoStack/redoStack/isInitialLoadComplete` keyed to `page.id` | All six are `remember(page.id)` | VERIFIED (present on HEAD) | `ui/screens/EditorScreen.kt:243-246`, `:543-544`, `:855` |
| `handleStrokesChange` must ignore edits before the async load lands | `if (!isInitialLoadComplete) return` at the top | VERIFIED | `ui/screens/EditorScreen.kt:1061-1062` (+ the pre-load cancel-guard at `:1034`) |
| `loadEditorCanvasPage` reads hold the page mutex | `lock = pageSaveLocks.computeIfAbsent(pageId){Mutex()}`; `lock.withLock { }` wraps the 5 reads (strokes, sticky notes, embeds, layers, content) | VERIFIED | `data/repository/NoteRepository.kt:1440`, `:1468-1500` |
| ViewModel delegates to the repository loader | `val data = repository.loadEditorCanvasPage(pageId)` inside the pool guard | VERIFIED | `ui/viewmodel/NoteflowViewModel.kt:4479-4487` |
| Save path still holds the same per-page mutex | `saveStrokesForPage` uses `pageSaveLocks.computeIfAbsent(...).withLock` (source pin in test) | VERIFIED | `data/repository/NoteRepository.kt:1763-1764` |
| No Room schema change / no new dependencies / workflows untouched | None added | VERIFIED | `git diff --stat`, `settings.gradle.kts` unchanged |

## Phase-256 eraser regression repairs (pre-existing failures)
The phase-256 commit shipped UDP-stale code + pins; the full suite was red at
phase start (10 failures on clean HEAD). Repaired here so DoD "0 failures" is
reachable:

| Test | Root cause | Fix |
|---|---|---|
| `StrokeSegmenterTest` (5) + `Phase124EraserTest` (4) | `segment()` returned DENSIFIED lattice points as survivors (deleting ink up to ~8 px outside the real round mask); `hitStrokeAt`/`strokeTouchedBy` lost the point-less shape-stroke start/end anchor fallback | Non-wet carve now emits runs of ORIGINAL `stroke.points`; a point is deleted iff its centerline is inside a mask circle; a mask crossing only the middle of an edge splits the run at the exact `circleSegmentInterval` crossing (`services/StrokeSegmenter.kt` `segment` + new `circleSegmentInterval`, edge-carve EPS `1e-3f`); `polylineTouched(stroke, stroke.points, …)` re-gains the empty-points anchor branch and `strokeTouchedBy` delegates to it |
| `Phase256EraserPrecisionTest` (1) | `commitEraserMutationIfAny()` pin counted the declaration + a prose comment (5 textual hits) as call sites | Pin now counts only line-anchored call sites (`it.trimStart().startsWith("commitEraserMutationIfAny()")` = 3: onDispose/drag-end/drag-cancel) |
| `Phase254CommentTrimTest` (2) | Phase-256 baselines never matched HEAD; reconciliation changed line counts again | Re-baselined to the verified phase-257 tree: AnnotationCanvas raw=8635 code=6933, EditorScreen raw=7339 code=6423, HomeScreen raw=3757 code=3267 (Kotlin `lineSequence` includes the trailing newline, so raw = `wc -l` + 1 on newline-terminated files) |

## Files changed
- `app/src/main/kotlin/com/authorss81/noteflow/services/CanvasStrokeReconcile.kt` (new)
- `app/src/main/kotlin/com/authorss81/noteflow/services/StrokeSegmenter.kt` (carve rewrite + `circleSegmentInterval` + anchor fallback)
- `app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt` (3 effects → reconcile helper)
- `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt` (`layers`/`activeLayerId` page keys)
- `app/src/test/java/com/authorss81/noteflow/Phase257UndoPageStateTest.kt` (new, 11 tests)
- `app/src/test/java/com/authorss81/noteflow/StrokeSegmenterTest.kt` / `Phase124EraserTest.kt` (unmodified — green via the segmenter fix)
- `app/src/test/java/com/authorss81/noteflow/Phase256EraserPrecisionTest.kt` (pin fix)
- `app/src/test/java/com/authorss81/noteflow/Phase254CommentTrimTest.kt` (re-baseline)

`git diff --stat` shows no schema, no dependency, no workflow change; `verification-metadata.xml` untouched.