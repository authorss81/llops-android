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

## Step 3 - Regression proof
- `gradle assembleDebug` green + `gradle testDebugUnitTest` green (except the
  pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure + the 2
  `B1Plat01ReleaseSigningTest` asserts, untouched).
- Pure-JVM tests for the toolbar-state policy: empty undo -> dimmed/disabled,
  non-empty -> enabled, empty redo -> dimmed, after undo redo becomes enabled, after
  a new stroke redo clears + dims again; source pins for both toolbars passing
  canUndo/canRedo.

## Definition of done
- Undo/Redo appear dimmed (disabled) when their stack is empty and bright when an
  action is available, on both the compact and full toolbars.
- `workspace/phase-194/REPORT.md`: before/after, policy, tests.
- Commit + push.

## Constraints
- Do NOT edit `.github/workflows/`. No new dependencies. No DB schema change.
- Do not change the undo/redo DATA logic (30-cap, drop-last, redo-clear on new
  stroke) — only the button appearance/state.