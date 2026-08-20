# Phase 194: Dim Undo/Redo buttons on the canvas when there is nothing to undo/redo [NOT STARTED]

You are working on **InkFlow/Noteflow**. USER REPORT: the Undo and Redo buttons on the
canvas page are always the same bright color even when there is no action to undo or
redo. They should be dimmed (disabled appearance) when their stack is empty.

Relevant code: `ui/screens/EditorScreen.kt` — the toolbar renders Undo/Redo with a fixed
tint `MaterialTheme.colorScheme.onSurfaceVariant` and no enabled state:
- compact toolbar `:2919-2925` (`IconButton(onClick = onUndo)` + `Icons.Outlined.Undo/Redo`)
- full toolbar `:3048-3054` (same pattern)
- `undoStack`/`redoStack` state `:424-426`, `handleUndo` `:1133-1145`,
  `handleRedo` `:1146-1158`.

Read `docs/ARCHITECTURE.md` and `docs/phase-status.md` first.

## WORKFLOW RULE
Work in small steps; `git add -A && git commit -m "llops: phase-194 step N: <desc>" && git push`
after EVERY step.

## Step 1 - Inventory (commit it)
- Read the two toolbar sections (`:2919-2925`, `:3048-3054`) and trace how
  `undoStack`/`redoStack` flow into the toolbar (via `ToolbarState`/params at
  `:2534-2545`, `:2793-2800`).
- Confirm there is NO current enabled/dim logic (both buttons always enabled).
- COMMIT this step.

## Step 2 - Dim when empty
- Pass `canUndo = undoStack.isNotEmpty()` and `canRedo = redoStack.isNotEmpty()` down
  to both toolbars. Render the buttons disabled when false:
  - `IconButton(enabled = canUndo, ...)` (Material disables click + dims content)
  - or set the icon tint to a dimmed color (`onSurface.copy(alpha=0.38f)` / the
    theme's disabled color) when the stack is empty.
- Keep the onClick unchanged (or no-op when disabled). Ensure the dim state updates
  reactively as strokes are added/undone/redone/cleared.
- Match the app's existing disabled-style convention used elsewhere (check
  `MaterialTheme` disabled content color usage).
- COMMIT this step.

## Step 3 - Fix: Canvas & Paper Options bottom sheet clips all lower options [same phase]
- USER REPORTED (2026-08-20): "templates don't open / don't work" AND "in the canvas
  and paper options all the bottom options are not visible". ROOT CAUSE (verified):
  `CanvasSettingsBottomSheet` in `EditorScreen.kt:4369-4373` wraps its content in a
  plain `Column` with NO `verticalScroll(rememberScrollState())`. Every OTHER
  ModalBottomSheet in the same file scrolls (`:3428`, `:3515`, `:4041` all apply
  `.verticalScroll(rememberScrollState())` to their content Column). The sheet is
  very tall (Canvas Mode -> Paper Template chip rows -> custom background ->
  Paper Background Color -> Page Management -> Minimap toggles -> GPU brushes ->
  Shape Auto-Snap -> two-finger undo -> quick-color ring -> haptics -> Ink-to-Shape ->
  Vibrancy -> presets -> Painting Assist -> Reset Zoom & Pan), so on a phone screen
  every option below the fold is clipped and UNREACHABLE — the Cornell/Meeting/To-Do
  Grid templates in the second chip row (`:4464-4468`) and everything beneath are the
  "bottom options" the user cannot see, which also makes templates appear "broken".
- FIX (1 line): add `.verticalScroll(rememberScrollState())` to the content Column of
  `CanvasSettingsBottomSheet` (`EditorScreen.kt:4369`), matching the existing sheets
  (`rememberScrollState()` + `verticalScroll` are already imported at `:28`,`:35`).
  Do NOT change the sheet layout/content or template list itself — only make the
  content scrollable so every option is reachable.
- COMMIT this step with the before/after modifier lines.

## Step 4 - Regression proof
- `gradle assembleDebug` green + `gradle testDebugUnitTest` green (except the
  pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure + the 2
  `B1Plat01ReleaseSigningTest` asserts, untouched).
- Pure-JVM tests for the toolbar-state policy: empty undo -> dimmed/disabled,
  non-empty -> enabled, empty redo -> dimmed, after undo redo becomes enabled, after
  a new stroke redo clears + dims again; source pins for both toolbars passing
  canUndo/canRedo.
- Source pin that `CanvasSettingsBottomSheet`'s content Column carries
  `.verticalScroll(rememberScrollState())`.

## Definition of done
- Undo/Redo appear dimmed (disabled) when their stack is empty and bright when an
  action is available, on both the compact and full toolbars.
- Every option in the "Canvas & Paper Options" sheet (including the second paper
  template row Cornell/Meeting/To-Do Grid and all lower settings) is reachable by
  scrolling; no option is clipped.
- `workspace/phase-194/REPORT.md`: before/after, policy, tests.
- Commit + push.

## Constraints
- Do NOT edit `.github/workflows/`. No new dependencies. No DB schema change.
- Do not change the undo/redo DATA logic (30-cap, drop-last, redo-clear on new
  stroke) — only the button appearance/state.
- Do not reorder/redesign the sheet's options or the template list — the fix is
  ONLY making the sheet content scrollable.