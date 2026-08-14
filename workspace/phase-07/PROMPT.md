# Phase 7: Free, CI-buildable painting features (HIGH VALUE, ZERO COST) [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with a real vector + AGSL paint engine. This phase adds **free, offline,
CI-buildable** features that make the painting/notes experience feel premium.
Every feature must be verifiable by unit test (pure math) or by the build
compiling — NO on-device-only tuning requirements in this phase, NO paid APIs,
NO new permissions, NO schema change.

Choose the ones that are highest value and keep the phase bounded. Do them
well rather than doing many poorly. At minimum implement items 1–3; items 4–7
are strongly encouraged if they fit.

## Feature list (ranked)

### 1. Brush stabilizer (HIGH value, pure math, unit-testable)
Add a stroke stabilizer (rolling-window smoothing with a per-axis weight, like
Procreate's stabilizer) to the canvas pointer input. The stroke point path in
`AnnotationCanvas.kt` should smooth jitter while staying responsive. Make it a
toggle in the brush/pen settings (persisted in `SettingsManager`), default off so
existing behavior is unchanged.

- Unit test: feed a noisy synthetic point sequence, assert the stabilized output
  is closer to the intended line (lower variance) than the input.

### 2. Pressure-curve remap (HIGH value for stylus users)
Add a pressure→width/opacity curve remap (e.g. linear / light / heavy presets)
so users can tune pen feel. Pure function from `pressure` (0–1) + curve id →
`width`/`opacity`. Wire into the existing pressure-sensitive brush path
(`AnnotationCanvas` pressure capture). Persist the curve choice.

- Unit test: assert monotonicity and endpoints per curve.

### 3. Symmetry / mirror mode (HIGH value for painting)
Add vertical/horizontal/radial symmetry: while enabled, stroke points are
mirrored live so the user paints both halves simultaneously. Toggle in the
toolbar. Do NOT change the saved stroke data model — mirror at render/input time
(store the real points; symmetry is a view-time transform so saved notes remain
portable and export correctly).

- Unit test: mirrored point math (vertical/horizontal/radial).

### 4. Color harmony tools (MED-HIGH, deterministic color math — ideal CI feature)
In the color picker/palette, add harmony generation: analogous, complementary,
triadic, tetradic swatches derived from the current color. Deterministic HSL
rotation math. Add generated swatches to the existing palette manager.

- Unit test: complementary of red is cyan-ish; triadic points are 120° apart;
  analogous are ±30°.

### 5. Reference-image layer (MED-HIGH for art notes & tracing)
Allow importing an image as a locked, low-opacity reference layer under the ink
(scale/move it). The layer model already exists — add a "reference image" layer
type or reuse the existing layer/embed mechanism with a locked + low-opacity
render. Must be off-thread and bounded-decode (reuse `ImageViewer.decodeBounded`).

- Verification: builds + existing layer tests pass; image decodes bounded (no
  `decodeFile` full-res).

### 6. Custom paper texture packs (MED, offline)
Allow importing a photo/texture as a tiled page background (in addition to the
existing blank/lined/grid/dots/Cornell templates). Reuse the paper-render path
in `AnnotationCanvas`. Persist per-page like the existing template field.

- Verification: builds; template enum/field extended without a schema bump (store
  the texture file path in an existing field or a preference keyed by page id —
  do NOT change the DB schema).

### 7. WebP export (MED, trivial)
Add `CompressFormat.WEBP_LOSSY` (and/or `WEBP_LOSSLESS`) as an export option in
the existing export engine (`ImportExportService` PNG/PDF exporter). One more
format, no new deps.

## Definition of done
- `gradle assembleDebug` succeeds.
- `gradle testDebugUnitTest` passes (existing + new math tests).
- Items 1–3 fully implemented with unit tests; 4–7 as far as they fit.
- Every new feature respects existing API 26+ fallbacks (no feature requires
  hardware that the app's compatibility tiering doesn't already gate).

## Constraints
- NO new third-party dependencies. NO new permissions. NO `INTERNET`.
- Do NOT change the DB schema. Persist new settings/preferences in
  `SettingsManager`/SharedPreferences only.
- Do NOT edit `.github/workflows/`.
- Keep classic brush rendering identical when new toggles are off.
- Do not silently degrade on low-end devices — gate features behind the existing
  capability/tier helpers (`DeviceCompatibilityManager`, `ShaderCapabilityHelper`).