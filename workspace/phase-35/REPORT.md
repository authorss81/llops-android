# Phase 35 — Canvas & Scribe Ergonomics (REPORT)

Status: **DONE**. `gradle testDebugUnitTest` (all subprojects) + `gradle assembleDebug` pass.
App unit suite: **805 tests, 0 failures/errors** (includes the four new test classes below and
`PaletteCatalogTest` / `ColorHarmonyHelperTest` kept green).

## 1. Floating minimalist tool dock

### Before
- `FloatingBottomToolbarPill` (EditorScreen) was a fixed, non-draggable pill anchored
  `BottomCenter`/`CenterEnd` with a dense row of tools; every tool needed a tap to expand
  the picker, then a tap on the tool.
- No edge snapping, no drag, no auto-tuck during pen input, no landscape handling.

### After
- `FloatingToolDock` (EditorScreen) replaces the old pill: a small draggable pill whose
  position is a free `rawPos` while the finger holds it, then spring-animates
  (`Animatable(Offset, Offset.VectorConverter)` + `spring(NoBouncy, Medium)`) to the
  nearest screen edge via the new pure-JVM `services/DockSnapMath.kt`
  (`nearestEdge` START/END/BOTTOM with deterministic ties→START, `snap` clamps so the dock
  is never off-screen — including the START-edge bounds bugfix, and `constrainInside` keeps
  the dock fully visible while dragging).
- In landscape, or when docked on the START/END edge, the main row rotates to a vertical
  column. Expand/collapse (`AnimatedVisibility` + `MotionSystem.enter/exit`) reveals the
  quick rail.
- **≤2 taps for every tool**: quick rail gives 1-tap ERASER / TEXT / STICKER /
  RECTANGLE / ARROW / LINE / EYEDROPPER; every remaining `StrokeTool` is 2 taps via the
  tool picker; colors, width, size preview, brush presets, undo/redo, settings all stay
  on the dock.
- **Auto-tuck on pen input**: drawing with a freehand tool uses the existing
  `FloatingToolbarState.HIDDEN_DRAWING` state (nothing new to regress); tapping returns
  the dock. FREE of new permissions, no image assets.

## 2. Live brush & nib previews

### Before
- `PenNibVisualPreview` rendered a static nib ellipse at the selected width/color. The
  width-picker bottom sheet showed a plain `Canvas drawRoundRect` swatch.

### After
- New pure-JVM `services/NibPreviewMath.kt` drives the preview: `pressureToWidthScale`
  (0.35× resting → 1.15× press, monotone), `tiltToOpacityFactor` (0° = full pigment →
  90° = 0.30 fade), `wetnessToCoeffs` (bleed = 0.35w, blurWeight = 0.75w,
  keepAlpha = 1 − 0.62w), `strokeAlphaForWetness` (0.92 → 0.30 clamped), `featherRadius`,
  and a combined `previewParamsFor`.
- `PenNibVisualPreview` gained `pressure` / `tiltDeg` / `wetness` params with defaults that
  reproduce the classic render exactly (no behavior change for existing callers); when the
  params are set it renders a wetness bleed under-glow, wider/slimmer ink, and feathered,
  translucent edges.
- WidthPickerBottomSheet now shows a **live** preview: three sliders
  (Force 0.05–1 default 0.62, Tilt 0–75° default 18°, Wetness 0–1 default 0.25) feed the
  enhanced `PenNibVisualPreview` inside a 56dp white card with percentage labels.

## 3. Canvas minimap & spatial HUD

### Before
- The BottomEnd minimap showed only the page thumbnails + a viewport rect (owned by
  AnnotationCanvas, `showMinimap` hardcoded false at the call site).

### After
- **Spatial HUD** added above the minimap box in AnnotationCanvas: zoom % (spring-smoothed
  `animateFloatAsState`, `snap` under reduce-motion), active layer name + layer count,
  and the viewport bounds in canvas coordinates.
- **Zoom controls**: − / 100% / + buttons calling `zoomCanvasBy(mult)` which clamps scale
  0.5×–4× and keeps the canvas point under the viewport centre stationary
  (`updateZoomAndPan`); the existing pinch-to-zoom on the canvas already springs.
- **Jump-on-tap** was already implemented on the minimap box (`pointerInput` map→pan)
  and is preserved.
- **Toggleable**: persisted setting `minimap_hud_enabled` (SettingsManager) drives the
  canvas minimap; toggled from the Canvas Settings sheet and the Editor's own toggle.

## 4. Color Palette & Swatch Studio

### After
- `DesignerPalettes` (`services/PaletteCatalog.kt`): four curated studio palettes —
  **Nordic** (22), **Botanical** (22), **Cyberpunk** (22 neon-on-dark), **Warm
  Terracotta** (20). Every swatch's family is DERIVED at construction via
  `PaletteMath.familyFor` (never hand-labelled) so the Phase 19 family-invariant rule
  applies unchanged; `dedup` by ARGB; unknown name falls back to the vibrant catalog.
- ColorPickerBottomSheet gained a **Palette Studio** chip row (Vibrant / Nordic /
  Botanical / Cyberpunk / Terracotta); the "curated families" sections and the recent-color
  exclusion set are now driven by the active palette.
- New pure-JVM `services/HarmonicContrastMath.kt`: WCAG `relativeLuminance` (gamma 2.4),
  `contrastRatio` (1→21), `complementary` (180° HSL), `analogousPicks` (±30°), `padFor`
  (32-step lightness walk to AA ≥ 4.5:1, gamut-safe), and a `suggestions` set
  (Complement + 2 Analogues + Lighter + Darker, each with its measured ratio).
- **Contrast Studio** row in the color picker renders each suggestion with an AA/AAA badge
  against a white paper background; tapping a suggestion selects it.
- **Eyedropper magnifier loupe**: the old solid-color circle is now
  `EyedropperMagnifierLoupe` (AnnotationCanvas) — a 5×5 grid of pixels sampled from the
  **actual rendered page bitmap** (`activeRawBitmapMap` + `EyedropperSamplingMath
  .canvasToPagePixel`) around the pointer, magnified ~4×, with a centre-cell targeting
  ring and an on-loupe hex chip. Falls back to the paper color/plain circle when no bitmap
  exists. All Compose primitives — no image assets.

## Low-end fallbacks (AGENTS.md hardware rule — never silent)
- **Minimap HUD**: on `DeviceTier.LOW_END` devices it auto-disable once with a Snackbar
  ("Minimap HUD turned off … re-enable in canvas settings") and persists
  `minimapHudEnabled=false` + `lowEndMinimapWarningShown=true`. No silent degradation.
- **Dock / previews**: pure 2D layout + math — no AGSL, no bitmap caches per frame. The
  minimap viewport text uses the already-cached page thumbnails.
- **Eyedropper loupe**: guarded by `sampledColorPreview != null`; drops back to the paper
  color if the bitmap is absent/recycled.

## Reduce-motion compliance
- Dock edge-snap uses `spring` normally, plain `snap` when `LocalReduceMotion` is on.
- Minimap zoom % uses a spring animation normally, `snap` when reduce-motion is on.
- All transitions reuse existing `MotionSystem.enter/exit`.

## What I fixed along the way
- `DockSnapMath.snap` START edge: corrected the bottom clamp from the faulty
  `(sw + sh) - dh - m` to `(sh - dh - m).coerceAtLeast(m)`.
- EditorScreen: moved `showMinimap` declaration ahead of the `LaunchedEffect(Unit)`
  low-end block so the setting gate actually compiles/works.
- Removed the orphaned legacy doc-comment fragment left after deleting
  `FloatingBottomToolbarPill` (was breaking the build with "Expecting a top level
  declaration").

## Verification evidence
- `gradle testDebugUnitTest` → BUILD SUCCESSFUL (805 app tests, 0 failures; plugins LLM
  module green on fresh `--rerun-tasks`).
- `gradle assembleDebug` → BUILD SUCCESSFUL; `app-debug.apk` produced.
- New tests:
  - `NibPreviewMathTest` (23 tests) — pressure/tilt/wetness curves, clamping, combined params.
  - `DockSnapMathTest` (15 tests) — edge classification, on-screen anchors, drag constraints.
  - `DesignerPalettesTest` (10 tests) — dedup, gamut, alpha, derived-family invariant, fallback.
  - `HarmonicContrastMathTest` (18 tests) — spec luminances, 21:1 ratio, 180° complement,
    30° analogues, padFor ≥ 4.5, gamut/delta helpers.
- `PaletteCatalogTest` (unchanged, still green) — the family-classification invariant that
  the phase must not regress.

## Files
- new `app/src/main/kotlin/com/authorss81/noteflow/services/DockSnapMath.kt`
- new `app/src/main/kotlin/com/authorss81/noteflow/services/NibPreviewMath.kt`
- new `app/src/main/kotlin/com/authorss81/noteflow/services/HarmonicContrastMath.kt`
- `app/src/main/kotlin/com/authorss81/noteflow/services/PaletteCatalog.kt` (DesignerPalettes)
- `app/src/main/kotlin/com/authorss81/noteflow/services/SettingsManager.kt`
  (`minimapHudEnabled`, `lowEndMinimapWarningShown`)
- `app/src/main/kotlin/com/authorss81/noteflow/ui/components/PenNibVisualPreview.kt`
- `app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt`
  (minimap HUD + zoom controls, eyedropper magnifier loupe)
- `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt`
  (FloatingToolDock replacing FloatingBottomToolbarPill, live width preview, palette
  studio chips, contrast studio, minimap settings wiring)
- new tests under `app/src/test/java/com/authorss81/noteflow/`

## Constraints honored
- No new permissions; no DB schema change; `.github/workflows/` untouched.
- `ClipboardGuard`, FLAG_SECURE, and `allowBackup=false` intact; nothing decrypted is logged.
- No image assets added — dock, HUD, loupe all Compose primitives.