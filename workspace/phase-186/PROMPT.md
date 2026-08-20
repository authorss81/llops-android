# Phase 186: GalleryView — quick-action menu (pin/tags/trash) + pinned badge on cards [NOT STARTED]

You are working on **InkFlow/Noteflow**. User visual review: gallery cards only support
tap-to-open; the List view (`NotePageCard`, `HomeScreen.kt:1352-1372`) has pin/rename/
edit-tags/trash actions. GalleryView cards need the same quick actions.

Relevant code: `ui/components/GalleryView.kt:95-97` vs `ui/screens/HomeScreen.kt:1352-1372`.

Read `docs/ARCHITECTURE.md` and `docs/phase-status.md` first.

## WORKFLOW RULE
Work in small steps; `git add -A && git commit -m "llops: phase-186 step N: <desc>" && git push`
after EVERY step.

## Step 1 - Inventory (commit it)
- Read `ui/components/GalleryView.kt:90-212` and the HomeScreen `NotePageCard`
  quick-action pattern (`HomeScreen.kt:1352-1372`) — how pin/rename/tags/trash are
  wired to the ViewModel (`viewModel.togglePinPage`, `trashPage`, tag dialogs).
- COMMIT this step.

## Step 2 - Add quick actions
- Add a pinned badge (`PushPin` icon, primary tint) + a `MoreVert` overflow menu on
  each gallery card with: Pin/Unpin, Edit Tags, Move to Trash (error color).
  Wire them to the SAME ViewModel calls the list view uses. Keep the compact
  layout (menu icon ~28dp, menu icon ~18dp) so it fits the narrow grid column.
- Ensure the menu does NOT overlap/overflow the card on 360dp; long-press also opens
  the menu if cheap to add.
- COMMIT this step.

## Step 3 - Regression proof
- `gradle assembleDebug` green + `gradle testDebugUnitTest` green (except the
  pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure + the 2
  `B1Plat01ReleaseSigningTest` asserts, untouched).
- Pure-JVM tests for any new policy (e.g. menu-state or pin-badge decision) +
  source pins that the gallery actions route through the same VM calls.

## Definition of done
- Gallery cards show pinned badge + working Pin/Unpin, Edit Tags, Move to Trash via
  the shared ViewModel path. `workspace/phase-186/REPORT.md` before/after + tests.
- Commit + push.

## Constraints
- Do NOT edit `.github/workflows/`. No new dependencies. No DB schema change.
- Keep destructive actions behind the existing trash confirmation (never delete
  silently).