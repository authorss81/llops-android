# Phase 36: Micro-Interactions & Fluid Motion [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with a Glass theme and a `theme/Motion.kt` (existing motion primitives),
Compose Material 3, and haptics already used in places (Phase 05 added haptics).
**Read `docs/phase-status.md` first** — do not regress prior work; respect the
existing reduce-motion setting everywhere.

**THE GOAL:** make every interaction feel alive and tactile with **fluid shared
element transitions, designed haptics, and spring-physics gestures** — real,
measurable, and low-end safe (AGENTS.md: never silent degradation; respect
reduce-motion).

## 1. Shared Element Transitions
- Implement **fluid morph animations** between note cards on the home grid and
  the active editor/canvas view: the tapped card's visual (title, snippet,
  thumbnail) morphs/expands into the editor opening.
- Use Compose shared-element style (e.g. `AnimatedContent`/`AnimatedVisibility`
  with matching bounds/alpha/scale, or a MotionLayout-style approach — no new
  heavy deps). Fall back to a simple fade/scale when reduce-motion is on.

## 2. Haptic Feedback Design
- Integrate subtle, tactile haptics via `HapticFeedbackType` (or `View.performHapticFeedback`/
  `Vibrator` guarded by the existing haptic setting) for **gesture milestones**:
  - shape auto-snapping (Phase 03/09 `ShapeRecognitionHelper` snap success),
  - color-picker detents (swatch selection),
  - slider notches (pressure/size sliders),
  - lasso selections (if lasso exists, else when a selection completes).
- Keep haptics subtle and off when the user disabled haptics or reduce-motion.

## 3. Spring-Physics Gestures
- Apply **natural damping ratios** to: swipe-to-dismiss actions, bottom-sheet
  expansions (`ModalBottomSheet`/custom sheets), and canvas panning overshoots.
- Use `spring(dampingRatio, stiffness)` with tuned constants (not magic numbers —
  define in `theme/Motion.kt`); ensure no jank on low-RAM (reuse existing
  animation-frame discipline from perf phases).

## Definition of done
- Shared element morph: note card → editor works with reduce-motion fallback.
- Haptics fire on the listed milestones, subtle, honoring the haptic + reduce-motion
  settings (`file:line` evidence).
- Springs used for swipe/sheet/canvas-pan with tuned damping in `Motion.kt`;
  no jank regressions.
- `gradle testDebugUnitTest` + `gradle assembleDebug` pass.
- Pure-JVM tests where logic is testable (e.g. spring constant selection,
  haptic-enable decision).
- REPORT.md: interactions list, reduce-motion compliance, low-end verification.

## Constraints
- No new permissions. No DB schema change. Do NOT edit `.github/workflows/`.
- Respect `reduce-motion` and the haptics setting EVERYWHERE (never force motion
  on a user who disabled it).
- No heavy animation deps. Keep per-frame cost low (reuse perf-phase discipline).
- Never log decrypted content. Keep the security model intact.