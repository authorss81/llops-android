# Phase 73 — B2-UI-3: Unsynchronized shared `lastSavedStrokeHash` HashMap + overlapping debounce/dispose flush can drop strokes or poison the map

## Finding

`docs/security-report.md` B2-UI-3 (MEDIUM). `NoteRepository.lastSavedStrokeHash`
was a plain `LruBoundedMap` (access-order `LinkedHashMap`) shared by ALL pages,
mutated from the stroke-load path (`getStrokesForPage`, no transaction) AND the
stroke-save path (`saveStrokesForPage`, inside `withTransaction`) on different
coroutines with NO synchronization. Two concurrent saves for the same page could
interleave the HashMap read-modify-write: an older snapshot's hash commit can land
last (the newer stroke's `changed` diff is skipped → newest stroke silently not
inserted → data loss), the map's internal link list can corrupt under concurrent
mutation (`ConcurrentModificationException`), or two `withTransaction`
delete+upsert rounds interleave and drop rows. Independently, `EditorScreen`'s
dispose flush (`NonCancellable`) could overlap the 1 s debounced autosave window,
so a stale snapshot's write could fire AFTER the final flush and land last. Pure
concurrency defect — no crypto involvement, no schema change required.

## What changed (file:line)

### `data/repository/NoteRepository.kt`

- **`:911-912`** — `lastSavedStrokeHash` is now `Collections.synchronizedMap(LruBoundedMap<String, Int>(…))`
  (KDoc updated `:889-910`): every individual get/put/remove is atomic while the
  B2-DOS-10 LRU bound (phase-100) is preserved. `imports` added: `java.util.Collections`
  (`:26`), `java.util.concurrent.ConcurrentHashMap` (`:28`), `kotlinx.coroutines.sync.Mutex`
  (`:22`), `sync.withLock` (`:23`).
- **`:944`** — NEW `private val pageSaveLocks = ConcurrentHashMap<String, Mutex>()` (KDoc
  `:924-943`): one fair/FIFO `Mutex` per page, deliberately never evicted (a lock held by an
  in-flight save must not be recreated), growing only with the distinct pages edited in a
  session (naturally bounded by the vault's B2-DOS budgets).
- **`:986-988`** — `saveStrokesForPage` acquires the page lock first:
  `val lock = pageSaveLocks.computeIfAbsent(pageId) { Mutex() }` → `lock.withLock { … }`
  wrapping the whole existing `db.withTransaction { diff + delete-stale + insert-changed +
  hash-commit }`. The diff + hash commit (the compound read-modify-write) now runs under the
  per-page mutex, so a newer save can never have its hash commit overwritten by an older one.
- **`:1165-1167`** — `saveMediaEmbedsForPage` wrapped in the same per-page lock (KDoc
  `:1157-1164`): the full-page flush's strokes → embeds → layers writes share ONE lock per
  page, keeping a page's whole snapshot atomic.
- **`:1244-1246`** — `saveLayersForPage` wrapped in the same per-page lock (KDoc
  `:1238-1243`).

### `ui/viewmodel/NoteflowViewModel.kt`

- **`:2875-2892`** — NEW `fun disposeEditorPageFlush(pageId, strokes, stickyNotes, embeds,
  layers, pendingDebounce: Job?)`: launches in `viewModelScope` (survives editor teardown),
  `pendingDebounce?.cancel()` stops a stale debounce from ever firing, `pendingDebounce?.join()`
  settles any write the debounce already dispatched, THEN `flushEditorPageSave(...)` persists
  the final (newest) snapshot. KDoc `:2861-2874`.
- **`:2907-2920`** — `autosaveStrokes` is now `suspend fun` and runs the strokes-only write
  INLINE via the new `persistEditorSaveSuspend` (KDoc `:2894-2906`). The editor's debounce
  `saveJob` now covers delay + actual persistence, so it is cancellable and awaitable by the
  dispose flush.
- **`persistOrDefer`** — refactored to launch `persistEditorSaveSuspend` on
  `Dispatchers.IO`; all existing fail-closed behavior preserved: `VaultLockedWriteException` /
  null-key catch → stash for after unlock, `CancellationException` rethrown.

### `ui/screens/EditorScreen.kt`

- **`:443-451`** — `DisposableEffect(page.id)` `onDispose`: captures `val pending = saveJob`,
  nulls the slot (`saveJob = null`), and routes through
  `viewModel.disposeEditorPageFlush(page.id, strokes, stickyNotes, mediaEmbeds, layers, pending)`
  instead of the previous direct flush (comments `:429-442`). Still gated on
  `isInitialLoadComplete`, still never runs the write post-lock (B2-UI-1 preserved).
- **`:513-529`** — `triggerAutoSave` keeps the 1 s debounce; the comment documents that the
  debounce job now includes the persistence, so cancel/await ordering is guaranteed.

## The vulnerability path (before/after)

```
Before:  save1(page, olderSnapshot)  … commit hashes(H1)          // inside withTransaction
         save2(page, newerSnapshot)  … compute changed vs H1
           → H1 commit lands AFTER save2's diff?  ⇒ newer stroke skipped  → data loss
         OR  two delete+upsert withTransaction rounds interleave      → rows dropped
         OR  load() and save() mutate the same LinkedHashMap          → ConcurrentModificationException
         EditorScreen dispose flush (NonCancellable) overlaps the 1s debounce
           → a STALE snapshot fires after the final flush and lands last

After:   save1(page, older) → pageSaveLocks["p"].withLock { withTransaction { diff+commit } }
         save2(page, newer) → blocks on the same fair mutex, runs entirely AFTER save1
                              → newest hashes commit last, newer stroke always inserted
         load()/save() map ops are individually atomic (Collections.synchronizedMap)
         dispose flush: cancel() the debounce → join() any in-flight write → flush() newest
                              → the final snapshot always lands last
```

## Verification

- `gradle :app:testDebugUnitTest --tests "com.authorss81.noteflow.B2Ui3StrokeSaveConcurrencyTest"`
  → **BUILD SUCCESSFUL, 11 tests green** (after fixing one incorrect assertion in the new test
  itself, not in app code).
- `gradle :app:testDebugUnitTest` → **BUILD SUCCESSFUL, 1447 test methods, 0 failures / 0 errors**
  (aggregated from the JUnit XML reports). Baseline before this phase was 1436 green
  (the two B1Plat01ReleaseSigningTest asserts were repaired by commit `b9a0b52`), so this phase
  adds 11 tests with **no regression**.
- `gradle :app:assembleDebug` → **BUILD SUCCESSFUL** (57 task outcomes).

## New test coverage (`app/src/test/.../B2Ui3StrokeSaveConcurrencyTest.kt`, 11 tests)

The Android side needs Room + the encryption stack, so the behavioral proof is a faithful
pure-JVM model of the repository save path driven by the SAME primitives the fix uses
(`Collections.synchronizedMap(LruBoundedMap)`, per-page fair `Mutex`, `withLock`), plus
source-level wiring pins.

Behavior — the diff-cache map:
- 8 threads × 2000 RMW iterations (get→put→remove→put) never throw, never exceed the LRU
  cap, and every written key is still present exactly once (no lost/duplicated entries).
- concurrent read+write through the LRU eviction boundary stays safe, cap holds.

Behavior — per-page FIFO mutex serialization:
- a later-issued (newer, strict superset) snapshot commits AFTER an in-flight older one —
  the newest stroke (`s3`) survives, full newest snapshot is the final state, diff cache
  matches.
- two concurrent same-page saves never interleave delete+upsert: both strokes present, newer
  content wins for the shared stroke.
- different pages are NOT serialized: page 2 saves to completion while page 1 holds its lock.

Behavior — dispose flush cancel+await:
- cancels a still-pending debounce → stale snapshot never fires, only the newest persists.
- awaits an already-started stale write before flushing → newest content lands last.
- no pending debounce → simply flushes the newest snapshot.

Source pins (the Android wiring):
- `NoteRepository`: `lastSavedStrokeHash` backed by `Collections.synchronizedMap(LruBoundedMap`,
  `pageSaveLocks = ConcurrentHashMap<String, Mutex>()`, and all three of
  `saveStrokesForPage` / `saveMediaEmbedsForPage` / `saveLayersForPage` contain
  `pageSaveLocks.computeIfAbsent(pageId) { Mutex() }` + `lock.withLock {`.
- `EditorScreen`: dispose block captures `val pending = saveJob`, nulls `saveJob`, and calls
  `viewModel.disposeEditorPageFlush(page.id, strokes, stickyNotes, mediaEmbeds, layers, pending)`.
- `NoteflowViewModel`: `autosaveStrokes` is `suspend fun`; `disposeEditorPageFlush` contains
  `pendingDebounce?.cancel()`, `pendingDebounce?.join()`, and `flushEditorPageSave(...)`.

## Checksums / secrets handling

- No keys, passwords, or decrypted note content logged/persisted. The change touches
  concurrency primitives and job lifecycle only.
- `allowBackup=false`, `ClipboardGuard`, FLAG_SECURE, and the B2-UI-1 lock-safe flush gate
  (locked ⇒ stash + encrypted-after-unlock, never plaintext) kept intact.
- `LruBoundedMap` itself stays a plain access-order `LinkedHashMap`; only its single
  repository call-site is synchronized (the class KDoc documents the phase-73 wrapper).

## Out of scope (documented, not fixed here)

- `B2-UI-4` (unlock never re-establishes session flows) and `B2-UI-5` (non-atomic markdown
  `File.writeText`) are separate findings with their own phases — untouched.
- `pageSaveLocks` entries are never evicted (see `:938-942` rationale); if a future phase
  wants bounded lock storage it must coordinate with in-flight saves — noted, not changed.
- The phase-27 shape auto-snap and wet-brush paths that call `saveStrokesForPage` were
  verified to route through the same repository method (and therefore now the same lock); no
  other write path mutates `lastSavedStrokeHash` besides the load + save paths (grepped).

## Constraints honored

- No DB schema change, no migration (concurrency fix only).
- No new dependencies (`Mutex`/`withLock`/`ConcurrentHashMap`/`Collections` are stdlib/JDK).
  `.github/workflows/` untouched.
- Do-not-fix rule: only the B2-UI-3 surface touched.
- API floor (26+): no new API usage — `synchronizedMap`, `ConcurrentHashMap`, and
  kotlinx `Mutex` are all API-agnostic; no fallback notice needed.
