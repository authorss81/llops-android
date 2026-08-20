# Phase 186 — GalleryView quick-action menu (pin/tags/trash) + pinned badge

**Status: DONE** (2026-08-20) · commits `llops-bot` on `main` (phases pushed
incrementally per the WORKFLOW RULE).

## Before

Gallery cards (`ui/components/GalleryView.kt`) only supported tap-to-open. The
`GalleryCardItem` header already rendered a pinned **badge** (`PushPin`, primary
tint — from the phase-165 redesign, `GalleryView.kt:183-189` @ commit), but there
was NO overflow menu on the card, so the only way to pin / edit tags / trash a
note was to switch to the List view (`NotePageCard`, `HomeScreen.kt:2541-2672`)
or open the note first. The list card exposes Pin/Unpin, Rename, Edit Tags and
Move to Trash wired to the shared ViewModel:
`viewModel.togglePinPage(page.id, page.pinned)`, `viewModel.trashPage(page.id)`,
`viewModel.updatePageTags(page.id, newTags)` (via `tagEditorTargetPage` →
`TagEditorDialog`, HomeScreen.kt:1918-1926).

## After

Every gallery card now carries a compact quick-action overflow menu wired to the
**SAME ViewModel calls the list view uses**:

- **Pin / Unpin** → `viewModel.togglePinPage(page.id, page.pinned)` (NoteflowViewModel.kt:2604).
- **Edit Tags** → `onEditTags(page)` callback → HomeScreen sets
  `tagEditorTargetPage = page` → the shared `TagEditorDialog` → `viewModel.updatePageTags`.
- **Move to Trash** → `viewModel.trashPage(page.id)` (NoteflowViewModel.kt:2612),
  text + leading icon in `scheme.error`.

New pure-JVM decision table `services/GalleryCardActionsPolicy.kt` owns the
menu labels ("Pin"/"Unpin"/"Edit Tags"/"Move to Trash"), the ordered 3-item menu
set, the pinned-badge show rule + accessibility content-description, and the
destructive-item tint rule — so the menu contract is testable without Compose,
and a reviewer can't reintroduce inline literals that de-sync the two views.

Layout (360dp-safe, phase-166 discipline): the header Row keeps the type badge,
then the title (`weight(1f)` + ellipsis — it absorbs the extra 30dp), the pinned
badge now compact `18.dp` (was the default 24dp icon), and a `MoreVert` overflow
button at `28.dp` with an `18.dp` icon. The rows use the app-wide
`overflowMenuScrollState()`/`overflowMenuScrollModifier()` overflow helpers, so
the menu can never overflow the screen.

### Decision: long-press
The prompt offered full-card long-press "if cheap to add". A
`combinedClickable` long-press would fight the card's tap-to-open `Card(onClick)`
and its rounded ripple; the menu is one extra tap away through the MoreVert icon
(the exact pattern the list card uses). **Not added** — documented here as a
deliberate scope call, not an omission.

### Destructive actions
"Move to Trash" is recoverable (Trash tab → Restore) and matches the list-view
behavior. The app's truly destructive "Delete Permanently" is NOT offered on a
gallery card — it stays behind its existing confirmation gate
(`deleteConfirmType = "page_perm"`, HomeScreen.kt:1869-1896). No silent
destructive action exists from the gallery menu.

## Files touched

| File | Change |
|---|---|
| `app/src/main/kotlin/com/authorss81/noteflow/services/GalleryCardActionsPolicy.kt` | **new** pure-JVM menu/badge decision table |
| `app/src/main/kotlin/com/authorss81/noteflow/ui/components/GalleryView.kt` | compact pinned badge (18dp) + 28dp `MoreVert` overflow menu (Pin/Unpin, Edit Tags, Move to Trash-error); new optional `onEditTags: (NotePageEntity) -> Unit = {}` param (API backward-compatible — HomeScreen.kt:1343 still compiles unchanged, now passing the callback) |
| `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/HomeScreen.kt` | gallery call site passes `onEditTags = { tagEditorTargetPage = it }` → shared `TagEditorDialog` → `updatePageTags` |
| `app/src/test/java/com/authorss81/noteflow/GalleryCardActionsPolicyTest.kt` | **new** 6 pure-JVM policy tests |
| `app/src/test/java/com/authorss81/noteflow/Phase186GalleryQuickActionsTest.kt` | **new** 7 source pins (shared VM calls, Edit-Tags dialog path, policy labels, badge options, compact sizes, error tints, no trash-branch / no hard-delete on gallery) |

## Verification

- `gradle :app:compileDebugKotlin` — green (only pre-existing deprecation
  warnings elsewhere).
- `gradle testDebugUnitTest` — **2463 tests, 2 failed**, both documented
  pre-existing / unrelated:
  - `Phase148UiFailureTextScrubTest:234` — the pre-existing UNC-path assertion
    (documented in AGENTS.md; untouched).
  - `WikiLinkParserCacheUnitTest:233` — documented timing/concurrency flake:
    **passes in isolation** (re-verified just now, BUILD SUCCESSFUL).
- `gradle assembleDebug` — **BUILD SUCCESSFUL** (113 tasks);
  `app-debug.apk` SHA-256 `d14145ee…b6672`.

No schema change, no new dependencies, `.github/workflows/` untouched,
base-APK-size rule intact. `GalleryView` public API remains backward-compatible
(optional param) so any other call site keeps working.

## Regression guard (summary of the pins)

1. `viewModel.togglePinPage(page.id, page.pinned)` appears in GalleryView.kt
   (shared call, not a fork).
2. `viewModel.trashPage(page.id)` appears in GalleryView.kt (shared call).
3. `onEditTags(page)` + HomeScreen `onEditTags = { tagEditorTargetPage = it }` +
   `viewModel.updatePageTags(page.id, newTags)` — the Edit-Tags path stays the
   shared dialog, never a gallery-only tags write.
4. All three labels come from `GalleryCardActionsPolicy` (no inline literals).
5. Badge = `PushPin` + `scheme.primary` + `18.dp`; overflow button `28.dp` /
   icon `18.dp`; trash item `scheme.error` (text + icon).
6. Gallery menu never branches on `isTrash` and never offers "Delete Permanently".

## Docs updated

- `docs/ARCHITECTURE.md` — appended "Implemented in phase-186:" note to the
  `ui/components/` row.
- `docs/phase-status.md` — phase-186 row appended.