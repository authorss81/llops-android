# Phase 208: Page Management UX — Trash-Search Safety, Sort, Move/Duplicate, Multi-Select, Palm-Reject Persistence [UX]

**Goal:** Five verified page-list/editor gaps, led by one CRITICAL data-loss bug.

1. **Searching while on the Trash tab renders LIVE notes as trash cards → one tap from unrecoverable deletion.** `activePageList` ignores `selectedTab` when a query is present (`HomeScreen.kt:1203-1220`: search results replace the list globally); those results come only from non-deleted rows (`Daos.kt:128 WHERE deleted=0`), yet rendering applies `isTrash = selectedTab == 3` (`HomeScreen.kt:1390`) → live notes get Restore/**Delete Permanently** menu (`:1400-1403`, `:1886`, `:1898`).
   **Fix:** pure-JVM `TrashSearchScopePolicy` deciding result scoping per tab; in Trash context intersect results with trashed ids (or show scoped-empty state). Unit tests cover every tab × query combination.

2. **No sort control anywhere** — order hard-coded `ORDER BY pinned DESC, updatedAt DESC` (`Daos.kt:75,84`); Table/Gallery/Kanban/Calendar inherit it.
   **Fix:** client-side sort of already-collected lists keyed by persisted `SettingsManager.pageSortMode` (Updated ▼ / Created ▼ / Title A-Z) + sort icon near view-mode chips (`HomeScreen.kt:1145-1180`); pure-JVM comparator policy + tests.

3. **Move/Duplicate have no UI despite backend existing.** `NoteflowViewModel.movePage` (`:3001-3004` → `NoteRepository.kt:865`) has ZERO UI call sites; no duplicate exists anywhere. Menus today: Rename/Edit Tags/Trash only (`HomeScreen.kt:2649-2661`, GalleryView `:270-324`).
   **Fix:** "Move to Section…" (section-picker dialog → movePage) + "Duplicate" (new repository `duplicatePage` single transaction copying row + strokes + tags) in both card menus.

4. **No multi-select/bulk actions** (GalleryView.kt:62-63 admits it): long-press selection mode with contextual bar — trash/restore/move/tag; complements #3 verbs. Keep Empty Trash untouched.

5. **Palm-rejection toggle resets every editor open and hides under ⋮ export clutter** — plain `remember` (`EditorScreen.kt:405-407`), toggled only at `:1943-1957`.
   **Fix:** persist via SettingsManager (pattern: `hapticsEnabled`), seed from VM, surface as a chip in Canvas & Paper Options sheet.

## DoD
`gradle assembleDebug` green; `testDebugUnitTest` green incl. TrashSearchScopePolicy + sort-comparator tests; `workspace/phase-208/REPORT.md` with screenshots-in-words of the new menus/bar. No schema change (duplicatePage copies rows within existing schema), no workflow edits.
