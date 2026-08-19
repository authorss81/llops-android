# Phase 168 — App must always open the last-used notebook

Status: **DONE** (2026-08-19)
Finding source: user feedback — "when the app opens it should always open the
last notebook the user was using (it currently doesn't)".

## Root causes (verified `file:line`)

1. **`lastNotebookId` was NEVER WRITTEN.** It existed only as an accessor in
   `app/src/main/kotlin/com/authorss81/noteflow/services/SettingsManager.kt:94-96`
   (pref key `last_notebook_id`). A repo-wide grep for `lastNotebookId` /
   `last_notebook_id` shows the only production references are that accessor and
   comment/doc copies — no caller ever set it. So the pref was permanently null
   and the cold-start restore could not read a last-used notebook.

2. **Cold start restored from the WRONG pref and required a valid section.**
   `NoteflowViewModel.initializeDataCore()`
   (`app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt`,
   pre-fix at lines 1866-1889) read `settings.activeNotebookId` (a legacy
   "active id" that `selectNotebook` did write), then ALSO read
   `settings.activeSectionId`, and only restored the notebook when
   **both** resolved AND the section belonged to the notebook:
   ```
   if (restoredNb != null && restoredSec != null && restoredSec.notebookId == restoredNb.id) { … }
   else { ensureDefaultNotebookAndSection() … }
   ```
   Any stale/deleted `activeSectionId` fell through to
   `repository.ensureDefaultNotebookAndSection()` — i.e. the fixed `default_nb`
   notebook (or, effectively, the first/random notebook), never the last-used
   one. So even though `activeNotebookId` was current, a stale section pref
   silently discarded the notebook restore.

3. The notebook list loads asynchronously from the DAO flow
   (`repository.notebooks`, `NoteRepository.kt:262`); the synchronous
   pre-selection is decoupled from it, and `observeSections`
   (`NoteflowViewModel.kt:2011-2024`) auto-selects the first section whenever
   the current section is null/stale — so re-arming that observer once the data
   is available already self-heals a missing section, it just never informed the
   notebook choice.

## Fix

`app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt`

- **Write on every selection change.** `selectNotebook()` (single chokepoint for
  the notebook-switcher on HomeScreen `562/573/795/803/861/995`, sidebar, add-
  and-delete-fallback paths) now writes `settings.lastNotebookId = notebook.id`
  alongside the legacy `activeNotebookId` write.
- **Write on exit.** `onCleared()` persists `_selectedNotebook.value?.id` to
  `lastNotebookId` (belt-and-suspenders; the selection change already covers it).
- **Read + apply at cold start (`initializeDataCore`).** New restore block:
  1. Source = `settings.lastNotebookId ?: settings.activeNotebookId` — legacy
     pre-phase-168 sessions whose prefs only ever wrote `activeNotebookId` keep
     restoring.
  2. If that notebook still exists → restore it (persist the resolved id back to
     `lastNotebookId`), then restore the recorded section **only when it belongs
     to that notebook**; otherwise re-arm `observeSections` and let it pick the
     notebook's first existing section. A stale section no longer discards the
     notebook.
  3. If the last notebook was **deleted** → fall back to the **first existing**
     notebook (`repository.getAllNotebooks().first()`) and persist it into
     `lastNotebookId`.
  4. Empty vault → `ensureDefaultNotebookAndSection()` (unchanged default
     behavior), also persisted to `lastNotebookId`.

No schema change, no migration, prefs only. Security model untouched.
`.github/workflows/` untouched. No new dependencies.

## Tests

`app/src/test/java/com/authorss81/noteflow/Phase168LastNotebookRestoreTest.kt`
(new, 10 tests):

Behavior (pure-JVM model mirroring the production restore decision exactly):
- cold start opens the last-used notebook with its exact section;
- stale section pref keeps the last-used notebook (first-section fallback) —
  the pre-fix defect;
- legacy `activeNotebookId`-only sessions still restore;
- deleted last notebook falls back to the first existing and persists the
  fallback id;
- brand-new empty vault opens default_nb + default_sec.

Wiring (source pins, same technique as `B2Ui4UnlockReinitializesStateTest`):
- `SettingsManager` still exposes `last_notebook_id`;
- `selectNotebook` writes `lastNotebookId` (and keeps the B2-UI-4 `activeNotebookId`
  write);
- `initializeDataCore` reads `lastNotebookId`, keeps `activeNotebookId` as a
  fallback, writes back the resolved id, re-arms `observeSections`/`observePages`;
- `onCleared` persists the current selection.

Existing source pin compatibility: `B2Ui4UnlockReinitializesStateTest` (10 tests)
still passes — `initializeDataCore` retains `settings.activeNotebookId` +
`settings.activeSectionId` + `observeSections(`/`observePages(` and
`selectNotebook` retains `settings.activeNotebookId = notebook.id`.

## Verification

- `gradle :app:testDebugUnitTest --tests Phase168LastNotebookRestoreTest --tests B2Ui4UnlockReinitializesStateTest` → green.
- `gradle testDebugUnitTest` → **2285 tests, 1 failure** = the documented
  pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure
  (reproduced on prior clean trees, untouched by this phase).
- `gradle assembleDebug` → green (174,181,730-byte `app-debug.apk`; the first
  invocation had the documented transient daemon failure, retry clean).