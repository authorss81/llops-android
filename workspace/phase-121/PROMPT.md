# Phase 121: Rainbow colour support for brushes [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/phase-status.md` and
`docs/ARCHITECTURE.md` first.**

**THE FEATURE:** add a **rainbow colour mode** so brush strokes transition
through the colour spectrum as they are drawn (e.g. hue cycles along the
stroke length, or over time), in addition to the existing solid colours.

## What to do
- Add a "Rainbow" option to the colour picker (`EditorScreen.kt` colour
  picker + `SettingsManager` persisted state). Rainbow is a **mode**, not a
  single colour: stroke colour is computed per-point from a hue wheel
  (e.g. `Color.hsv(hue, 1f, 1f)` with hue advancing along the stroke or
  over time).
- Wire it through `AnnotationCanvas.kt` stroke recording and rendering so
  per-point hue is applied (reuse the existing per-point stroke data; no new
  schema). Define the hue-advance policy clearly (per-stroke-length vs
  per-time) and keep it deterministic for tests.
- Expose it in the width/colour quick pickers and persist the choice.
- Low-end safe: hue math must be cheap and allocation-free per point.

## Verification
- Pure-JVM unit tests: hue advance function (deterministic, wraps at 360°),
  rainbow-mode state persistence, and that non-rainbow strokes are unchanged.
- `gradle testDebugUnitTest` + `gradle assembleDebug` must pass (or a
  documented pre-existing-only failure).

## Definition of done
- Rainbow mode is selectable, persists, and renders spectrum-coloured
  strokes; normal colours unchanged.
- `workspace/phase-121/REPORT.md` committed with file:line evidence.

## Constraints
- NO DB schema change. Do NOT edit `.github/workflows/`. Do not add new
  dependencies. Never log keys/decrypted content. Keep `allowBackup=false`,
  `ClipboardGuard`, FLAG_SECURE intact.