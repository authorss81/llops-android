# Phase 164 — Tag vault scoped to the current notebook (NOT all notebooks)

Status: DONE

## Bug
The tag vault/explorer (`ui/components/TagExplorerView.kt`) showed tags from ALL
notebooks instead of only the currently selected notebook.

## Previous global source (file:line)
- `TagExplorerView.kt` (pre-fix): the vault loaded the WHOLE vault's pages via
  `viewModel.loadAllActivePages()` (guarded read, but GLOBAL) then built the tag tree
  over them:
  - `TagExplorerView.kt:48` (pre-fix): `val pages = viewModel.loadAllActivePages()`
  - `TagExplorerView.kt:55` (pre-fix): `WikiLinkParser.buildTagHierarchy(pages, …)` —
    the global aggregation over every notebook's pages.
- `NoteflowViewModel.kt:4043-4046` (pre-fix, unchanged): `loadAllActivePages()`
  → `repository.getAllActivePages()` (`NoteRepository.kt:316`), which scans `pages`
  across every section of every notebook.

## Fix (no schema change — page↔notebook link is implicit via `page.sectionId → section.notebookId`)
The scoping happens in the query/ViewModel + parser layers; the Room schema,
DAOs and entities are untouched.

1. **New scoped parser builder** — `WikiLinkParser.buildScopedTagHierarchy(notebookPages, notebookTags, importsRoot)`
   (`services/WikiLinkParser.kt`):
   - scans ONLY `notebookPages` (the selected notebook's active pages) for `#tag`
     full-text mentions — reusing the shared, bounded, cancellable
     `collectTextTags` (extracted from the old `computeTagHierarchy`, which now
     delegates to it, so the whole-vault builder behavior is byte-identical);
   - augments the map with each page's explicit CSV `tags`-field entries
     (`parseCsvTags`), mapped to that page (the app's second tag representation);
   - adds the notebook's OWN tag list (`notebookTags` — tags the user attached to
     the notebook itself, no page member yet) as tag nodes;
   - builds the hierarchical tree via the shared depth-bounded `buildTagTree`;
   - cached per unlock epoch + a scope fingerprint that covers BOTH the page list
     AND the notebook tag list, so switching notebooks / editing a notebook's tags
     recomputes while an unchanged scope reuses the cache (new
     `scopedTagRecomputes` metric). B2-DOS-11 caps preserved
     (`MAX_SCAN_PAGES` / `MAX_TAGS` per map / `MAX_TAG_TREE_DEPTH`) and the build
     is cancellable mid-scan.

2. **New guarded VM accessor** — `NoteflowViewModel.loadScopedTagHierarchy(importsRoot)`
   (`NoteflowViewModel.kt:4058-4069`):
   - reads the SELECTED notebook (`selectedNotebook.value`) and returns empty when
     none is selected;
   - queries ONLY that notebook's pages via `repository.getPagesForNotebookOnce(notebookId)`
     (existing DAO: `pages WHERE sectionId IN (SELECT id FROM sections WHERE notebookId = :notebookId) AND deleted = 0`,
     `Daos.kt:122-123`) and parses the notebook's own CSV tag list;
   - wrapped in the existing `withLockedPoolGuard` (R2-b2b1-UI-01) so a `lock()`
     racing the read is an armed-empty fallback + notice, never a crash.

3. **UI scoping** — `TagExplorerView.kt`:
   - now collects `viewModel.selectedNotebook` and keys its `LaunchedEffect` on
     `(notebookId, selectedNotebook.tags)`, so switching notebooks (or editing the
     notebook's own tags) rebuilds the vault for that notebook only;
   - the build runs directly in the effect's coroutine (removed the
     `rememberCoroutineScope` inner-launch), so a mid-build notebook switch cancels
     the stale notebook's scan before it can overwrite the vault (B2-DOS-11);
   - empty state is the existing `TactileEmptyState(TAG_VAULT)` — a notebook with
     no tags shows the empty vault.

4. **HomeScreen filter hygiene** — `HomeScreen.kt` now clears the active tag filter
   (`activeTagFilterPath`/`activeTagMatchingIds`) whenever the selected notebook
   changes, so a stale cross-notebook tag filter never filters the new notebook's
   pages.

## Scope definition (per the DoD)
A notebook's vault = tags on that notebook's active pages (both `#tag` full-text
mentions AND the pages' CSV `tags` field) + the notebook's own tag list. Tags from
any page/notebook outside the selected notebook can never appear.

## Tests
New `app/src/test/java/com/authorss81/noteflow/Phase164TagVaultScopingTest.kt` (8 tests):
- notebook-A tags never surface in notebook B vault (text tags + notebook tags cross-checked both directions);
- switching notebooks changes the vault and re-scopes the cache (`scopedTagRecomputes`);
- a page moved to another notebook carries its tags into that notebook's vault (and out of the old one);
- page CSV tags + the notebook's own tag list show in the vault; a notebook tag borne by a page maps to that page;
- an empty notebook yields the empty vault;
- source pins: `TagExplorerView` loads via `viewModel.loadScopedTagHierarchy` (not the whole-vault list) and the VM accessor queries `getPagesForNotebookOnce` on `selectedNotebook` (never `repository.getAllActivePages`).

Updated `Phase134LockVaultInflightTest.kt` pin: the tag-explorer guard pin now points
at the new guarded scoped accessor.

## Verification
- `gradle :app:testDebugUnitTest` — 2259 tests, 1 failure = the documented
  pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure (reproduced on a
  clean stash in prior phases — untouched by this diff; no changes to
  `UiFailureTextPolicy`).
- `gradle :app:assembleDebug` — BUILD SUCCESSFUL.

## Files changed
- `app/src/main/kotlin/com/authorss81/noteflow/services/WikiLinkParser.kt` (scoped builder + shared helpers + metric)
- `app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt` (`loadScopedTagHierarchy`)
- `app/src/main/kotlin/com/authorss81/noteflow/ui/components/TagExplorerView.kt` (notebook-keyed scoped load)
- `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/HomeScreen.kt` (clear stale tag filter on notebook switch)
- `app/src/test/java/com/authorss81/noteflow/Phase164TagVaultScopingTest.kt` (new)
- `app/src/test/java/com/authorss81/noteflow/Phase134LockVaultInflightTest.kt` (pin updated)

No `.github/workflows/` changes. No security-model changes. No DB schema/migration changes.