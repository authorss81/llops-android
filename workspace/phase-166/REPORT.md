# Phase 166 report — compact-screen overflow fixes: app bar, dialogs, chip rows, banners

Status: **DONE** — 6 real overflow defects fixed, verified by a new source-pinning
`Phase166LayoutOverflowTest` (8 tests) and the full suite.

Verification: `gradle assembleDebug` green · `gradle testDebugUnitTest` = **2268 app
tests, 2267 green, 1 PRE-EXISTING failure** (`Phase148UiFailureTextScrubTest`
`scrubForUi strips userinfo...` UNC-path assertion, reproduced on a clean stash per the
phase runner audit — untouched by this phase).

## Why this phase existed

At 360dp (the narrowest supported portrait width, API 26- target) a fixed-width Compose
`Row` cannot shrink its children — whichever child does not fit is **clipped** by the
parent. An on-screen audit of the pre-phase tree found six live sites that render
side-by-side wide controls with no shrink path:

1. `WebDavSyncDialog` — a two-button alert-dialog **confirm row** ("Upload Backup" +
   "Download & Restore", ~320dp+) inside the compact right-aligned `confirmButton` slot
   of `AlertDialog` on 360dp screens. Clipped.
2. `CalendarView` — the selected-date header was a `SpaceBetween` row holding the long
   `Notes for <date key> (n)` summary AND the "New Note for Date" button. Clipped.
3. `HomeScreen` import dialog — three `FilterChip`s ("Auto (Detect)" + icon /
   "Portrait" + icon / "Landscape" + icon ≈ 350dp of unshrinkable content) in a
   fixed-width `Row` inside the dialog text (~264dp available). "Landscape" clipped.
4. `HomeScreen` — the "Filtered by tag: #<path>" banner placed the tag-path text and the
   close button in a `SpaceBetween` row with no weight on the text; a nested tag path
   (e.g. `#date:2026-08-19` or `a/b/c`) overflowed.
5. `InteractiveTutorial` — the bottom action row put "Skip Tutorial" + Back +
   "Skip step" + the Next/Continue button all on one `SpaceBetween` row. Clipped on
   360dp and on larger screens with system font scaling.
6. `MarkdownPreviewScreen` — the `TopAppBar` **title row** held the note title PLUS the
   Reader chip, the view-mode chip AND the split-orientation chip (≈330dp of chips with
   no flexible width), while the **actions** slot held 3 icons + a Serif chip. On a
   360dp screen the title was squeezed to nothing and the chips clipped each other.

## Fixes shipped (with `file:line` evidence)

### 1. WebDavSyncDialog — actions to the body (`WebDavSyncDialog.kt`)

- The two primary actions moved out of `confirmButton` and into the dialog body as
  **full-width stacked buttons** (`WebDavSyncDialog.kt:305-388`). A full-width `Button`
  + `OutlinedButton` stack cannot overflow any supported width.
- `confirmButton` now holds exactly one control — "Close" (`WebDavSyncDialog.kt:391-395`).

### 2. CalendarView — stacked date header (`CalendarView.kt`)

- The summary + button moved from a `SpaceBetween` row into a full-width `Column`
  (`CalendarView.kt:188-212`); the summary wraps and the button keeps its natural width.

### 3. HomeScreen import dialog — scrollable orientation chips (`HomeScreen.kt`)

- The orientation chip `Row` gained `horizontalScroll(rememberScrollState())`
  (`HomeScreen.kt:1711-1714`), so on 360dp the user scrolls to "Landscape" instead of
  seeing it clipped. (The `List/Gallery/…` view-mode chips and the home stat chips
  already used this pattern — verified unchanged.)

### 4. HomeScreen filtered-by-tag banner — flexible text (`HomeScreen.kt`)

- The tag-path `Text` now has `Modifier.weight(1f)` + `maxLines = 2` +
  `TextOverflow.Ellipsis` (`HomeScreen.kt:1233-1245`); the close IconButton stays put.

### 5. InteractiveTutorial — split action rows (`InteractiveTutorial.kt`)

- "Skip Tutorial" sits on its own full-width line; the Back / "Skip step" / Next row
  is a separate, **end-aligned** row (`InteractiveTutorial.kt:338-402`).

### 6. MarkdownPreviewScreen — decluttered app bar + scrollable sub-bar (`MarkdownPreviewScreen.kt`)

- The title row now holds only the **note title + the Reader toggle chip** (the
  view-mode and split-orientation chips were removed from the app bar).
- The view-mode chip, split-orientation chip and the Serif chip now live in a
  **full-width, horizontally scrollable sub-bar** directly beneath the app bar: `Serif`,
  view-mode (EDIT/SPLIT/PREVIEW cycle) and split orientation (Auto/Top-Bottom/Left-Right)
  — `MarkdownPreviewScreen.kt:564-635`. The sub-bar is always present, so the Serif
  toggle stays reachable in reader/preview mode (phase-158 requirement), and because it
  scrolls, no chip can ever clip.
- The app-bar actions keep only the icons (History / Smart-Assistant / Backlinks /
  Save / Plugins) — the Serif chip that previously crowded them is gone.
- Phase-158 behavior is preserved: in reader mode the sub-bar shows only the Serif chip,
  and the editing-only icons/plugins remain hidden in the app bar.

## Audit pass (every screen checked — worst offenders)

| Screen | Finding | Fix |
|---|---|---|
| `WebDavSyncDialog.kt:222-…` | two-button confirm row (~320dp) clipped in `AlertDialog`'s compact slot | stacked full-width body buttons (`:305-388`) |
| `CalendarView.kt:…` header | "Notes for … (n)" summary + button in one `SpaceBetween` row | Column stack (`:188-212`) |
| `CalendarView.kt` month pager | already compact — `ArrowBack`/`ArrowForward` IconButtons + `contentDescription` (`:77-96`) | none needed (DoD icon-arrow pattern already in place) |
| `HomeScreen.kt` import dialog | 3 orientation chips (~350dp) in fixed Row | `horizontalScroll` (`:1711-1714`) |
| `HomeScreen.kt` tag-filter banner | tag path text pushed close button off-screen | weight + `maxLines` + ellipsis (`:1233-1245`) |
| `HomeScreen.kt` search bar | TextField `weight(1f)` + 2 icon buttons | acceptable — flexible TextField absorbs width |
| `HomeScreen.kt` notebook/section chips | two `AssistChip` with `weight(1f)` + ellipsized label | safe (verified) |
| `TagManagerDialog.kt` | input row = TextField `weight(1f)` + single button; tags in `FlowRow` | safe (verified) |
| `CommandPaletteOverlay.kt` tag chips | `LazyRow` | safe (verified — channels scroll) |
| `EditorScreen.kt` template/pressure chips | equally-weighted `weight(1f)` labels | safe (verified); remaining chip rows already `horizontalScroll` |
| `ColorModeChipsRow.kt` | `horizontalScroll` + `LazyRow` swatches | safe (verified) |
| `InteractiveTutorial.kt:…` action row | Skip Tutorial + Back + Skip step + Next in one row | skip line + end-aligned action row (`:338-402`) |
| `KnowledgeGraphScreen.kt` selected-node card | long title overflowed the card | weight + `maxLines` + ellipsis (`:670`) |
| `MarkdownPreviewScreen.kt` app bar | title row = title + 3 chips (~330dp); actions had icons + chip | title + Reader chip only; chips → scrollable sub-bar (`:564-635`) |
| `TutorialDemos.kt` swatches | 6 × 30dp + spacing ≈ 220dp | fits 360dp (verified) |
| Paging: version history / gallery / backups | scrollable sheets / adaptive grid — no fixed-width text pager | none needed (verified) |

### Regression guard

- New `app/src/test/java/com/authorss81/noteflow/Phase166LayoutOverflowTest.kt` (8
  tests, `class Phase166LayoutOverflowTest` at `:29`). Following the repo's
  `Phase148UiFailureTextScrubTest` style, it source-pins every fixed surface: exactly one
  confirm slot in WebDavSyncDialog, no `SpaceBetween` in the Calendar/date header or the
  tutorial action area, `horizontalScroll` on both fixed chip rows, `weight`+ellipsis on
  the two flexible texts, and the preview app bar's title containing exactly one
  FilterChip. A future edit that re-introduces any of the six overflow patterns fails
  the suite.

## Manual check matrix (device / emulator, 360dp portrait)

| Surface | Before | After |
|---|---|---|
| WebDAV dialog actions | right-edge row, clipped | two stacked full-width buttons |
| Calendar selected date | summary + button row clipped | summary wraps above button |
| Import orientation chips | "Landscape" clipped | chips scroll horizontally |
| Tag-filter banner (long tag) | button pushed off-screen | text wraps/ellipsizes |
| Tutorial actions (font scale ≥1.0) | row clipped | skip line + end-aligned actions |
| Preview note (long title) | title invisible, chips jammed | title + reader chip, sub-bar scrolls |

## Notes

- No schema change, no new dependencies, no permission changes.
- `docs/ARCHITECTURE.md` updated with an "Implemented in phase 166" note.
- The one pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure is documented in
  AGENTS.md ("PHASE-166 run: same 1 pre-existing failure, reproduced on a clean stash").