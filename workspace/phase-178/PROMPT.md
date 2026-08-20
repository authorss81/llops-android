# Phase 178: Reference-image layer (phase-07 encouraged item) - insert a photo as a traceable underlay [NOT STARTED]

You are working on **InkFlow/Noteflow**. ROADMAP Phase 7's "reference image layer"
was an encouraged (non-mandatory) item that never shipped - recorded in
`docs/phase-status-gaps.md`: "Phase-07 reference-image layer (encouraged item, not
mandatory) absent". This phase ships it: insert an image as a dimmed, non-inking
underlay on the canvas that strokes draw OVER but cannot modify.

Read `docs/ARCHITECTURE.md`, `docs/phase-status.md` and `docs/brush-styles.md`
first.

## WORKFLOW RULE
Work in small steps; `git add -A && git commit -m "llops: phase-178 step N: <desc>" && git push`
after EVERY step. Never sit on uncommitted work.

## Step 1 - Inventory (commit it)
- Read `AnnotationCanvas.kt` (layering: how paper/template/strokes are composed,
  the `LayerBitmapCache`, paginated culling, `graphicsLayer`/`RenderEffect`
  usage), `EditorScreen.kt` (toolbar/overflow menus where a "Insert reference
  image" action would live), and the media-embed path
  (`MediaEmbedComponents.kt`, `decodeBoundedImage` in
  `FullscreenImageDialog`) for how images are stored/loaded today.
- Confirm there is NO existing reference-image/underlay feature to extend.
- COMMIT this step.

## Step 2 - Add the reference-image layer
- Per-page setting: one reference image per page (path or embedded bytes, reusing
  the existing media-embed storage/decryption pattern). Persist via the same
  field-encrypted column convention as other page embeds (no DB schema change -
  reuse an existing column/table or the embed mechanism).
- Render it as the BOTTOM layer (below strokes) with: dim opacity (e.g. 30-50%),
  no inking target (strokes never draw onto it), scale/position stored with the
  page, and it must NOT be exported into the markdown back-save or shared as part
  of the note body (reference-only).
- Respect the existing B1-AUTH-05 inline-image confinement if the path form is
  used - reuse `InlineImagePathPolicy` so a reference image can only resolve
  inside the app-private subtree.
- COMMIT this step.

## Step 3 - UI
- Toolbar/overflow entry "Insert reference image" (existing SAF picker + bounded
  `decodeBoundedImage`), a small control to adjust opacity, and "Remove reference
  image". Non-alarming Snackbars only.
- COMMIT this step.

## Step 4 - Regression proof
- `gradle assembleDebug` green + `gradle testDebugUnitTest` green (except the
  pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure + the 2
  `B1Plat01ReleaseSigningTest` asserts, untouched).
- Pure-JVM tests for the reference-image policy: dim opacity range clamp,
  no-inking guarantee (strokes do not modify the layer), path confinement via
  `InlineImagePathPolicy`, export/back-save exclusion.

## Definition of done
- Reference image renders dimly under strokes, strokes draw over it without
  modifying it, opacity adjustable, removable, persisted per page, excluded from
  markdown back-save/share, image paths confined to the app-private subtree.
- `workspace/phase-178/REPORT.md`: design (storage, rendering order, policy),
  before/after files, test list.
- Commit + push.

## Constraints
- Do NOT edit `.github/workflows/`. No new dependencies. No DB schema change
  (reuse the existing embed/column mechanism).
- Keep the existing security model: reference images are field-encrypted, paths
  confined, never logged.