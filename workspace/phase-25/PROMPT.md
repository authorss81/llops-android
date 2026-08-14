# Phase 25: InkStroke→Shape canvas plugin — free, compile-time [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with a working canvas (`AnnotationCanvas`), a shape auto-snap
(`ShapeRecognitionHelper.trySnapShape` runs on freehand draw-end when
`shapeAutoSnapEnabled`), and the hybrid plugin framework (Phases 10–11, 22).

**THE GOAL:** deliver an **InkStroke→Shape** capability as a **free, lightweight
compile-time plugin** (pure geometry, ~KB, no native deps — safe to ship in the
base APK under the hybrid model). It converts a user's freehand ink stroke into a
clean, crisp shape **on demand** in the canvas, as a proper plugin capability the
user can toggle off.

## What to build

1. **`InkToShapePlugin`** (plugin package, e.g. `plugins/inktos/`) implementing the
   Phase-10 `NoteflowPlugin` + a new `ShapeFromInk` capability (add to the
   `PluginCapability` sealed type if not present). Manifest, honest
   `availability()` (always available — pure geometry, no permissions), opt-in off
   by default, namespaced settings, registered in the Phase-10 registry and the
   Phase-21 store (bundled).
2. **Pure-JVM geometry core** (mirroring `ShapeRecognitionHelper`'s proven
   approach but as a plugin): given a stroke's raw points, detect and emit a clean
   snapped shape:
   - **Circle / ellipse** (perimeter-fit + circularity ratio)
   - **Rectangle / rounded-rect** (corner detection + edge alignment)
   - **Straight line** (endpoint + deviation)
   - **Arrow / polyline** (segment count + direction change)
   Reuse/port `ShapeRecognitionHelper`'s math where sensible (it is already pure
   JVM); the plugin core is independently testable with NO Android deps.
3. **On-demand conversion UI** — a canvas action ("Convert to shape") that takes
   the currently selected/latest freehand stroke, runs the plugin, and REPLACES
   the raw stroke with a clean shape (or inserts the shape alongside, keeping the
   original — make it configurable via a namespaced setting). Distinct from the
   existing *auto-snap on draw-end*: this is an explicit, user-triggered convert.
   `isAvailable()` reports the plugin enabled state; when disabled the action
   shows "unavailable — enable Ink→Shape in Plugins".
4. **Undo/redo safety** — conversion must be a normal undoable canvas operation
   (reuse the existing stroke history model); no silent data loss.
5. **Tests (pure-JVM)** — circle/rect/line/arrow detection accuracy on synthetic
   point sets (straight, slightly-wavy, closed-loop, wrong-shaped strokes that
   must NOT convert), setting-toggle behavior, capability routing.

## Integration requirements
- Registered in `PluginRegistry.defaultPlugins()` (bundled, off by default),
  toggleable in the Phase-21 store like other built-ins.
- `docs/PLUGINS.md` + `docs/PLUGIN_SDK.md`: document the new capability +
  implementation as the canvas-shape example.
- Keep plugin logic in its plugin package; the canvas only calls through the
  capability interface (no direct geometry reach-out).

## Definition of done
- `gradle testDebugUnitTest` passes (geometry + routing tests above).
- `gradle assembleDebug` succeeds; base-APK delta from this plugin is tiny (report
  it — should be KB, NOT MB).
- "Convert to shape" works end-to-end in the canvas for the four shape kinds,
  respects enable/disable, is undoable, and rejects non-shape strokes honestly
  (no fake conversion).
- REPORT.md records detection thresholds, the on-demand flow, undo integration,
  and size delta.

## Constraints
- Pure geometry ONLY — NO ML, NO camera, NO network, NO new permissions, NO native
  deps. This is deliberately a compile-time free plugin (it stays light).
- Do NOT change the DB schema. Do NOT edit `.github/workflows/`.
- Do NOT bypass `ClipboardGuard`. Never log decrypted note content.
- Do not break the existing auto-snap behavior — this plugin is additive and
  opt-in.