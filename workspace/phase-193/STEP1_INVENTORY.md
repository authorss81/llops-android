# Phase 193 — Step 1 Inventory: resize handles

USER REPORT: at the four corners of items (code blocks, sticky notes, photo
attachments), resize symbols are ALWAYS visible. They should be HIDDEN and only
appear while the user is dragging/resizing the item.

## Where the resize handles actually live

The PROMPT pointed at `ui/components/MediaEmbedComponents.kt` (image embeds
`:70-220`, code blocks `:358-364`, audio cards). Inventory shows those composables
(`PhotoEmbedCard`, `CodeBlockCard`, `AudioPlaybackCard`) have NO corner handles —
they render only card-internal controls (rotate/zoom/reset/OCR `IconButton`s,
caption row, code header). The corner resize symbols are created by the TWO
draggable wrapper composables in `ui/components/AnnotationCanvas.kt`, which wrap
those cards and add corner/rotation handles + drag/resize gestures:

### 1. `DraggableStickyNoteCard` — `AnnotationCanvas.kt:4340-4732`
- Drawn as a `Box` at screen offset + width/height, with an inner content `Box`
  (fillMaxSize, note colour, edit/collapse/delete header row, optional body text).
- Resize state: `resizeWidth` / `resizeHeight` (`:4358-4359`) hoisted via
  `remember(currentNote.width|height)`.
- **Bottom-Right resize handle** `:4683-4717`: a 24dp `Box` aligned
  `Alignment.BottomEnd` containing `Icons.Outlined.AspectRatio` (12dp), with
  `pointerInput` `detectDragGestures { onDrag -> resizeWidth += dx / zoom,
  resizeHeight += dy / zoom }` (+ `onDragEnd` commits to the model). Rendered
  whenever `!currentNote.isCollapsed` (`:4684`) — i.e. ALWAYS visible at rest on
  any expanded sticky note.
- **Rotation handle** `:4719-4730` via `RotationHandle` (`:5135-5198`), a 26dp
  circle at TopCenter with `Icons.Outlined.RotateRight` — also always visible when
  expanded.

### 2. `DraggableMediaEmbedCard` — `AnnotationCanvas.kt:4735-5126`
- Wraps `PhotoEmbedCard` / `CodeBlockCard` / `AudioPlaybackCard` / sticker under
  one outer `Box` with the SAME drag/resize machinery. This is what shows corner
  symbols at rest on code blocks and photo attachments.
- Resize state: `resizeWidth` / `resizeHeight` (`:4762-4779`).
- **FOUR corner resize handles** `:4969-5111` — the exact "four corners" the user
  reported:
  - Bottom-Right `:4990-5015`
  - Bottom-Left `:5017-5046`
  - Top-Right `:5048-5077`
  - Top-Left `:5079-5110`
  Each is a 24dp `Box` aligned to a corner with background
  `primaryContainer` + `Icons.Outlined.AspectRatio` (12dp), and its own
  `pointerInput` `detectDragGestures` (resize delta incl. corner-preserving drag
  for L/R/T corners). Rendered whenever
  `! (type == AUDIO_NOTE && collapsed)` (`:4969`) — i.e. ALWAYS visible at rest
  on every non-collapsed embed.
  All four share `saveCurrentEmbedState` (`:4976-4988`) on `onDragEnd`.
- **Rotation handle** `:5113-5124` via `RotationHandle` — always visible under the
  same gating.

### 3. `RotationHandle` — `AnnotationCanvas.kt:5135-5198`
- 26dp TopCenter circle `Icons.Outlined.RotateRight`. The PROMPT's scope is corner
  resize symbols; rotation handle is a top-center affordance. It is included in the
  inventory but the fix policy (hide at rest, show while dragging) applies uniformly
  to it for consistency.
- Rotation math: `services/CanvasItemRotationMath.rotationFromHandleDrag`
  (`CanvasItemRotationMath.kt:91` documents the handle drag frame).

### 4. Overlay composables used on the canvas (drag/move, no corner handles)
- `DraggableStickyNoteCard` body `pointerInput` `:4495-4531` (older card drag:
  the header row) and the outer-card `pointerInput` `:4690-4705` (overlap).
- `DraggableMediaEmbedCard` body `pointerInput` `:4812-4846` (whole fillMaxSize
  drag/move).
- Minimap widget drag `:2079-2086` — not an item handle.
- PDF page images / reference-image underlay (phase-178) — drawn in canvas body,
  no handles.

## How a handle is drawn + detected (per item type)

| Item | Handle visual | Where | Drag gesture starts |
|---|---|---|---|
| Sticky note (expanded) | 24dp Box, bottom-right, `AspectRatio` icon, `Box.background` alpha 0.15 | `AnnotationCanvas.kt:4683-4717` | `pointerInput` handle box itself |
| Sticky note rotation | 26dp circle top-center `RotateRight` | `:4719-4730` + `RotationHandle` `:5135-5198` | `RotationHandle` `pointerInput` `:5164-5186` |
| Photo / code / audio / sticker embed | 4× 24dp corner Boxes `primaryContainer` + `AspectRatio` | `:4969-5111` | each corner's `pointerInput` `detectDragGestures` |
| Embed rotation | 26dp circle top-center | `:5113-5124` | `RotationHandle` |
| Sticker | box + delete `IconButton` overlay (no resize symbols) | `:4926-4961` | body drag only |
| Audio (collapsed) | handles hidden already (`:4969` gate) | — | — |

## Summary

Two composables own ALL rest-state corner/side resize symbols:
1. `DraggableStickyNoteCard` bottom-right handle `:4683-4717`
2. `DraggableMediaEmbedCard` four corners `:4969-5111`

Both are unconditionally visible at rest (unless audio/sticky collapsed). The drag
gestures live inside each handle's own `pointerInput` (`detectDragGestures`) and are
the natural "handle is active" signal; the body drag (`:4495-4531`, `:4812-4846`) is
the "item is active" signal that must keep working from the item body. No other file
(`MediaEmbedComponents.kt`, `AudioPlaybackCard.kt`, `ImageViewer.kt`, PDF overlay)
renders corner resize symbols.