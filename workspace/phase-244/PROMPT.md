# Phase 244 — Fix Minimap Off-Screen + Ink Bar Blocks Drawing + General Visual Layout

## Goal
Fix the visual layout issues from screenshots:
1. **Minimap is outside screen** — the minimap is positioned with hardcoded offsets that go off-screen on some devices/orientations
2. **Drag ink bar in top doesn't let you draw** — when the ink bar is at the top of the screen, it blocks drawing in that area
3. **General visual problems** — text overflow, sizing, spacing issues in various screens

## Context — Verified Root Cause (from screenshots)

### Bug 1: Minimap off-screen
**File:** `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt` around line 780+ (minimap HUD)
**Symptom (from user's report):** "minimap is otside screen" — the minimap is positioned in a way that makes it go off-screen on some devices/orientations

**Root cause:** The minimap position is likely calculated with hardcoded offsets (e.g., `Modifier.offset(x = X, y = Y)`) that don't account for different screen sizes, orientation, or system insets. The minimap should be positioned using `WindowInsets` and `WindowSizeClass`:
- If `WindowSizeClass.Compact` (phone portrait): position at bottom-right, small size
- If `WindowSizeClass.Medium` (phone landscape or small tablet): position at bottom-right, medium size
- If `WindowSizeClass.Expanded` (tablet landscape or large tablet): position at top-right, large size

The position should also respect `WindowInsets.systemBars` and `WindowInsets.displayCutout` so the minimap doesn't overlap with the notch or navigation bar.

### Bug 2: Ink bar blocks drawing
**File:** `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt` (InkBarPortraitBar / InkBarLandscapeBar)
**Symptom (from user's report):** "drag ink bar does not let us draw if we try to draw it drags the ink bar if it is in top" — the ink bar at the top of the screen blocks drawing in the top area of the canvas

**Root cause:** The ink bar at the top of the screen is a `Draggable` composable that intercepts touch events. When the user tries to draw near the top of the canvas, the ink bar's drag gesture catches the touch first, so drawing doesn't happen.

**Fix:** The ink bar should:
- Not intercept touch events when the user is drawing on the canvas (only intercept when the user explicitly touches the ink bar)
- Or position the ink bar OUTSIDE the canvas drawing area
- Or use a `pointerInput` that only consumes events when the touch is on the ink bar, not when it's on the canvas

### Bug 3: General visual problems
**Symptom (from screenshots):** "new visual problems" — text overflow, sizing issues

**Root cause:** Various screens may have:
- Text that doesn't fit in its container
- Buttons that are too small for touch targets
- Spacing that doesn't account for different screen sizes
- Missing `Modifier.weight()` or `Modifier.fillMaxWidth()`

## Files to Fix

### 1. `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt`
- Find the minimap HUD code (around line 780)
- Replace hardcoded offsets with `WindowSizeClass`-aware positioning
- Use `WindowInsets.systemBars` and `WindowInsets.displayCutout` for safe positioning
- Make sure the minimap stays within the visible area

### 2. `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt`
- Find the ink bar top/bottom positioning (InkBarPortraitBar / InkBarLandscapeBar)
- Make the ink bar not consume touch events when the user is drawing on the canvas
- Use a `pointerInput` that only consumes events on the bar itself

### 3. `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt` and related files
- Audit all screens for text overflow
- Add `Modifier.weight(1f)` or `Modifier.fillMaxWidth()` where needed
- Ensure all buttons meet minimum 48dp touch target size
- Ensure proper padding and margins

## Constraints
- No schema change
- No new dependencies
- No `.github/workflows/` edits
- Must not break existing tests
- Must not change the canvas drawing logic

## DoD
- `gradle :app:assembleDebug` + `assembleRelease` green
- `gradle :app:testDebugUnitTest` 3420+ tests green
- `gradle :app:lintDebug` 0 errors
- Manual test: minimap stays within screen bounds on phone portrait, phone landscape, tablet portrait, tablet landscape
- Manual test: drawing on the canvas works even when the ink bar is at the top
- Manual test: no text overflow on any screen
- `workspace/phase-244/REPORT.md` with file:line evidence
