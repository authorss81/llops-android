# Phase 25 — InkStroke→Shape: free, lightweight, compile-time canvas plugin

**Status:** DONE
**Date:** 2026-08-14
**Phase 24:** `workspace/phase-24/REPORT.md` (user-approved dynamic plugin updates)

## What Phase 25 delivers

A **free, lightweight, compile-time plugin** (`plugins/inktos/`) that converts a
user's freehand ink stroke into a clean, crisp shape **on demand** in the canvas:
LINE, RECTANGLE (incl. rounded-rect), ELLIPSE and ARROW. Pure JVM geometry — no
ML, no camera, no network, no new permissions, no native deps. It is a proper
`ShapeFromInk` plugin capability: opt-in off by default, toggleable in the
Phase-21 store like other built-ins, and surfaced in `CanvasSettingsBottomSheet`
as an explicit **"Convert to Shape"** action that is **distinct** from the
existing draw-end auto-snap (`ShapeRecognitionHelper.trySnapShape` is untouched).

```
user taps "Convert to Shape" (bottom sheet, canvas) → EditorScreen takes the
latest freehand stroke → NoteflowViewModel.convertStrokeToShape → PluginManager
withPluginAsync(ShapeFromInk) → InkToShapePlugin.convertToShape →
InkToShapeGeometry.detect(InkPoints) → crisp Stroke (or NotAShape) →
handleStrokesChange(updated) → undo stack (one undo) → Snackbar named shape
```

## 1. The capability + serving interface

- `PluginCapability.ShapeFromInk` — new sealed object, `key = "shape_from_ink"`,
  label "Ink to Shape", **non-exclusive**, added to `allCapabilities`
  (`plugins/PluginCapability.kt`).
- `ShapeKind` enum (`LINE` / `RECTANGLE` / `ELLIPSE` / `ARROW`),
  `ShapeFromInkOutcome` sealed (`Success(kind, snappedStroke, replaceOriginal)`,
  `NotAShape`, `Error`) and the `ShapeFromInkPlugin : NoteflowPlugin` serving
  interface (`convertToShape(stroke): ShapeFromInkOutcome`) — all in
  `plugins/NoteflowPlugin.kt`.
- `PluginStoreCatalog.category()` maps `ShapeFromInk → "Canvas"`
  (`plugins/store/PluginStoreCatalog.kt`).
- Registered in `PluginRegistry.defaultPlugins()` (bundled, off by default).

## 2. Pure-JVM geometry core — `plugins/inktos/InkToShapeGeometry.kt`

Android-free: takes `List<InkPoint>(x, y)` and returns `DetectedShape` (crisp
points, per-shape quality metrics) or `null`. Detection order + **thresholds**:

| Shape | Trigger (measured) |
|-------|--------------------|
| LINE | `straightness = directDistance/pathLength` > 0.82 AND perpendicular deviation < 0.10 × span. 2-point strokes accepted. |
| RECTANGLE (incl. rounded-rect) | closed loop (`direct < 0.28 × boundingDiag`), perimeter-fit ratio ≥ 0.72, corner-coverage ≥ 2, margin = `max(5, 0.06×diag)`. Checked BEFORE ellipse so a traced square stays a square. Snaps to the exact bounding-box corners (5 points, closed). |
| ELLIPSE | closed loop, ≥ 10 pts, ellipse-equation fit deviation < 0.35, circularity ≥ 0.30. Circle vs ellipse distanced by the circularity ratio; snaps to a 37-point ellipse. |
| ARROW | ≥ 8 pts, straightness in 0.55–0.95 (checked BEFORE line so long arrows whose head adds little to path length still convert), perpendicular deviation < 0.12 × span, final-segment direction change ≥ 10° (the head vee). |

Anything that fits none of these honestly returns `null` → `NotAShape`; the raw
stroke is **never** mutated or faked into a shape (no fake conversion). Tiny
specks (< 15 px bounding diagonal) are ignored.

## 3. The plugin — `plugins/inktos/InkToShapePlugin.kt`

- `class InkToShapePlugin : NoteflowPlugin, ShapeFromInkPlugin` — manifest id
  `com.authorss81.noteflow.plugins.inktos`, `SemanticVersion(1,0,0)`
  `PluginAvailability.Ok` always (pure geometry, permission-free).
- `convertToShape(raw)`: maps `Stroke.points` (pressure/tilt/timestamp preserved
  conceptually) → `InkPoint`s → `detect()` → crisp `Stroke` with tool switched to
  `LINE`/`RECTANGLE`/`ELLIPSE`/`ARROW`, the original color/width carried over.
- **Namespaced setting** `keepOriginal` (`plugins.<id>.keepOriginal`, default
  **off** = replace the raw stroke; on = insert the shape alongside and keep the
  ink). Companion constants `ID` / `SETTING_KEEP_ORIGINAL` are public.

## 4. On-demand conversion flow (distinct from auto-snap)

- **EditorScreen** (`ui/screens/EditorScreen.kt`): `inkToShapeAvailable` from
  `pluginRegistry.availablePlugins(ShapeFromInk, …)`, `inkToShapeKeepOriginal`
  from `pluginRegistry.settingsFor(InkToShapePlugin.ID)`; `convertLatestStrokeToShape()`
  (post-`handleRedo`) routes via the ViewModel; **all through the capability
  interface — no direct geometry reach-out from the canvas.**
- **ViewModel**: `convertStrokeToShape(stroke)` =
  `pluginManager.withPluginAsync(PluginCapability.ShapeFromInk, appContext) { (it as ShapeFromInkPlugin).convertToShape(stroke) }`.
- **UI**: `CanvasSettingsBottomSheet` gains an "Ink → Shape" section (below the
  Shape Auto-Snap toggle): a **Convert to Shape** button enabled only when the
  plugin is AVAILABLE; when off it reads **"Unavailable — enable Ink to Shape in
  Plugins"** and the button is disabled. "Keep original stroke" toggle.
- **Results**: `Success` → Snackbar "Converted ink to <kind>", stroke history
  replaced/kept per the toggle; `NotAShape` → honest message ("No clean shape
  detected — the stroke is too rough or not a line, circle, rectangle or
  arrow."); `Failure`/`Unavailable` → the plugin's reason, never a silent no-op.
- **Undo**: implemented through the existing `handleStrokesChange(updated)`,
  which pushes the previous `strokes` onto the undo stack — a conversion is one
  undo away (equal for keep-original mode). No new history machinery, no silent
  data loss.

## 5. Tests — `InkToShapePluginTest.kt` (25 pure-JVM tests)

- **Detection accuracy** on synthetic point sets: straight line (incl. 2 points),
  slightly-wavy (→ NotAShape), closed circle (→ ELLIPSE, circularity > 0.8,
  37 snapped points), closed ellipse, traced rectangle (→ RECTANGLE, snapped to
  the trace's real bbox, corner coverage ≥ 2), rounded-rect (→ RECTANGLE),
  horizontal arrow (direction change), zigzag (→ NotAShape), triangle + blob
  (→ NotAShape).
- **Plugin conversion**: line → crisp LINE stroke with matching bbox endpoints;
  zigzag → `NotAShape` (no fake conversion).
- **keep-original toggle**: off → replace; on → keep both strokes
  (`reg.setEnabled` / `reg.settingsFor` / `reg.notifyConfigChanged`).
- **Capability routing**: `NO_PLUGIN_INSTALLED` / `NONE_ENABLED` / `AVAILABLE`.
- **Store listing**: entry is bundled, `optional = false`, category "Canvas".

## 6. Docs

- `docs/PLUGINS.md`: capability added to the package table + the "implemented for
  real" paragraph; new **"Ink → Shape (`inktos/`)"** section with the detection
  threshold table, undo integration, and the capability-interface-only rule.
- `docs/PLUGIN_SDK.md`: new **"The `ShapeFromInk` capability contract"** in §3 —
  serving interface, `ShapeFromInkOutcome` invariants (never fake a shape,
  surface `NotAShape` honestly), undoable-apply requirement, availability
  surfacing, reference implementation pointer.

## 7. Size delta (base-APK cost)

Built with and without the plugin (`gradle assembleDebug`, clean/`--rerun-tasks`
comparison of the produced APK multi-dex):

| Metric | Baseline | With plugin | Delta |
|--------|----------|-------------|-------|
| DEX uncompressed | 98,848,376 B | 98,903,976 B | **+55,600 B (~54.3 KB)** |
| DEX compressed-in-APK | 32,408,741 B | 32,430,447 B | **+21,706 B (~21.2 KB)** |

Well inside the "KB, not MB" constraint — the plugin ships **compile-time** in
the base APK under the hybrid model (lightweight pure-JVM stays bundled;
heavy/native features stay downloadable, see `docs/plugin-architecture.md`).

## 8. Definition-of-done check

- `gradle testDebugUnitTest --tests InkToShapePluginTest` — 25 tests pass. Full
  suite: **489/489 pass, 0 failures** (verified at commit `00d9b35`, clean tree).
- `gradle assembleDebug` — BUILD SUCCESSFUL.
- "Convert to shape" wires end-to-end through the capability interface for all
  four shape kinds, respects enable/disable (button + "enable in Plugins" hint),
  is undoable via the existing stroke history, and rejects non-shapes honestly.
- Existing auto-snap (`ShapeRecognitionHelper`) is untouched — this phase is
  purely additive and opt-in.