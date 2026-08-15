# Phase 36 — Micro-Interactions & Fluid Motion (REPORT)

Status: **DONE**. `gradle testDebugUnitTest` (all subprojects) + `gradle assembleDebug` pass.
App unit suite now includes `MotionPolicyTest` (**10 tests**), plus the tuned-spring +
haptic-gate logic in the pure-JVM `services/MotionPolicy.kt`.

## What shipped

### 1. Shared element transition (note card → editor)
- **Card bounds capture**: `NotePageCard` (HomeScreen.kt) records the tapped card's
  window bounds via `onGloballyPositioned` → `boundsInWindow()` and hands them to
  `SharedElementState.rememberCard(...)` just before `onClick()`. Only the tapped
  card region is captured — sidebar/graph/FAB opens send no bounds and get a plain fade.
- **Editor morph**: new `ui/components/FluidPageReveal.kt` wraps the editor /
  Markdown preview in the compact MainActivity branch (`MainActivity.kt:350`).
  On page open it springs `scale` (from `MotionPolicy.revealStartScale` of the card
  width share) + `alpha` (0.25 → 1) with `MotionSystem.SpringReveal`, anchored at the
  tapped card's centre via `TransformOrigin`. This is cheap — two float `Animatable`s
  on a single `graphicsLayer`, nothing allocated per-frame.
- **Reduce-motion fallback**: when `LocalReduceMotion` is on (or no source bounds),
  the reveal snaps to identity — no motion at all, per AGENTS.md (never silent
  degradation; reduce-motion = no motion).

### 2. Haptic feedback design (all gated by haptics + reduce-motion)
New `MotionPolicy.hapticsEnabled(hapticsEnabled, reduceMotion)` is the single gate.
- **Shape auto-snap**: AnnotationCanvas.kt fires `LongPress` on a **snap success**
  (freehand → line/rect/ellipse/arrow) and a lighter `TextHandleMove` on a normal
  stroke commit. Previously the tick fired on every commit; now the snap milestone is
  distinct and both are individually gated.
- **Color-picker detents**: `ColorSwatch` (EditorScreen.kt) ticks on every swatch
  selection (recent colors, curated families, custom swatches) via `TextHandleMove`.
- **Slider notches**: the width slider in `WidthPickerBottomSheet` ticks whenever the
  value crosses a whole-point notch (`MotionPolicy.sliderNotchTriggered`, granularity
  1pt) — not on every pixel of drag.
- **Haptics setting**: `SettingsManager.hapticsEnabled` (new, default ON), surfaced
  with a toggle in `CanvasSettingsBottomSheet` and provided app-wide via
  `LocalHapticsEnabled` (Theme.kt).
- Reduce-motion kills all haptics through the same gate — no haptic can fire when the
  user disabled motion.
- Lasso: the canvas has **no lasso tool** (verified by grep), so per the PROMPT the
  "selection completes" milestone is covered by swatch selection (colour-picker
  detents) instead; documented, not stubbed.

### 3. Spring-physics gestures
- `services/MotionPolicy.kt` (pure JVM) is the single tuning source:
  - `SHEET` (0.9 damping / 300 stiffness) — bottom-sheet expansion,
  - `DISMISS` (1.0 / 500) — swipe-to-dismiss, no overshoot,
  - `CANVAS_PAN` (1.4 / 200) — overdamped so canvas pan overshoot never oscillates,
  - `REVEAL` (0.8 / 260) — shared-element morph.
- `theme/Motion.kt` exposes `SpringSheet`, `SpringDismiss`, `SpringCanvasPan`,
  `SpringReveal` built from those numbers — no magic numbers in UI code. Existing
  inline springs in AnnotationCanvas (minimap zoom, sticky-note scale) were migrated
  to `SpringCanvasPan` / `SpringReveal` so tuning is centralized.
- Per-frame discipline from the perf phases is preserved (single `graphicsLayer`
  values, no new allocations per frame); all animations are skipped under
  reduce-motion (spring → `snap()`).

## Reduce-motion compliance (file:line)
- `theme/Motion.kt:29` `isSystemReduceMotionEnabled` (pre-existing) drives
  `LocalReduceMotion`; every new animation honours it:
  - `FluidPageReveal.kt` snaps instead of animating,
  - AnnotationCanvas minimap/sticky-note specs swap to `snap()`,
  - `MotionPolicy.hapticsAllowed` = false under reduce-motion.
- No new permissions. No DB schema change. `.github/workflows/` untouched. No heavy
  animation deps. No decrypted content logged.

## Low-end verification
- `FluidPageReveal` runs two `Animatable<Float>`s with `graphicsLayer` (GPU-composited,
  no layout thrash); container measured once via `onSizeChanged`.
- Pure-JVM `MotionPolicy` has no android imports so the tuning decisions are covered
  by `MotionPolicyTest` and free-testable on CI.
- Haptics only fire on discrete milestones (snap success / swatch tap / notch
  crossing), never on continuous drag streams — minimal vibrator churn on low-end
  devices.

## Test evidence
- `MotionPolicyTest` (10 tests): haptic gate truth table, per-kind spring tuning ,
  overdamped canvas-pan invariant, notch-crossing detection, reveal-scale clamp,
  invalid-tuning rejection.
- Full suite: `gradle testDebugUnitTest` + `gradle assembleDebug` pass.