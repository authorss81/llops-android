# Phase 194 — Step 1: Undo/Redo toolbar-state inventory (2026-08-20)

## Bug
- Undo and Redo buttons on the canvas ink bar are always the same bright color
  even when there is nothing to undo/redo. They should be dimmed (disabled
  appearance) when their stack is empty.

## Wiring (file:line, verified against the current source)

### State
- `EditorScreen.kt:439-440` — `undoStack` / `redoStack` are Compose
  `remember { mutableStateOf(...) }` lists of `List<Stroke>` snapshots. Empty on
  page open.

### Data logic (UNCHANGED this phase — appearance only)
- `EditorScreen.kt:829-837` `handleStrokesChange` — a new stroke batch pushes the
  PRE-change `strokes` onto `undoStack` (30-cap via drop-oldest) and clears
  `redoStack`.
- `EditorScreen.kt:1280-1291` `handleUndo` — pops `undoStack.last()` into
  `strokes`, pushes the current `strokes` onto `redoStack`. No-op when empty.
- `EditorScreen.kt:1293-1304` `handleRedo` — pops `redoStack.last()` into
  `strokes`, pushes the current `strokes` onto `undoStack`. No-op when empty.
- Clear Canvas (`:2726-2727`) routes through `handleStrokesChange(emptyList())`,
  so it pushes the pre-clear strokes onto `undoStack` (Undo stays enabled).

### Call-site → floating dock
- `EditorScreen.kt:2245-2291` — inside an `AnimatedVisibility`, `FloatingToolDock`
  is invoked from the editor scope with `onUndo = { handleUndo() }` and
  `onRedo = { handleRedo() }` (`:2279-2280`). Undo/redo stack size is in scope here.

### FloatingToolDock → the two ink bars
- `EditorScreen.kt:2787-2807` `private fun FloatingToolDock(...)` — takes
  `onUndo: () -> Unit` (`:2804`) and `onRedo: () -> Unit` (`:2805`); passes them
  verbatim to both bars:
  - landscape (full-width/vertical) `InkBarLandscapeBar` at `:2996-2997`
  - portrait (compact horizontal) `InkBarPortraitBar` at `:3014-3015`

### Button renders (THE two sites with the bug)
- COMPACT toolbar — `InkBarPortraitBar`, `EditorScreen.kt:3190-3195`:
  ```
  IconButton(onClick = onUndo, modifier = Modifier.size(36.dp)) {
      Icon(Icons.Outlined.Undo, ..., tint = MaterialTheme.colorScheme.onSurfaceVariant)  // :3191
  }
  IconButton(onClick = onRedo, modifier = Modifier.size(36.dp)) {
      Icon(Icons.Outlined.Redo, ..., tint = MaterialTheme.colorScheme.onSurfaceVariant)   // :3194
  }
  ```
- FULL toolbar — `InkBarLandscapeBar`, `EditorScreen.kt:3319-3324`: identical
  fixed-tint pattern.

### Confirmation: NO current enabled/dim logic
- Both `IconButton`s declare NO `enabled` parameter (defaults to `true`), and the
  icon tint is the fixed `MaterialTheme.colorScheme.onSurfaceVariant` in both
  branches — bright regardless of stack contents. There is zero
  enabled/dim/distinguish logic anywhere in the undo/redo path.
- `IconButton(onClick = onUndo, enabled = ...)` does not exist in the file; the
  only `enabled =` usages are on unrelated `Surface`/`Switch`/`TextButton` nodes
  (e.g. the phases-12-OCR `showExtract` gate). Grep-confirmed.

## Fix plan (Step 2)
1. New pure-JVM policy `services/CanvasUndoRedoStatePolicy.kt`:
   `canUndo(undoStackSize)`, `canRedo(redoStackSize)`, `iconAlpha(actionable)`
   (false ⇒ `DISABLED_ALPHA = 0.38f`), plus the state-transition oracle mirrors
   (`afterUndo`/`afterRedo`/`afterNewStroke`) that the reactive dim tests drive.
2. Editor scope computes `canUndo = policy.canUndo(undoStack.size)` /
   `canRedo = policy.canRedo(redoStack.size)` → pass into `FloatingToolDock`.
3. `FloatingToolDock` forwards to `InkBarPortraitBar` + `InkBarLandscapeBar`.
4. Both bars render `IconButton(enabled = canUndo/canRedo, ...)` and the icon
   tint = `onSurfaceVariant.copy(alpha = policy.iconAlpha(canUndo/canRedo))`.