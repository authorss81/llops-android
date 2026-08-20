# Phase 194 — Undo/Redo dim + Canvas & Paper Options sheet clipping (2026-08-20)

## User reports
1. "The Undo and Redo buttons on the canvas page are always the same bright
   color even when there is no action to undo or redo."
2. "Templates don't open / don't work" AND "in the canvas and paper options all
   the bottom options are not visible."

## Before

### (1) Undo/Redo never dimmed
Both canvas ink-bar render sites drew the buttons unconditionally bright:

- COMPACT bar — `InkBarPortraitBar` (`app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt:3190-3195` at inventory):
  ```
  IconButton(onClick = onUndo, modifier = Modifier.size(36.dp)) {
      Icon(Icons.Outlined.Undo, ..., tint = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  IconButton(onClick = onRedo, modifier = Modifier.size(36.dp)) {
      Icon(Icons.Outlined.Redo, ..., tint = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  ```
- FULL bar — `InkBarLandscapeBar` (`:3319-3324` at inventory): identical fixed
  tint, no `enabled`.
- No enabled state, no dim, no distinction — grep-confirmed there was zero
  enabled/dim logic anywhere on the undo/redo path. The two stacks themselves
  were reactive state (`undoStack`/`redoStack`, `:439-440`) so all that was
  missing was the button-state wiring.

### (2) Canvas & Paper Options clipped
`CanvasSettingsBottomSheet`'s content `Column`
(`EditorScreen.kt:4369-4373` at report time, now `:4700-4709`) wrapped its very
tall option list — Canvas Mode → Paper Template chip rows → custom background →
Paper Background Color → Page Management → Minimap toggles → GPU brushes →
Shape Auto-Snap → two-finger undo → quick-color ring → haptics → Ink-to-Shape →
Vibrancy → presets → Painting Assist → Reset Zoom & Pan — in a plain
non-scrolling `Column`. Every option below the fold was CLIPPED and
UNREACHABLE on a phone screen, including the second template chip row
(Cornell/Meeting/To-Do Grid, `:4464-4468`). That made the templates look
"broken" — the user could never reach or tap them. Every OTHER `ModalBottomSheet`
in the same file already scrolled (`:3428`, `:3515`, `:4041` → verified at
`:3706`, `:4312`, `:2963` in the current tree).

## After

### (1) Dim when empty (both toolbars)
- New pure-JVM decision table `services/CanvasUndoRedoStatePolicy.kt`:
  - `canUndo(undoStackSize)` = `size > 0`, `canRedo(redoStackSize)` = `size > 0`
  - `iconAlpha(actionable)` = `1f` / `DISABLED_ALPHA` (`0.38f`, the Material 3
    disabled-content alpha)
  - oracle mirrors of the data transitions for the reactive-dim tests:
    `afterUndo`/`afterRedo`/`afterNewStroke` (image the stacks without touching
    the EditorScreen logic).
- Editor scope computes the flags at the `FloatingToolDock` call site
  (`EditorScreen.kt:2285-2286`):
  ```
  canUndo = com.authorss81.noteflow.services.CanvasUndoRedoStatePolicy.canUndo(undoStack.size),
  canRedo = com.authorss81.noteflow.services.CanvasUndoRedoStatePolicy.canRedo(redoStack.size),
  ```
- `FloatingToolDock` takes `canUndo`/`canRedo` (`:2814-2815`) and forwards to
  both bars (`:3008-3009` landscape, `:3028-3029` portrait).
- BOTH render sites now:
  ```
  IconButton(
      onClick = onUndo,
      enabled = canUndo,
      modifier = Modifier.size(36.dp)
  ) {
      Icon(Icons.Outlined.Undo, ..., tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
          alpha = com.authorss81.noteflow.services.CanvasUndoRedoStatePolicy.iconAlpha(canUndo)))
  }
  ```
  → bright + clickable when actionable; dimmed to 0.38 alpha + non-clickable
  (Material `enabled=false` also suppresses the ripple) when empty. The dim is
  reactive: Compose re-reads the stack sizes on every stroke added / undo /
  redo / clear.
- Undo/redo DATA logic unchanged: `handleStrokesChange` 30-cap drop-oldest
  (`:830-834`), `handleUndo`/`handleRedo` (`:1280-1304`), redo-clear on a new
  stroke — all untouched.

### (2) Sheet is now scrollable
Exactly one modifier added to `CanvasSettingsBottomSheet`'s content Column
(`EditorScreen.kt:4700-4709`):
```
// before: Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
// after:
Modifier
    .fillMaxWidth()
    .padding(horizontal = 20.dp, vertical = 8.dp)
    .verticalScroll(rememberScrollState())
```
Layout/content/order and the template list are otherwise identical. `verticalScroll`
+ `rememberScrollState` were already imported (`:28`, `:35`).

## Tests
`app/src/test/java/com/authorss81/noteflow/Phase194UndoRedoToolbarStateTest.kt` (13, pure JVM):
- Decision table: empty undo/redo → disabled + dimmed (0.38f), non-empty → enabled
  + full alpha, zero/negative sizes never enable, M3 0.38 constant pinned.
- Reactive transitions through the oracle mirrors: after undo redo becomes
  enabled while undo dims; no-op undo on a fresh page keeps both dimmed; after
  redo undo re-enables and redo dims again; a new stroke re-enables undo and
  clears + dims redo.
- Source pins: editor passes policy-computed `canUndo`/`canRedo` into the dock
  and forwards to BOTH bars (`canUndo = canUndo` ×2, `canRedo = canRedo` ×2);
  both render sites use `enabled = canUndo`/`canRedo` (×2 each) and policy
  `iconAlpha(canUndo)`/`iconAlpha(canRedo)` (×2 each); no old fixed-tint inline
  Undo/Redo `IconButton` remains; the 30-cap `newUndo.removeAt(0)` + redo-clear
  data logic still present.
- Source pin: `CanvasSettingsBottomSheet`'s content Column carries
  `.verticalScroll(rememberScrollState())` and the Cornell/Meeting/To-Do Grid
  template mapping sits BELOW the scroll line inside the same sheet.

## Regression proof
- `gradle :app:assembleDebug` — BUILD SUCCESSFUL (79 MB debug APK on disk).
- `gradle :app:testDebugUnitTest` — **2564 tests, 2 failed**:
  - `Phase148UiFailureTextScrubTest` — known pre-existing UNC-path failure
    (fails even in isolation; documented in AGENTS.md; untouched by this diff).
  - `WikiLinkParserCacheUnitTest` — known timing/concurrency flake; **passes in
    isolation** (verified: separate run green).
  - New `Phase194UndoRedoToolbarStateTest`: 13/13 green.

## Constraints honored
- No `.github/workflows/` edits. No new dependencies. No DB schema change.
- Undo/redo DATA logic (30-cap, drop-last, redo-clear on new stroke) untouched —
  appearance/state only.
- Sheet layout/content and template list not reordered/redesigned — the fix is
  ONLY `verticalScroll`.
- base-APK-size rule intact; no INTERNET usage added.

## Files
- `app/src/main/kotlin/com/authorss81/noteflow/services/CanvasUndoRedoStatePolicy.kt` (new)
- `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt` (undoredo wiring + sheet scroll)
- `app/src/test/java/com/authorss81/noteflow/Phase194UndoRedoToolbarStateTest.kt` (new)
- `docs/ARCHITECTURE.md`, `docs/phase-status.md` (phase-194 note/row)
- `workspace/phase-194/STEP1_INVENTORY.md` (commit c5af1f1)