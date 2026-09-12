# Phase 263 — Home/gallery/tab: restoration + import + overflow — REPORT

## 1. What was done

Rotation (and process death) wiped home-screen state that is expensive or
dangerous to lose: the search query (debounce orphaned → empty vault flash),
the selected tab (jump back to Pages re-targets destructive bulk verbs at the
wrong list), the in-progress multi-selection, the tag filter, and the pending
10-file import dialog. The 4-tab `PrimaryTabRow` clipped its labels at
320–360dp, and 14 compact controls shipped 20–36dp hit areas.

All fixes are pure Kotlin/Compose, no schema change, no new deps,
`.github/workflows/` and `verification-metadata.xml` untouched.

**Restoration (`HomeScreen.kt`)** — converted to `rememberSaveable`:
`searchQuery` (`:182`), `selectedTab`/`pageViewMode` (`:190-191`),
`multiSelectedIds` (`:196`, via `homeStringSetSaver`), `activeTagFilterPath`
(`:205`) + `activeTagMatchingIds` (`:206`, via `homeNullableStringSetSaver`
which preserves null-vs-empty through a boolean header element),
`pendingImportUris` (`:449`, Uris round-trip as strings via
`homeImportUriListSaver` `:77`), `selectedImportOrientation` (`:452`), every
dialog-visibility flag + dialog string state (`:117-120` security/update/
plugins/store, `:164-168` bulk/template/WebDAV/LocalSend/capture,
`:199-210` prompt/delete/restart, `:419` tag manager, `:451` multi-page
import), and the notebook/section panel-local filters (`:2599`, `:2721`).
Deliberately plain `remember`: `pendingRestoreFile` (a `File` handle cannot
survive process death — the flag restores, the file re-picks),
`tagEditorTarget*` (decrypted entities), `globalSearchResults` (re-executed
from the restored query), `pageSortMode`/`recentSearches` (prefs-backed),
`showOnboarding/showTutorial` (first-run one-shots), `isSearching` (no search
is ever in flight across a rotation).

**Tabs (`HomeScreen.kt:1429`)** — `PrimaryTabRow` → `ScrollableTabRow`
(`edgePadding = 0.dp`), all four labels `maxLines = 1` + `Ellipsis`
(cf. `OnDeviceSmartAssistant.kt:197`). Phase-166 overflow discipline kept.

**Search (`HomeScreen.kt:1294-1305,421-447,1796-1805`)** — `TextField` gains
`keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)` +
`keyboardActions onSearch → focusManager.clearFocus()`, the leading icon is
labelled `"Search"`, and a session-only `isSearching` flag (true across the
300 ms debounce, cleared in the search callback and on blank) swaps the
`"no notes"` empty state for a centered `CircularProgressIndicator`.

**48dp minimums** — `minimumInteractiveComponentSize()` appended (visual size
unchanged): HomeScreen chip-dismiss 20dp (`:1385`), sort 32dp (`:1502`),
tag-clear 24dp (`:1650`); GalleryView overflow 28dp (`GalleryView.kt:337`);
UnifiedSidebar header 36dp (`:111`), notebook 30dp ×2 (`:339,357`), section
28dp ×2 (`:442,459`), page 26dp (`:533`); LockScreen biometric (`:183`);
EditorScreen voice-dismiss 28dp (`:2731`), ref-image close 26dp (`:2881`),
contrast color row (`:4901`), paper swatch (`:5649`). DockToolButton/
DockIconButton already enforce it (`:4199,4219`).

**Gallery (`GalleryView.kt:100,289`)** — `GridCells.Adaptive(minSize = 168.dp
→ 150.dp)` (168dp rendered ONE column until the grid exceeded 372dp — a 360dp
phone got one giant card per row; 150dp keeps 2 columns < 600dp, tablets
scale up; the phase-184 content-driven min-height floor still owns card
proportions) and the type badge is TalkBack-labelled
(`contentDescription = pageTypeLabel(page)`).

## 2. File:line evidence table (claim / reality / status / evidence)

| # | Claim | Reality | Status | Evidence |
|---|-------|---------|--------|----------|
| 1 | search/tabs survive rotation | `rememberSaveable` on query, tab, view-mode | DONE | `HomeScreen.kt:182,190-191` |
| 2 | selection + tag filter survive | set savers (null-safe) | DONE | `HomeScreen.kt:77-91,196,205-207` |
| 3 | 10-file import dialog survives | Uri-list string saver + saveable flag/orientation | DONE | `HomeScreen.kt:77-80,449-452` |
| 4 | dialog flags survive | all visibility + string state saveable | DONE | `HomeScreen.kt:117-120,164-168,199-210,419,451` |
| 5 | tabs never clip at 360dp | `ScrollableTabRow`, ellipsized labels | DONE | `HomeScreen.kt:1429-1460` |
| 6 | search IME + no empty-flash | `imeAction=Search`, labelled icon, `isSearching` spinner | DONE | `HomeScreen.kt:1299-1302,421-447,1796-1805` |
| 7 | 48dp hit areas | minimums on all 14 sites; icon-button visuals unchanged, Contrast Studio row intentionally grows to 48dp height (see §6) | DONE | HomeScreen `:1385,1502,1650`; Gallery `:337`; Sidebar `:111,339,357,442,459,533`; Lock `:183`; Editor `:2731,2881,4901,5649` |
| 8 | gallery 2 cols on 360dp | `Adaptive(150.dp)` | DONE | `GalleryView.kt:100` |
| 9 | badge announced | `pageTypeLabel` description | DONE | `GalleryView.kt:289` |
| 10 | pin badge stays 18dp | untouched | DONE | `Phase186GalleryQuickActionsTest` green |
| 11 | `weight(1f,fill=false)` retained | kept deliberately — phase-188 pins it as the defensive slack seat; removal would break `Phase188GalleryLayoutBoundsTest` | ACCEPTED (documented) | `GalleryView.kt:461,467,533` + phase-188 suite green |
| 12 | phase-166 overflow fixes kept | sub-bar/banner/chips rows untouched | DONE | `Phase166LayoutOverflowTest` green |

Note on prompt line numbers: `GalleryView.kt:1432` does not exist (file is
627 lines) — the 32dp site is HomeScreen's sort button (`:1502`, fixed);
`EditorScreen.kt:4123` is the `DockQuickToolsRow` label (already 48dp via
`DockToolButton`); the 36dp interactive site is the paper swatch (`:5649`,
fixed). UnifiedSidebar prompt cited 5 sites; the header 36dp add-notebook
was fixed as a 6th for consistency.

## 3. Stale-pin maintenance (deliberate, behavior-verified)

- `Phase184GalleryProportionTest` + `Phase188GalleryRobustnessTest`: the
  `GridCells.Adaptive(minSize = 168.dp)` literal re-pinned to `150.dp` with a
  `PHASE 263 RE-PIN` note — the Adaptive+keyed structure is unchanged.
- `Phase254CommentTrimTest`: `PHASE 263 RE-BASELINE` — HomeScreen
  3757→3845 raw / 3267→3310 code (saveable state + savers + tabs + IME +
  spinner). EditorScreen needed NO re-baseline: the four minimums ride
  existing modifier lines (+0 lines, verified 7347/6426).
- `Phase186GalleryQuickActionsTest` passes unmodified (visual still 28dp).

## 4. Verification (DoD)

- `gradle :app:assembleDebug` — green.
- `gradle :app:testDebugUnitTest` — **3784 tests, 0 failures, 0 errors,
  0 skipped** (3763 baseline + 16 new `Phase263HomeGalleryTest` + 5
  re-pinned suites green).
- `gradle :app:lintDebug` — 0 errors.
- No Room schema change, no new dependencies, no `.github/workflows/` edits,
  `allowBackup=false` untouched, no plaintext rows, fail-closed behavior
  unchanged.

## 5. New tests

`Phase263HomeGalleryTest` (16 methods, source pins): saveable keys (query,
tab, view-mode, tag path/ids, selection, import uris/orientation/dialog,
all 13 dialog flags, no stale plain-`remember`, exactly 3 saveable
`searchQuery` declarations), `ScrollableTabRow` + no `PrimaryTabRow` +
ellipsized labels, Search IME + label + `isSearching` spinner branch, 48dp
minimums (home ×3, gallery, sidebar ≥6, lock, editor ×2), gallery 150dp
floor + badge description.

## 6. Review-fix addendum (post-review corrections, docs-only)

No `.kt` change: touching `EditorScreen.kt`/`GalleryView.kt` would break the
phase-254 line-count pins (`7347/6426`, +0 lines) and the phase-188
`weight` pins, so all three review findings below resolve as documentation.

- **Finding 7 (`weight(1f, fill = false)` kept):** re-verified inert, not a
  live foot-gun — a `Column` inside a Lazy-grid item has unbounded maxHeight,
  so Compose distributes zero slack through the flex child (it wraps content).
  Removal would break `Phase188GalleryLayoutBoundsTest` pins with no behavior
  gain. Kept deliberately; REPORT row 11 (`ACCEPTED`) stands.
- **Finding 8 (Contrast Studio row visual):** `minimumInteractiveComponentSize`
  on the full-width `ContrastSuggestionsRow` (`EditorScreen.kt:4901`) reserves
  a 48dp minimum height, so that row intentionally grows from ~32dp to 48dp —
  the larger touch target IS the fix. Row 7 above corrected accordingly; the
  13 icon-button sites keep byte-identical visuals.
- **Finding 4 note (LockScreen biometric):** the glyph previously had no
  explicit size (already 48dp by `IconButton` default), so the added minimum
  is a pin against future shrinkage, not a visual change. Harmless, kept.
