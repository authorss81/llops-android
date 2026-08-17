# Phase 124: Two eraser types — whole-stroke delete & smooth partial erase [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/phase-status.md` and
`docs/ARCHITECTURE.md` first.**

**THE FEATURE:** provide **two eraser modes**:
1. **Stroke eraser** — tapping (or crossing) a stroke deletes the **entire
   stroke**.
2. **Pixel/smooth eraser** — dragging erases only the area touched, with a
   smooth, round, pressure-aware edge (no sharp steps), like a real eraser.

## What to do
- Add both eraser modes to the tool picker (`EditorScreen.kt`), persisted in
  `SettingsManager` (or a per-session default that persists — pick one and
  document it).
- **Stroke eraser**: hit-test existing strokes in
  `AnnotationCanvas.kt`/stroke data (reuse the existing stroke segment data);
  delete the whole stroke record (both geometry + encrypted fields via the
  existing repository save path — no new schema).
- **Smooth eraser**: erase by masking/splitting the affected stroke geometry
  with a smooth round mask; the visible result must be smooth (no jagged
  carve), and undo must work for both modes.
- The eraser cursor preview should show the current mode (circle vs stroke-
  highlight) and radius.

## Verification
- Pure-JVM unit tests: stroke hit-testing (tap → whole stroke id), smooth
  partial-erase geometry (segment split stays smooth, points outside mask
  preserved), undo/redo round-trip for both modes.
- `gradle testDebugUnitTest` + `gradle assembleDebug` must pass (or a
  documented pre-existing-only failure).

## Definition of done
- Both eraser modes exist, are selectable, work correctly, and support undo.
- `workspace/phase-124/REPORT.md` committed with file:line evidence.

## Constraints
- NO DB schema change (if a schema change is truly required, a migration-safe
  note in REPORT.md is MANDATORY; migration must never delete user data).
- Do NOT edit `.github/workflows/`. Do not add new dependencies. Never log
  keys/decrypted content. Keep `allowBackup=false`, `ClipboardGuard`,
  FLAG_SECURE intact. Low-end safe (allocation-free per-point math).