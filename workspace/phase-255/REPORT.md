# Phase 255 — Canvas ingest: stale-timestamp dots + batcher tail loss

Parent: `2b56f30` (phases 255-271 AI Studio audit), per PROMPT "on top of
f7510ac/b5842ee". Scope: `AnnotationCanvas.kt` + `StrokeInputBatcher.kt` +
`Phase255CanvasIngestTest` + the pin re-baselines the PROMPT's "0 existing
failures" DoD required once the audit's own co-delivered regressions and a
HEAD compile break were accounted for (see §4).

## 1. Fix summary

### Fix 1 (HIGH) — frozen-prev monotonic gate in the batch drain

**Claim (PROMPT)**: `AnnotationCanvas.kt:2273-2294` froze `prevAcceptedTime`
before `for (sample in batchDrainScratch)`; `isStale(sample.ts, prevAcceptedTime)`
never advanced to the just-accepted sample, so a duplicate-`eventTime` burst
injected zero-distance samples that polluted `WetThrottlePolicy`'s MIN_PX 1.5f
distance gate → `points.size==1` drawCircle dots.

**Reality after fix**: the loop tests every sample against the LIVE
`lastIngestedInputTimestampMs` and the stamp advances per accepted sample. No
`val prevAcceptedTime =` code line exists anywhere (the only remaining mention
is the historiographical comment at `AnnotationCanvas.kt:2315`).

| claim | reality | status | evidence |
|---|---|---|---|
| gate must compare against the live stamp | `if (StrokeBatchPolicy.isStale(sample.timestampMs, lastIngestedInputTimestampMs)) continue` in the drag batch loop | FIXED | `AnnotationCanvas.kt:2328` |
| accepted sample advances the live stamp | `if (accepted) lastIngestedInputTimestampMs = sample.timestampMs` | FIXED | `AnnotationCanvas.kt:2347` (drag batch), `:1621` (dispose flush), `:2359` (newest-sample branch) |
| frozen `prevAcceptedTime` capture removed from code | zero code lines start with `val prevAcceptedTime`; only comment `:2315` mentions it | FIXED | `grep -n "val prevAcceptedTime"` = 0 hits |

Pure-JVM divergence proof (new `Phase255CanvasIngestTest` #1): a two-event
duplicate burst `[1000,1001,1002] + [1002,1003]` through the production
`StrokeInputBatcher`/`StrokeBatchPolicy.isStale` — a frozen gate admits 5/5
(duplicate injected), the live gate admits 4/5 (duplicate dropped). The
strategies diverge exactly on the duplicate.

### Fix 2 (MEDIUM) — single uptime clock (eventTime preferred)

**Claim (PROMPT)**: producer stamps `motionEvent.eventTime`/`getHistoricalEventTime(h)`
but the drag fallback used `change.uptimeMillis` (different dispatch layer;
120 Hz panels show off-by-1), so the first live sample after a batch could be
wrongly stale/duplicate.

**Reality after fix**: the passive bridge captures THIS event's
`motionEvent.eventTime` into `lastTimestampMs` before the drag fallback runs;
the fallback reads it FIRST and falls back to `uptimeMillis` only when no
MotionEvent has been seen yet.

| claim | reality | status | evidence |
|---|---|---|---|
| bridge stamps eventTime | `lastTimestampMs = motionEvent.eventTime` in the passive `pointerInteropFilter` | FIXED | `AnnotationCanvas.kt:1702` |
| fallback prefers eventTime, uptimeMillis only `?:` | `val changeTime = lastTimestampMs ?: change.uptimeMillis`; `upsample? no` — `uptimeMillis` is never a primary stamp (`val changeTime = change.uptimeMillis` = 0 hits) | FIXED | `AnnotationCanvas.kt:2372`, negative pin in `Phase255CanvasIngestTest` |
| advance guarded by acceptance | `if (accepted) { lastIngestedInputTimestampMs = changeTime }` (rejected samples never consume their timestamp) | FIXED | `AnnotationCanvas.kt:2380-2382` |

`HistoryBatchTest` `:192` previously pinned the old form `if (accepted && lastTimestampMs != null) lastIngestedInputTimestampMs = lastTimestampMs`;
re-baselined to the new `lastIngestedInputTimestampMs = changeTime` form plus
the `val changeTime = lastTimestampMs ?: change.uptimeMillis` guarantee
(`HistoryBatchTest.kt:188-194`). Semantics unchanged (advance-only-on-accept).

### Fix 3 (MEDIUM) — dispose flush drains the batcher tail

**Claim (PROMPT)**: mid-gesture navigation `DisposableEffect(Unit)` committed
`activePoints.toList()` WITHOUT draining `strokeInputBatcher` first; the last
10-20 ms tail (2-3 queued ACTION_MOVE samples) was lost.

**Reality after fix**: the dispose flush drains the ring through the SAME
shared `ingestPointerSample` gate as live ink before the dispose Stroke is
built. To make that reachable, `ingestPointerSample` and the per-gesture state
it touches (wet `lastRawWetX/Y/TimeMs` refs + eraser window/spatial bucket)
were hoisted OUT of the drag closure to composable scope; per-gesture resets
stay in `onDragStart` (phase-249 Bug 1 / Bug 4 semantics preserved exactly).

| claim | reality | status | evidence |
|---|---|---|---|
| dispose flush drains the batcher before building the stroke | `if (tool.isFreehandTool && tool != StrokeTool.LASER && strokeInputBatcher.isNotEmpty) { strokeInputBatcher.drainInto(batchDrainScratch); for (sample …) { gate; ingestPointerSample; advance } }` precedes `val ink = activePoints.toList()` | FIXED | `AnnotationCanvas.kt:1610-1623` before `:1624` |
| drained samples flow through the SAME gate | dispose drain calls the hoisted `ingestPointerSample(` with identical box-local coords + monotonic gate + per-accepted advance | FIXED | `AnnotationCanvas.kt:1613-1621`; shared gate `fun ingestPointerSample` `:1405` |
| lasers never committed, drain skipped | guard excludes LASER (replayed timestamp trail, not freehand geometry) | FIXED | `AnnotationCanvas.kt:1610` |
| batcher contract pin for the second consumer | KDoc documents the dispose flush drain runs on the UI thread during composition teardown and must never race the gesture drain; future consumers must not drain from a background coroutine (overflow-at-head would desync the ring) | FIXED | `StrokeInputBatcher.kt:48-57` |

The drag-handler drain remains a separate second drain (`:2311`) — pinned in
`Phase255CanvasIngestTest` (first drain precedes `val ink = …`, second drain
exists further down).

### 1a. Hoisted shared ingestion gate (enabler for Fix 3)

`ingestPointerSample` (was drag-scope-local) is now a composable-scope
function (`AnnotationCanvas.kt:1405`). The five hoisted states are snapshot
state by design — only the gesture/dispose pipelines read/write them, and no
composition reader exists, so writes never invalidate composition
(`:532-546`). `applyEraser` was hoisted with them (`:1295`) so the dispose
flush (which cannot reach a drag closure) can use the whole erase pipeline if
an eraser stroke is mid-flight at teardown. Per-gesture resets preserved:
wet refs in `onDragStart`, eraser window + spatial bucket on the eraser
branch.

## 2. Prerequisite HEAD repair (would not compile)

HEAD `2b56f30` did **not** compile: `gradle :app:compileDebugKotlin` in the
clean `2b56f30` worktree fails — `e: MarkdownPreviewScreen.kt:391:13
Unresolved reference 'savedContent'` (+ `WholeMarkdownEditor` unresolvable at
`:994/:1037/:1088`). Repaired in this phase as a build prerequisite:

| fix | location |
|---|---|
| `savedContent` declared ABOVE the async-load sync effect (commit 33bbecf referenced it before declaring it) | `MarkdownPreviewScreen.kt:395` (decl) before `LaunchedEffect(page.id, initialContent)` `:398` |
| `WholeMarkdownEditor` import added | `MarkdownPreviewScreen.kt:55` |

## 3. Tests

### New: `Phase255CanvasIngestTest` (6)

1. `live gate rejects the cross-event duplicate that a frozen gate admits` —
   pure-JVM divergence on the duplicate burst using the PRODUCTION
   `StrokeInputBatcher` + `StrokeBatchPolicy.isStale`.
2. `within a single event historical times stay monotone and are all accepted`
   — FIFO order + gate identity in the same event.
3. `batch drain tests the gate against the LIVE stamp and never freezes a prev`
   — source pin (no `val prevAcceptedTime =` code, live-stamp gate + advance).
4. `fallback clock prefers the passive bridge eventTime over uptimeMillis` —
   source pin (+ negative pin: `uptimeMillis` never the primary stamp).
5. `dispose flush drains the batcher through the shared ingest gate before building the stroke` — source pin (drain precedes `val ink`, shared gate, both drains exist).
6. `batcher KDoc pins the single-threaded two-consumer contract` — source pin.

### Re-baselined pins (behavior-verified, not weakened)

| file | what | why | evidence |
|---|---|---|---|
| `HistoryBatchTest.kt:188-194` | fallback advance pin → `lastIngestedInputTimestampMs = changeTime` + eventTime-preference guarantee | Fix 2 changed the pin's code form | old form present only in history |
| `Phase254CommentTrimTest.kt` `headRaw`/`headCode` | snapshot re-baselined to phase-255 counts (8533/6895, 7336/6423, 3757/3267 — Kotlin `lineSequence` semantics incl. trailing newline) | phase-254's "fewer-than-parent / exact-parent code" invariants cannot survive later legit code growth: the audit dot-fixes already grew AnnotationCanvas code 6852→6882 at HEAD (pre-existing break), phase-255 again +13 code/+86 raw; the comment-hygiene invariants (provenance markers, KDoc openers, 0 dividers, ≤1 blank run) still hold for all 3 files unchanged | class KDoc re-baseline note; counts verified with the test's own semantics |
| `B2Ui1LockedFlushTest.kt:267`, `B2Ui5MarkdownSaveSerializationTest.kt:308` | flush take(2600)→take(3800) | audit commits grew `flushPendingEditorSaves` (merge-union + `commitLatest` guard) so the pinned strings now sit at char deltas 2879 (`markdownBodySaveCoordinator.issue`), 3131 (`commitLatest`), 3207 (`updatePageBody`), 3431 (`deleteLegacyNoteTextBody`) — past the old window, still INSIDE the function (next member ~5900 chars) | deltas measured; all assertions unchanged in meaning |
| `Phase150CanvasRenderBudgetTest.kt` `the ViewModel raises the one-time layers-capped notice…` | VM-level `repository.getLayerCountForPage(pageId)` pin moved to the relocated invariant; the raw-vs-retained comparison now lives in `NoteRepository.loadEditorCanvasPage` (returns `RepositoryCanvasData.rawLayerCount`) | audit refactored the loader; the phase-150 review-fix-6 ordering (raw read BEFORE bounded load) is re-pinned inside the repo | `NoteRepository.kt:1472` (`getLayerCountForPage`) before `:1473` (`getLayersForPage`); VM `loadEditorCanvasPage` `NoteflowViewModel.kt:4485-4488` feeds `data.rawLayerCount` into `omittedLayerCount` |

## 4. Pre-existing failures — why they are pre-existing (not phase-255)

The PROMPT demands "0 failures". HEAD does not compile, so no HEAD-only run
was possible. All three remaining pre-fix failures read ONLY
`NoteflowViewModel.kt`, which phase-255 never modifies (git status: only
`AnnotationCanvas.kt`, `StrokeInputBatcher.kt`, `MarkdownPreviewScreen.kt` +
tests/docs). Therefore their pass/fail is a pure function of HEAD content:

| pre-fix failure | HEAD cause (verified in the clean `2b56f30` worktree) | disposition |
|---|---|---|
| `B2Ui1LockedFlushTest` (flush encrypted-column write) | `flushPendingEditorSaves` grew past the test's take(2600) window; the pinned behavior IS present at `NoteflowViewModel.kt:4414` | window widened (§3), assertions unchanged |
| `B2Ui5MarkdownSaveSerializationTest` (coordinator issue ordering) | same window issue; `markdownBodySaveCoordinator.issue` present at `:4408` | window widened (§3) |
| `Phase150CanvasRenderBudgetTest` (raw count vs retained) | `getLayerCountForPage` has ZERO hits in the whole VM at HEAD (`grep -c` = 0); the audit relocated the read into the repository | pin moved to the relocated invariant (§3), behavior verified |

## 5. DoD verification

| gate | result |
|---|---|
| `gradle :app:assembleDebug` | BUILD SUCCESSFUL |
| `gradle :app:testDebugUnitTest` | **3665 tests, 0 failures, 0 errors, 0 skipped** |
| `gradle :app:lintDebug` | 0 errors (106 pre-existing warnings, 15 info) |
| `workspace/phase-255/REPORT.md` | this file |

Constraints honoured: no Room schema change / migration, no new dependencies,
`verification-metadata.xml` untouched, `.github/workflows/` untouched,
`allowBackup=false` untouched, no plaintext rows introduced, fail-closed
defaults unchanged.