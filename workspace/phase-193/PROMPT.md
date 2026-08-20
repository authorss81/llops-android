# Phase 193: Hide resize handles on code blocks / sticky notes / photo attachments until dragging [NOT STARTED]

You are working on **InkFlow/Noteflow**. USER REPORT: at the four corners of items
(code blocks, sticky notes, photo attachments), resize symbols are ALWAYS visible.
They should be HIDDEN and only appear while the user is dragging/resizing the item.

Read `docs/ARCHITECTURE.md` and `docs/phase-status.md` first.

## WORKFLOW RULE
Work in small steps; `git add -A && git commit -m "llops: phase-193 step N: <desc>" && git push`
after EVERY step. Never sit on uncommitted work.

## Step 1 - Inventory (commit it)
- Find every item that renders corner resize handles:
  - `ui/components/MediaEmbedComponents.kt` (image embeds `:70-220`, code blocks
    `:358-364`, audio cards) — locate the corner/edge handle drawables and the
    drag/resize gesture that uses them.
  - `ui/components/AnnotationCanvas.kt` canvas sticky notes / stickers / images
    (`CanvasStickyNote`, `:380-460`, hit-testing + drag) — locate their corner
    handle rendering.
  - Any other resizeable item (PDF page images, overlays, etc.).
- For each: how is the handle drawn (separate composable? overlay Icon? drawBehind?),
  and how does the drag/resize gesture detect the handle?
- COMMIT this step with the full list (file:line).

## Step 2 - Show handles only while dragging/selected
- Handles must be INVISIBLE in the resting state. Show them only while the item is
  actively being dragged/resized (e.g. `isDragging` state, or while a selection
  mode with a visible resize affordance is engaged).
- Keep the gesture hit-target available during the drag so resizing still works —
  the user must be able to START the drag from anywhere on/near the item (not only
  the now-hidden corner), or reveal the handles on touch-down and keep them during
  the gesture.
- Implement a single consistent policy (e.g. a `ResizeHandleVisibility` state
  hoisted per item) reused across all item types so behavior is uniform.
- COMMIT this step.

## Step 3 - Regression proof
- `gradle assembleDebug` green + `gradle testDebugUnitTest` green (except the
  pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure + the 2
  `B1Plat01ReleaseSigningTest` asserts, untouched).
- Pure-JVM tests for the visibility policy: resting = hidden, dragging = visible,
  gesture can still start from the item body, all item types route through the
  same policy (source pins).

## Definition of done
- No resize corner symbols visible at rest on any item; they appear only while
  dragging/resizing; resizing still works from the item body.
- `workspace/phase-193/REPORT.md`: inventory, policy, per-item wiring, tests.
- Commit + push.

## Constraints
- Do NOT edit `.github/workflows/`. No new dependencies. No DB schema change.
- Do not break pinch-zoom or existing drag/pan gestures on the canvas.