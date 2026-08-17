# Phase 155: Canvas & brush workshop — two-finger undo/redo, quick-color ring + brush-preset import/export (wires the dormant ProtobufBrushLoader) [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/phase-status.md` +
`docs/ARCHITECTURE.md` + `docs/brush-styles.md` first.** This is a PRODUCT
feature phase. One coherent slice of the canvas/brush experience built on the
existing `AnnotationCanvas`, `WetBrushEngine`, `DesignerPalettes`, and the
dormant-but-tested `ProtobufBrushLoader`.

## Features (2-3 related, bundle deeply)

1. **Two-finger gesture shortcuts on canvas:** two-finger double-tap = undo,
   two-finger swipe left/right = undo/redo (mirror the existing
   `MainActivity.detectTwoFingerSwipeDown` pattern for the palette overlay),
   wired to the existing undo/redo stack in `AnnotationCanvas`/`NoteflowViewModel`.
   Respect reduce-motion (instant, no animation) and never conflict with the
   pinch-zoom gesture (require the second finger's tap/swipe to be distinct from
   an active pinch).
2. **Quick-color ring (long-press eyedropper):** long-press on the canvas pops a
   radial quick-color ring seeded from the active `DesignerPalette` swatches
   (like a mini palette studio); drag to a swatch + release to apply the current
   tool color. Reuse `EyedropperSamplingMath`/`HarmonicContrastMath` seams and
   the existing `BrushColorModeMath`. Non-alarming first-time hint.
3. **Brush-preset import/export.** Wire the DORMANT `ProtobufBrushLoader`
   (`services/ProtobufBrushLoader.kt:67,80,88-96` — currently no production
   caller) into a real brush-preset feature: export the current brush settings
   as an `.inkbrush` protobuf to SAF (`ui/components/SaFExporter.kt` flow), and
   import a user-chosen `.inkbrush` via the picker. This is the exact caller
   round-2 finding R2-b2b3-LOG-03 predicted would appear — carry over its fix
   (sanitized logging) so the API is safe from day one.

## UI/UX + plugin ideas

- The brush workshop ties into the bundled brush presets list (`StickerCatalog`/
  `DesignerPalettes` precedent) — a saved "My presets" section.
- Could later become a `FileTransfer`-capability plugin surface (see
  phase-157's capability browser) — keep the import/export in-app + SAF first.

## Verification

- Unit tests for any new pure-JVM math/decision logic (e.g. a
  `GestureRedoUndoClassifier` that distinguishes two-finger swipe from pinch; a
  `QuickColorRingMath` hit-test; a round-trip `.inkbrush` export→parse test using
  `ProtobufBrushLoader`). Follow repo test layout `app/src/test`.
- `gradle testDebugUnitTest` then `gradle assembleDebug`, report in
  `workspace/phase-155/REPORT.md`.

## Definition of done

- All three features shipped with `file:line` evidence, reachable from
  `AnnotationCanvas`/`EditorScreen`, wired via the view model. Gestures never
  hijack pinch-zoom; reduce-motion respected.
- `.inkbrush` import/export works end-to-end via SAF with the sanitized-logging
  fix from R2-b2b3-LOG-03 applied.
- New tests green + no existing test regressed.

## Constraints

- NO DB schema change. Do NOT edit `.github/workflows/`. No new native deps
  (protobuf runtime is already on the classpath for `.inkbrush`).
- Never log decrypted note content or brush file paths in the clear. Keep
  `allowBackup=false` and the fail-closed lock model.
- Low-end devices: keep the gesture detection cheap (pointer counts only).