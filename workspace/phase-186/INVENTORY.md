# Phase 186 — GalleryView Quick-Action Menu Inventory

Phase 186 adds gallery card quick actions (pin/tags/trash) + pinned badge, wiring
them to the SAME ViewModel calls the list view uses.

## User need
Gallery cards only support tap-to-open. The List view (`NotePageCard`) has
pin/rename/edit-tags/trash actions. Gallery cards need the same quick actions.

## Reference — list view quick-action pattern (`ui/screens/HomeScreen.kt`)

`NotePageCard` (HomeScreen.kt:2541-2672) exposes the actions via callbacks the
HomeScreen composable wires directly to the shared ViewModel:

| Action | Card callback (`NotePageCard(...)` param) | HomeScreen wiring | ViewModel call |
|---|---|---|---|
| Pin/Unpin | `onTogglePin: () -> Unit` | HomeScreen.kt:1357 / 1387 | `viewModel.togglePinPage(page.id, page.pinned)` |
| Rename | `onRename: () -> Unit` | HomeScreen.kt:1358-1362 | sets `targetEntityId`/`initialDialogText`/`promptDialogType = "rename_page"` |
| Edit Tags | `onEditTags: () -> Unit` | HomeScreen.kt:1369-1371 / 1399-1401 | sets `tagEditorTargetPage = page` → `TagEditorDialog` (HomeScreen.kt:1918-1926) → `viewModel.updatePageTags(page.id, newTags)` |
| Move to Trash | `onTrash: () -> Unit` | HomeScreen.kt:1363 / 1393 | `viewModel.trashPage(page.id)` |

The list `NotePageCard` renders a direct pin `IconButton` (HomeScreen.kt:2622-2631,
`Icons.Filled.PushPin` when pinned / `Icons.Outlined.PushPin` unpinned) PLUS a
`MoreVert` overflow `DropdownMenu` (HomeScreen.kt:2633-2668) with Rename / Edit
Tags / Move to Trash (trash variant: Restore / Delete Permanently).

### ViewModel targets (NoteflowViewModel.kt)
- `togglePinPage(id: String, currentPinned: Boolean)` — `:2604-2610`,
  `repository.togglePin(id, !currentPinned)` inside `writeGuardedAgainstLock`.
- `trashPage(id: String)` — `:2612-2621`, `repository.trashPage(id)` inside
  `writeGuardedAgainstLock`, clears `_selectedPage` if it was the open page.
- `updatePageTags(id: String, tags: String)` — `:2529-2539`,
  `repository.updatePageTags(id, tags)` inside `writeGuardedAgainstLock`.
- `updatePageTitleAndTags(id, title, tags)` — `:2471` (used by rename dialog).

## Current GalleryView code (`ui/components/GalleryView.kt`, 328 lines)

- Public API (phase-165, preserved): `GalleryView(pages, viewModel, onOpenPage, modifier)`.
  HomeScreen call site HomeScreen.kt:1343 (`pageViewMode == 1`).
- `GalleryCardItem` (private, `:87-314`):
  - Header Row (`:145-190`): type-badge Surface (18dp icon) + title
    (`GalleryTitleDisplayPolicy.displayTitle`, `maxLines=2`, ellipsis) +
    **pinned indicator already present** = `Icons.Outlined.PushPin`,
    tint `scheme.primary`, at default Icon size 24dp (`:183-189`).
  - Content-driven height floor via `GalleryCardLayoutPolicy` (phase-184),
    `heightIn(min = minCardHeight)` at `:96-115`.
  - Preview block `maxLines=3` or 84dp-min ink placeholder (`:202-244`).
  - Footer: ≤3 tag chips + "+N", date row (`:255-310`).
- There is **NO overflow menu** on the gallery card today (grep of file: only the
  pinned badge; no `DropdownMenu`/`MoreVert`).

## Gaps to fix (phase 186 scope)

1. Add `MoreVert` overflow menu to each gallery card with: Pin/Unpin, Edit Tags,
   Move to Trash (error colour), wired to `viewModel.togglePinPage(page.id,
   page.pinned)`, the HomeScreen `TagEditorDialog` path (via an `onEditTags`
   callback so HomeScreen's `tagEditorTargetPage` state stays the single render
   site), and `viewModel.trashPage(page.id)`.
2. Keep the pinned badge (already present) but size it ~18dp so the header still
   fits the narrow grid column alongside the ~28dp overflow button.
3. Menu must not overlap/overflow the card on 360dp (compact ~28dp button, flows
   inside the header Row where the title already `weight(1f)`+ellipsizes).
4. Destructive action = "Move to Trash" (recoverable via the Trash tab →
   Restore), matching the list-view behavior; NOT "Delete Permanently" (the app's
   truly destructive action keeps its confirmation gate, HomeScreen.kt:1869-1896).

## Long-press
Optional ("if cheap to add"). A full-card `combinedClickable` long-press would
fight the Card's tap-to-open gesture and the rounded-ripple; the menu is cheaply
reachable via the MoreVert icon (same pattern as the list card). Decision:
overflow-button only (documented in REPORT.md).

## Tests plan
- Pure-JVM `GalleryCardActionsPolicy` decision table (menu labels Pin/Unpin/Edit
  Tags/Move to Trash, pinned badge show rule, destructive tint rule).
- Source pins: gallery card routes Pin → `viewModel.togglePinPage(.id,.pinned)`,
  Trash → `viewModel.trashPage`, Edit Tags → `onEditTags` (HomeScreen wires it to
  `tagEditorTargetPage = page`), menu labels come from the policy, badge uses the
  PushPin icon + primary tint.