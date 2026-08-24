# Phase 197: Per-Brush Stroke Stabilizer Tuning + Smoothing Slider [PERF 1.2]

**Goal:** Make stabilizer adaptive per input/brush + expose user control.

**Context:** `services/StrokeStabilizer.kt:36-37` `DEFAULT_WINDOW_SIZE=8` `DEFAULT_PREDICTION=0.15f` fixed for all brushes/inputs. `services/BrushPreset.kt:20` already has `BrushPreset` model.

**Steps:**
1. Add `smoothing: Float` to `BrushPreset` (0..1 maps to windowSize 2..12). Finger input → more smoothing, stylus → less. Fast brushes (calligraphy) tighter than marker.
2. Add Settings slider 0–100% (`services/SettingsManager.kt`) → windowSize mapping. Persist, immediate apply.
3. Update `AnnotationCanvas.kt:347` `create(windowSize, prediction)` to read preset + slider + input source.
4. Golden tests in `StrokeStabilizerTest` for window 2/8/12.

**DoD:** All brush presets have smoothing, slider works, `gradle assembleDebug`/`test` green, REPORT.md with before/after stroke samples.
