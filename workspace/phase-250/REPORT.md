# Phase 250 — Data-loss criticals: stale autosave + lock-during-load wipes page

Date: 2026-08-30. Spec: `workspace/phase-250/PROMPT.md` (verified against repo). Closes the two
**CRITICAL** data-loss paths from `AUDIT_2026-08-30.md` / the 5-of-5 audit.

---

## Bug 1 (CRITICAL) — stale autosave can land AFTER a newer `flushPendingSaves`, overwriting the newest flush

**Root cause (pre-fix, `NoteflowViewModel.kt``:4034-4053` + `EditorScreen.kt``:1011-1029`):**
`triggerAutoSave` debounces with `delay(1000)` inside a `viewModelScope.launch(Dispatchers.IO)` job and then
dispatches its write via `viewModel.autosaveStrokes(...)` → `persistEditorSaveSuspend` → `repo.saveStrokesForPage`.
None of these re-checked freshness between the debounce resuming and the DB write landing. When the user's
back-press called `flushPendingSaves`, the `pendingDebounce?.cancel(); pendingDebounce?.join()` settle returns
as soon as the cancellation is applied — it does NOT wait for a write that had already passed its cancellation
point to drain. The stale autosave (older state) could therefore commit LAST, over the newer flush's write,
and the newest stroke disappeared on page reopen.

**Fix:** a monotonically increasing, `@Volatile` `editorSaveGeneration: Int` token
(`NoteflowViewModel.kt:216`).
- Every write-entry bumps it and stamps its write:
  - `flushPendingSaves` bumps FIRST (`:4095`, before the cancel/join/flush) and
    `flushEditorPageSave` bumps (`:4020`) so the flush's full-page snapshot carries the newest token.
  - `triggerAutoSave` (EditorScreen `:1050`) does `val myGen = ++viewModel.editorSaveGeneration` BEFORE
    launching the debounce and passes `myGen` into `viewModel.autosaveStrokes(..., generation = myGen)` (`:1055`).
  - `saveLayersGated` bumps (`:4149`).
- The generation check is at the START OF THE WRITE — inside `persistEditorSaveSuspend`
  (`NoteflowViewModel.kt:4250` entry check + `:4264` re-check immediately before `unlockedPersist`),
  the single entry into `repo.saveStrokesForPage` for the autosave path. This is intentionally NOT at the UI
  layer, preserving the plugin / WebDAV contract. A stale autosave whose stamped token is no longer the newest
  is logged and skipped — it can never overwrite the newest snapshot.
- `flushPendingSaves` body remains wrapped in `withContext(NonCancellable)` (phase-249 Bug 2), so it still
  completes under cancellation; the generation token is the new ordering invariant on top of it.

## Bug 2 (CRITICAL) — lock during page load wipes the page

**Root cause (pre-fix, `EditorScreen.kt:866-884`):** `LaunchedEffect(page.id)` read the page once via
`loadEditorCanvasPage(page.id)`, checked `if (viewModel.authenticated.value)` a single time, then
unconditionally ran `isInitialLoadComplete = true` AFTER the branch. If `lock()` fired between the read
returning and `isInitialLoadComplete = true`, the branch was skipped, `strokes/layers/...` stayed at their
initial `emptyList()`, and `isInitialLoadComplete = true` was still set — so a back-press called
`flushPendingSaves(strokes = emptyList())` and DELETED every stroke row from the DB.

**Fix:**
- The load effect is re-keyed on `isAuthenticated` too (`LaunchedEffect(page.id, isAuthenticated)`,
  `EditorScreen.kt:885`) so a locked then unlocked page reloads; the auth gate is re-checked at the
  ASSIGNMENT MOMENT (inside the `if (viewModel.authenticated.value) { ... }` block), and
  `isInitialLoadComplete = true` now lives INSIDE that block (`:906`) — it is never set after it.
- If the gate dropped in the load window, the page is kept "loaded-but-locked": `isInitialLoadComplete = false`
  (`:911`) and a session-scoped `loadFailedDueToLock = true` (`:912`) is set, so
  `triggerAutoSave`/`flushPendingSaves` are no-ops and a back-press can never wipe the page.
- The two back paths — `BackHandler` (`:1761`) and the top-bar navigation back `IconButton` (`:1797`) —
  now gate the flush on `!loadFailedDueToLock` BEFORE calling `viewModel.flushPendingSaves(...)`.
- Because `MainActivity.kt:623-624` swaps the whole app content for `LockScreen` when `authenticated` drops,
  an already-loaded page is disposed (its `DisposableEffect.onDispose` defers the edited snapshot encrypted
  via the phase-49 gate) — the durable flush path is unaffected; `isInitialLoadComplete` is only ever left
  `false` in the genuine never-loaded-during-lock case.

---

## Tests (8 new)

### `app/src/test/java/com/authorss81/noteflow/Phase250DataLossCriticalsTest.kt`

Generation-token semantics (pure-JVM `GenerationGate` model):
1. `a save stamped with an older generation is skipped after a newer flush bumps it` — gen-1 stale save is a no-op after gen-2 bump.
2. `the current generation save is still committed after bumping` — the newest token commits.
3. `an in flight save whose generation was superseded mid gate is skipped - newest wins` — the stale autosave's write entry after the flush is dropped; the flush commits.

Source pins (repo purity: no Compose/Room in the unit suite):
4. `source pin - generation is bumped before every flushPendingSaves and every triggerAutoSave` — `bumpSaveGeneration()` precedes `flushEditorPageSave(` in the VM; `++viewModel.editorSaveGeneration` precedes `autosaveStrokes(` in EditorScreen.
5. `source pin - flushPendingSaves body wraps the write in withContext NonCancellable` — `withContext(NonCancellable)`, `pendingDebounce?.cancel()`, `pendingDebounce?.join()` preserved.
6. `source pin - write entry skips a stale generation (persistEditorSaveSuspend)` — the gate runs `if (!isCurrentSaveGeneration(generation))` at the write entry.
7. `source pin - editor load assigns only while authenticated and sets isInitialLoadComplete inside the same if` — `isInitialLoadComplete = true` is INSIDE the authenticated block (not after); the else branch sets `loadFailedDueToLock = true` + `isInitialLoadComplete = false`.
8. `source pin - back paths refuse to flush while loadFailedDueToLock is set` — both `BackHandler` and the top-bar `IconButton` check `!loadFailedDueToLock` BEFORE `flushPendingSaves(`.

---

## Verification

- `gradle :app:testDebugUnitTest` — **3618 tests / 0 failures / 0 errors** (phase-249 baseline 3610 + 8).
- `gradle :app:assembleDebug` — green.
- `gradle :app:assembleRelease` — green (R8 + shrinkResources + signed + lintVital).
- `gradle :app:lintDebug` — **0 errors** (106 pre-existing warnings, none from this phase).

## Constraints honored

- No schema change.
- No new dependencies (uses only existing `kotlinx.coroutines.NonCancellable` + `android.util.Log`).
- No `.github/workflows/` edits.
- The generation-token check lives at the write entry (`persistEditorSaveSuspend` → `repo.saveStrokesForPage`),
  not the UI layer — plugin / WebDAV contracts preserved.
- `loadFailedDueToLock` is session-scoped (a `remember` state), never persisted.
- `verification-metadata.xml` untouched.
- Base-APK-size rule intact (no added dependencies).

## Manual expectation (per DoD)

- Rapid draw → immediate back-press: `flushPendingSaves` persists the committed ink through the lock-safe gate; no dot loss.
- Draw → back-press 800ms later (inside the debounce window): the back-path cancel+await+generation settles the newest snapshot.
- Open page → lock → unlock: the page reloads cleanly (editor is re-keyed on `isAuthenticated`; on lock the editor is disposed and on unlock re-created fresh).
- Open page → lock → back while locked: `loadFailedDueToLock`/`isInitialLoadComplete=false` gate fires — no `flushPendingSaves`, no DB write.

---

## Review-fix round (2026-08-30)

**Findings #1/#2 (HIGH/MEDIUM) — `saveLayersGated` bumped the global generation but wrote ONLY layers, silently invalidating a pending STROKE autosave.**

`saveLayersGated` (`NoteflowViewModel.kt`) previously did `val myGen = bumpSaveGeneration()` and stamped every layer-only write with the newest token. But it persists ONLY `saveLayersForPage` — never strokes. Because `editorSaveGeneration` is a single global token for the whole page, this layers-only write superseded a concurrently-armed stroke-only autosave even though it did not carry the stroke data. Traces:
- Draw → `handleStrokesChange` → `triggerAutoSave` bumps gen=N, arms the 1s debounce (`EditorScreen.kt:1050-1064`) → user toggles layer visibility/opacity/blend → `handleLayersChange` → `saveLayersGated` bumped gen=N+1 → the armed debounce fires with stale gen=N and is skipped at the write entry (`NoteflowViewModel.kt` `persistEditorSaveSuspend`). The just-drawn stroke stayed only in session memory until a later save/flush.
- `onDeleteLayer` (`EditorScreen.kt:1255-1272`) is the sharp case: `handleStrokesChange(updatedStrokes)` (layerId reassignment) is immediately followed by `handleLayersChange(remainingLayers)` → `saveLayersGated`, whose bump killed the reassignment's only stroke DB write; no later full-page flush exists in that function, so a process kill there would lose the reassigned strokes.

**Fix:** the generation token now orders only STROKE data; it is not a global `this supersedes everything` token. `saveLayersGated` no longer bumps and is passed `generation = null` (ungated) through `persistOrDefer` → `persistEditorSaveSuspend`, where the stale-token check is now conditional on `generation != null` (`NoteflowViewModel.kt` `persistEditorSaveSuspend(generation: Int?, ...)`, both entry `:4269` and mid-gate `:4283` checks guarded). The layers-only write persists independently and can neither invalidate nor be invalidated by a stroke-save ordering token. The full-page writers (`flushPendingSaves`/`flushEditorPageSave`, which re-bump `:4095`/`:4020`) and the stroke autosave (`triggerAutoSave` `:1050`) still bump, so the Bug-1 guarantee (a stale autosave can never overwrite a newer FULL-PAGE flush) is fully preserved. Layer-change ordering for rapid successive layer ops is unchanged from pre-250 (ungated) behavior.

**Tests (3 new, total 11):** `Phase250DataLossCriticalsTest` gained (1) a pure-JVM semantic test — a non-bumping (ungated) write leaves the stroke autosave's token current and the stroke write still commits; (2) a source pin — `saveLayersGated` contains no `bumpSaveGeneration()`, passes `generation = null` to `persistOrDefer`, and persists only `saveLayersForPage`; (3) a source pin — `persistEditorSaveSuspend` accepts `generation: Int?` and gates on `generation != null && !isCurrentSaveGeneration(generation)`. The pre-existing "write entry skips a stale generation" pin was tightened to the `generation != null &&` guarded form.

**Verification:** `gradle :app:testDebugUnitTest` — **3621 total / 0 failures / 0 errors** (3618 + 3). `gradle :app:assembleDebug` green. `gradle :app:compileReleaseKotlin` green (full `assembleRelease` signing requires the `RELEASE_KEYSTORE_B64` env var, which CI sets; not present in this local env — code compiles for release, no new release-only code). `gradle :app:lintDebug` 0 errors. Constraints unchanged (no schema, no new deps, no workflows edits, `verification-metadata.xml` untouched, base-APK-size rule intact).
