# Phase 13: Rich canvas content — brush presets, stickers, styled & rotatable sticky notes [PARTIAL]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with a real AGSL paint engine (Phase 4), working painting features (Phase 7:
stabilizer, pressure curves, symmetry, color harmony), and a plugin system
(Phases 10–12). This phase makes the canvas feel like a real creative tool:
ready-made brushes, ready-made stickers, prettier sticky notes, and rotation for
canvas items. NO fake features — everything must actually render, persist, and
respect the existing data model.

## What to build

### 1. Ready-made brush preset pack
- Add a curated set of named brush presets (e.g. Pencil, Fountain Pen, Marker,
  Soft Watercolor, Dry Oil, Chalk, Eraser, Highlighter) built on the EXISTING
  `WetBrushEngine` + AGSL shader + `BrushStudioDialog` parameters (size,
  pressure curve, wetness, pigment load, texture, hardness, granulation). Presets
  are just pre-filled parameter sets — do NOT create a new engine.
- A preset picker in the brush UI (reachable, not dead). Presets persist per
  selection in `SettingsManager`/SharedPreferences — NO DB schema change.
- Unit test: each preset maps to valid parameter ranges (pure Kotlin).

### 2. Ready-made sticker pack
- Ship a small, free, offline pack of stickers (emoji-style or simple vector
  stickers) that can be placed on the canvas at the tapped position. Use
  built-in vector/emoji rendering — NO new image assets required unless trivial;
  do NOT add an image-import permission (already none needed). Emoji rendering
  via the platform is free and offline.
- Sticker placement: on tap, a sticker appears at the tap point on the active
  page; it is draggable, resizable, and (per item 4) rotatable.
- Sticker instances persist in the note (reuse/extend the existing sticky-note /
  canvas-item persistence path so it survives save/load — inspect
  `CanvasStickyNote`/`CanvasItem` model first and extend honestly; a small DB
  field addition is acceptable ONLY if migration-safe, otherwise persist via the
  existing stroke/note JSON payload).

### 3. Stylish sticky notes
- Improve the existing `CanvasStickyNote` visuals: nicer styling (rounded
  corners, subtle shadow, optional pin/doodle accents), multiple color themes,
  and keep them fully functional (editable, draggable, resizable). Pure Compose
  drawing — no new deps.

### 4. Rotation for ALL canvas items
- Make sticky notes, stickers, and image attachments **rotatable** on the canvas
  (a rotation handle on drag). Store the rotation angle with the item so it
  survives save/load.
- Rotation math must be pure and unit-tested (rotate a point around an anchor,
  clamp/rounding, hit-testing on a rotated rect).
- Respect existing API 26+ and low-end device constraints (rotation is cheap
  math + a `graphicsLayer` rotation — no full redraw).

## Definition of done
- `gradle assembleDebug` succeeds.
- `gradle testDebugUnitTest` passes, including new tests for: preset parameter
  validity, sticker placement math, rotation math (point rotation, rotated
  hit-test), and sticky-note model round-trip (persist → load → render).
- Brushes, stickers, stylish sticky notes, and rotation are ALL reachable in the
  UI and functional (not dead).
- Items survive app restart (persistence verified by round-trip test).

## Constraints
- NO new third-party dependencies. NO new permissions. NO `INTERNET`.
- Do NOT add heavy image assets (keep APK small; prefer vector/emoji).
- Do NOT change the DB schema unless strictly required and migration-safe (then
  say so explicitly; prefer the existing JSON payload path).
- Do NOT edit `.github/workflows/`.
- Keep the classic brush rendering identical when a preset is not in use.
- Be honest: if a sticker pack or rotation cannot be made fully persistent this
  phase, ship the render+rotate part and say exactly what is deferred — never
  claim persistence that doesn't exist.