# Phase 208 REPORT — Page Management UX: Trash-Search Safety, Sort, Move/Duplicate, Multi-Select, Palm-Reject Persistence

**Date:** 2026-08-25 · **Status:** DONE (all five fixes shipped, tests green)
**Prior attempts:** commits `5cb79f7` / `1749cfc` / `6756b75` were marker-only retries (`.attempts`/`.blocked`/`.no_work`) with ZERO code — this is the first real implementation of the phase.

---

## 1. Fix #1 (CRITICAL data-loss): searching on the Trash tab rendered LIVE notes as trash cards

### The bug
`HomeScreen.kt` built `activePageList = globalSearchResults ?: emptyList()` whenever a query was present — **globally, regardless of tab**. Search results come only from non-deleted rows (`NotePageDao.searchPages`, `Daos.kt:128` `WHERE deleted = 0`; same for the cached corpus), yet card rendering applied `isTrash = selectedTab == 3`. So typing a query while on the Trash tab rendered perfectly healthy live notes WITH the Restore / **Delete Permanently** overflow menu (`NotePageCard`) — one tap away from unrecoverable deletion of a live note.

### The fix
New pure-JVM `services/TrashSearchScopePolicy.kt`:
- `scopeFor(selectedTab, hasQuery)` → `LIVE_RESULTS` for every tab × query combination EXCEPT query+Trash = `TRASH_INTERSECT` (unknown tab indices fail safe to LIVE_RESULTS);
- `scoped(results, scope, trashedIds, idOf)` intersects results with the actually-trashed ids in Trash context;
- HomeScreen now routes search results through the policy BEFORE rendering (`HomeScreen.kt`, "Phase 208 fix #1" block). Today's backend returns no deleted rows, so query+Trash yields the honest **scoped-empty state**; if the backend ever grows trashed coverage, the intersection keeps rendering correct trash cards instead of resurrecting the bug.
- Non-query branches untouched (`3 -> trashedPages` preserved); Empty Trash button untouched.

### Tests
`TrashSearchScopePolicyTest` (8): the full 4-tab × 2-query matrix, unknown-tab fail-safe, intersection semantics, the exact bug scenario proving an empty list, order preservation, passthrough.

## 2. Fix #2: sort control

- New pure-JVM `services/PageSortPolicy.kt`: modes `UPDATED_DESC` ("Updated ▼", default), `CREATED_DESC` ("Created ▼"), `TITLE_ASC` ("Title A-Z"). Pinned notes float to the top in every mode (preserves the DB's `ORDER BY pinned DESC` invariant); TITLE_ASC is case-insensitive with an updatedAt-DESC tie-break; `sortedWith` stability preserves DAO order among equal keys.
- `SettingsManager.pageSortModeKey` (pref `page_sort_mode`) sanitized through the policy on read AND write; unknown keys fail closed to the default.
- UI: a sort icon-button (`Icons.AutoMirrored.Outlined.Sort`) sits at the end of the Pages-tab view-mode chip row; its dropdown lists the three modes with a check on the active one and persists each choice. Applied client-side to the already-collected list (no schema change) so List/Gallery/Kanban/Calendar/Table all inherit it. Search-result relevance ordering is deliberately NOT resorted.

### Tests
`PageSortPolicyTest` (10): fail-closed decode + sanitize round-trip, per-mode goldens, pinned-first in every mode, legacy-order parity, stability, immutability, empty/singleton.

## 3. Fix #3: Move/Duplicate UI (backend existed, zero call sites)

- **Repository** `duplicatePage(pageId)` (`NoteRepository.kt`): ONE `db.withTransaction` copying the page row + every stroke row + the plaintext tags column. Encryption contract honored: title/body are DECRYPTED under the source record AAD and RE-ENCRYPTED under the NEW page id (a verbatim ciphertext copy would fail GCM authentication); each stroke gets a fresh id + fresh `strokes|<newId>|*` AAD (the legacy id embedded inside pointsJson is inert — load identity comes from the row). Fail-closed semantics: missing source / undecryptable title or body abort the whole duplicate (never persisting the UNREADABLE_MARKER per phase-169); undecryptable or over-budget individual stroke rows are skipped (they render as nothing anyway, B2-DOS-01/B1-DB-8 semantics); pdf/image pages get their backing file COPIED to a new imports-root name (two pages sharing one path would break on delete because `deletePagePermanently` removes it) — a failed byte copy aborts document-backed duplicates. Copied strokes carry `layerId = null` so they never dangle into the deliberately-not-copied layers/media/embeds (documented scope).
- **ViewModel** `duplicatePage(id)` (guarded by `writeGuardedAgainstLock`, one honest snackbar per outcome) + bulk helpers `bulkTrashPages/bulkRestorePages/bulkDeletePagesPermanently/bulkMovePages/bulkAppendTags`.
- **UI**: both card menus gained **"Move to Section…"** (icon `DriveFileMove`) and **"Duplicate"** (icon `ContentCopy`) — the List `NotePageCard` menu (Rename/Edit Tags → Move/Duplicate → Move to Trash) and the Gallery `GalleryCardItem` menu (Pin/Edit Tags → Move/Duplicate → Move to Trash). New shared `SectionPickerDialog` (HomeScreen) lists the current notebook's sections with a check marking the note's current section and routes to `movePage`.

### Tests
`DuplicatePagePolicyTest` (7): copy-suffix derivation (incl. no-stacking + blank titles) and the bulk verb table per context; `Phase208PageManagementTest` pins the transaction/AAD/fresh-id/file-copy wiring in the repository.

## 4. Fix #4: multi-select + contextual bulk bar

- Long-press ANY card (list view AND gallery grid) enters selection mode with that page; taps then toggle membership (gallery cards use `combinedClickable`, list cards too — the phase-36 shared-element morph fires only on real opens).
- A contextual bar renders above the list when a selection exists: "**N selected**" + verbs from `DuplicatePagePolicy.bulkVerbs(context)`:
  - Live tabs: **Move to Trash** (recoverable), **Move to Section…** (shared picker), **Tag…** (append-only dialog), Close.
  - Trash tab: **Restore**, **Delete Permanently** (behind a NEW `"bulk_page_perm"` confirmation dialog — never a bare tap).
- Bulk tag edit APPENDS comma-separated tags to each selected note (deduped case-insensitively) — never a wholesale replacement of each note's own tagging.
- Selection resets on tab change (`LaunchedEffect(selectedTab)`) so ids can never feed another tab's verbs. **Empty Trash untouched.**

### Screenshots-in-words
- *Gallery grid, selection mode:* three tiles carry a bold primary border, tinted secondary-container fill, and their type badge replaced by a white check-circle on primary; the top of the pane shows a pill-shaped secondaryContainer bar reading "3 selected" with trash/move/tag/close icons at the trailing edge.
- *List card menu:* Rename · Edit Tags · **Move to Section…** · **Duplicate** · Move to Trash (trash-context cards keep Restore / Delete Permanently).
- *Section picker:* centered alert titled "Move to Section…" with folder-icon rows (current section check-marked) and Cancel.
- *Bulk delete confirm:* "Permanently Delete 4 Notes?" — "This action cannot be undone…".

### Tests
`Phase208PageManagementTest` source-pins long-press entry in both views, verb-table rendering, the confirm gate, tab-change reset, and the untouched Empty Trash path.

## 5. Fix #5: palm-rejection persistence + surfaced chip

- `SettingsManager.palmRejectionEnabled` (pref `palm_rejection_enabled`, default true = long-standing behavior).
- `EditorScreen` seeds `palmRejectionEnabled` from settings (was plain `remember { true }` — reset EVERY editor open); the ⋮ menu toggle now writes the pref back.
- New **Palm Rejection** switch row in the Canvas & Paper Options sheet (`CanvasSettingsBottomSheet`, next to Haptic Feedback: DoNotTouch icon, title, "Ignore touches from your palm while writing with the stylus") sharing the same pref — one state behind both surfaces.

### Tests
Source pins: pref key exists, editor seeds from prefs, BOTH toggle surfaces persist (≥2 write sites).

---

## Verification

| Command | Result |
|---|---|
| `gradle assembleDebug` | **green** (BUILD SUCCESSFUL) |
| `gradle testDebugUnitTest` | **2943 total / 3 failures** — all pre-existing/environmental and reproduced IDENTICALLY on a clean stashed tree immediately after: `Phase148UiFailureTextScrubTest` (UNC-path, documented in AGENTS.md), `PaparazziSmokeTest` ×2 (layoutlib env). |
| New suites | `TrashSearchScopePolicyTest` (8) + `PageSortPolicyTest` (10) + `DuplicatePagePolicyTest` (7) + `Phase208PageManagementTest` (12) — all green. |

## Constraints honored
- **No schema change** (duplicatePage copies rows within schema v9; sort/palm prefs are SharedPreferences).
- No new dependencies; `.github/workflows/` untouched; base-APK rule intact.
- Encrypted-at-rest discipline: duplicate re-encrypts under new record AAD; no plaintext fallbacks; no keys/content logged.

## Known scope limits (documented, deliberate)
- Duplicate copies row + strokes + tags only (per PROMPT) — layers/sticky notes/media embeds/voice notes are not copied; duplicated ink lands on the default layer.
- Section picker offers the current notebook's sections (`movePage` takes a section id; cross-notebook move out of scope).
- Kanban/Calendar/Table views render menus unchanged (list + gallery carry the new verbs; bulk selection covers list + gallery).
