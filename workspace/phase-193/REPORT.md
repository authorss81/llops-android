# Phase 193 — Hide resize handles on code blocks / sticky notes / photo attachments until dragging

Status: DONE (2026-08-20)

User report: at the four corners of items (code blocks, sticky notes, photo
attachments), resize symbols are ALWAYS visible. They should be HIDDEN and only
appear while the user is dragging/resizing the item.

## Inventory (Step 1 — see `STEP1_INVENTORY.md`)

The corner resize symbols are NOT drawn in `MediaEmbedComponents.kt`
(`PhotoEmbedCard`/`CodeBlockCard`/`AudioPlaybackCard` carry only card-internal
controls — rotate/zoom/reset/OCR buttons, captions, code headers). They are
created by the two draggable wrapper composables in
`ui/components/AnnotationCanvas.kt`:

| Item | Handle | Where |
|---|---|---|
| Sticky note (expanded) | Bottom-Right 24dp `Box` + `AspectRatio` icon | `DraggableStickyNoteCard` `:4683-4717` (pre-fix) |
| Sticky note rotation | 26dp top-center circle | `RotationHandle` `:5135-5198` |
| Photo / code / audio / sticker embed | FOUR corner 24dp boxes + `AspectRatio` | `DraggableMediaEmbedCard` `:4969-5111` (pre-fix) |
| Embed rotation | 26dp top-center circle via `RotationHandle` | `:5113-5124` (pre-fix) |

Every handle is a `Box` with its own `pointerInput { detectDragGestures(...) }`
— the handle drag IS the resize/rotation gesture. Handles were rendered
unconditionally at rest (unless audio/sticky collapsed): the "resize symbols
always visible at the four corners" the user saw = `DraggableMediaEmbedCard`
`:4969-5111` across photo/code/audio embeds, plus the sticky note's bottom-right
handle.

## Policy (Step 2)

New pure-JVM `services/ResizeHandleVisibilityPolicy.kt` — the single consistent
decision table reused by every draggable item type:

- `visibleAtRest()` = `false` — resting items show NO resize symbols.
- `visibleWhileActive(interacting)` = `interacting` — handles appear only while
  the item is being touched/dragged/resized.
- `shouldShow(interacting, collapsed)` = `!collapsed && visibleWhileActive(...)`
  — collapsed items (collapsed audio card, collapsed sticky) never show handles.
- `handleAlpha(visible)` = `1f`/`0f` — hidden handles are drawn at **alpha 0f
  but stay COMPOSED in the layout** (`HIDDEN_HANDLE_ALPHA = 0f`), so the box +
  `pointerInput` hit-target is still there and a resize/rotation gesture can
  STILL START on a (hidden) corner or from the item body — the gesture is never
  dead, just invisible.
- Shared constants: `HANDLE_SIZE_DP = 24f`, `ROTATION_HANDLE_SIZE_DP = 26f`.

### Per-item wiring (all through the one policy)

Each card hoists a per-item `interacting: Boolean` state keyed on the item id:

1. **Non-consuming touch-down observation** on the outer card `Box`
   (`awaitEachGesture { awaitFirstDown(requireUnconsumed=false); interacting=true;
   waitForUpOrCancellation(); interacting=false }`) — touching the item anywhere
   reveals the handles immediately and keeps them until the pointer lifts.
2. **Every drag gesture toggles the flag**: body move drag `onDragStart`
   sets `interacting = true` (reveal) and `onDragEnd`/`onDragCancel` clear it;
   each corner handle + `RotationHandle` do the same, so a resize/rotation begun
   on a still-composed (hidden) hit-box keeps the handles visible for the whole
   gesture.
3. **Handle visuals** are gated by `shouldShow(...)` via a `.graphicsLayer {
   alpha = handleAlpha(...) }` on the handle `Box` — the 24dp boxes and their
   `pointerInput` remain composed at rest (alpha 0), i.e. resize still works
   from corner or body, while the symbol is invisible.

Move/resize gestures and pinch-zoom are untouched (the body drag + the four
corner drag handlers keep their exact delta math).

## Regression proof (Step 3)

- `gradle :app:assembleDebug` — GREEN.
- `gradle :app:testDebugUnitTest` — 2551 total, 1 failed = the pre-existing
  `Phase148UiFailureTextScrubTest` UNC-path failure (fails in isolation,
  untouched). Phase-193's own suite green.
- `Phase193ResizeHandleVisibilityTest` (10 tests, pure JVM):
  - Policy: resting = hidden; dragging = visible; collapsed never; hidden
    hit-box keeps alpha 0 (still composed); active alpha = 1.
  - Source pins: sticky bottom-right handle routes through `shouldShow` +
    `handleAlpha(handleVisible)`; the four embed corners route through
    `handleAlpha(cornerVisible)` (×4) with `onDragStart = { interacting = true }`
    (×5 incl. sticky); both `RotationHandle` call sites pass
    `visible = policy.shouldShow(...)` + `onInteractionChange` (×2 each);
    `RotationHandle` itself alpha-gates its circle via `handleAlpha(currentVisible)`;
    body-drag `fillMaxSize().pointerInput` present (move survives); exactly 2
    non-consuming observation coroutines (sticky + embed; the 3rd
    `awaitFirstDown(requireUnconsumed=false)` is the pre-existing phase-155
    quick-color-ring handler); every `Icons.Outlined.AspectRatio` (×5) sits under
    a policy-driven `graphicsLayer`; no render site hardcodes its own visibility.

## Constraints honored

- No `.github/workflows/` edits, no new dependencies, no DB schema change.
- Pinch-zoom / existing drag-pan gestures on the canvas untouched (the
  observation coroutine never consumes).
- Base-APK-size rule intact (one tiny pure-JVM policy + 10 test methods).

## Files

- `app/src/main/kotlin/com/authorss81/noteflow/services/ResizeHandleVisibilityPolicy.kt` (new)
- `app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt` (wiring)
- `app/src/test/java/com/authorss81/noteflow/Phase193ResizeHandleVisibilityTest.kt` (new)
- `workspace/phase-193/STEP1_INVENTORY.md`