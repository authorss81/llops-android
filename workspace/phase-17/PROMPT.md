# Phase 17: Premium brush engine — real libmypaint (NDK + JNI), tiered fallback

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app. Its paint engine today is **AGSL GPU shaders** (Phase 4) with a
**vector-based fallback** for API < 33, plus wet-mixing math, stabilizer,
pressure, symmetry. This phase enables a REAL, OPT-IN **libmypaint** brush engine
as a premium tier for high-end devices — with AGSL as default and vector as
fallback. This is NOT a stub: you will vendor the actual C library and build it
with NDK/CMake + JNI.

## Research context (verified)
- libmypaint is the real engine behind MyPaint, GIMP, OpenToonz. Android ports
  exist (`mypaint_ffi` Flutter plugin; the `PaintingWorld` NDK sample).
- Maintainers' known limitation (mypaint issue #103): some brushes (e.g.
  `basic_digital_knife`) are too slow for phone CPUs. HENCE the tiered design:
  libmypaint only on high-end devices; AGSL/vector everywhere else.

## Requirements
1. Vendor libmypaint: fetch the upstream source (tag v1.6.1) into
   `app/src/main/cpp/`, add `CMakeLists.txt`, build the static library via the
   NDK. Add a thin **JNI layer** exposing: `brush_new/load`, `stroke_begin`,
   `stroke_to(x,y,pressure)`, `stroke_end`, `render_tile`, `brush_list`.
2. Expose as a Kotlin service `LibMyPaintEngine` implementing the SAME interface
   as the AGSL engine so `AnnotationCanvas` can swap engines at runtime.
3. Tiering: consult `DeviceCompatibilityManager`. libmypaint tier = high-end
   devices (see existing heuristics). Middle tier = AGSL (API 33+). Low =
   vector fallback (API<33). Default selection = AGSL unless user opts in via
   settings → "Pro brush engine (libmypaint)".
4. Provide 5+ real brushes via bundled `.myb` brush defs (translated to
   libmypaint's JSON format) that run acceptably fast (basic brushes, not the
   heavy knife).
5. Unit tests: pure-JVM tests for tier-selection logic and brush-catalog
   validation. JNI correctness is verified by building (CI can't run GPU), so
   the build must succeed via `gradle assembleDebug` with NDK/CMake.
6. Remove any residual "mypaint was removed" TODOs; docs must describe the real
   integration. Update `docs/BLACKBOARD`/README engine docs.
7. If libmypaint C code cannot build on a given API level, compile with `-DLIBMYPAINT` guards so AGSL tier still ships — but the DEFAULT build MUST compile the NDK library.

## Definition of done
- `gradle assembleDebug` succeeds (NDK + CMake + JNI compile must pass in CI).
- `gradle testDebugUnitTest` passes: tier selection, brush catalog, engine
  swap logic.
- Settings toggle "Pro brush engine (libmypaint)" persists and takes effect.
- No stub JNI; real library linked. `docs/PLUGINS.md` or engine docs updated.

## Constraints
- Do NOT change the DB schema. Do NOT edit `.github/workflows/`.
- No new permissions. APK size growth from libmypaint is acceptable.
- Do NOT commit the brush-mixing C source of mypaint's heavier features beyond
  what's needed; keep the port minimal and auditable.
- Respect `ClipboardGuard` if any clipboard interaction is added (unlikely).
- No fake behavior. If a brush is too slow on a device, it must be gated by the
  tiering, not silently shipped.