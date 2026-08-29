# Phase 238 — Responsive Layout (Tablet + Floating Window) [BUG]

## Goal
Fix the broken tablet / floating-window layout — app assumes phone layout, breaks when window is narrow or stretched. Add `WindowSizeClass` adaptive layout, minimum content width, and floating-window safe area.

## Context — Verified Root Cause (User Screenshots)
User shared screenshots from `redfin-30-en_US-portrait_video.mp4` showing the app in **floating-window mode** on a tablet. The app has NO responsive layout:
1. **Middle "Sections" column** is a **narrow vertical strip** (only shows minus icon) — `UnifiedSidebar.kt:115` is rendered without `BoxWithConstraints` or `WindowSizeClass` checking, so on narrow width it just clips to nothing
2. **EditorScreen with `Markdown Editor`** — toolbar icons stack vertically in a narrow right column, `Live Preview` is empty/huge gap
3. **"Welcome to InkFlow" dialog** appears on top, also broken

**The app assumes phone layout only** (`MainActivity.kt` uses `WindowInsets.systemBars` padding but never `WindowInsetsControllerCompat` / `WindowSizeClass`):
- `HomeScreen.kt` sidebar (`UnifiedSidebar.kt:115`) — fixed `width(240-260.dp)`, overflows on narrow window
- `EditorScreen.kt` — toolbar `LazyRow` with many items + `Live Preview` — doesn't adapt
- `MarkdownPreviewScreen.kt` reader mode split pane — doesn't have tablet branch

**No `WindowSizeClass` (`androidx.compose.material3.windowsizeclass`) used anywhere in the codebase** (grep 0 hits).

## Files to Fix

### 1. Add `WindowSizeClass` dependency
- `gradle/libs.versions.toml` add: `androidx-compose-material3-window-size-class = { group = "androidx.compose.material3", name = "material3-window-size-class" }`
- `app/build.gradle.kts` add: `implementation(libs.androidx.compose.material3.window.size.class)`
- This is already part of `composeBom 2024.12.01` (`gradle/libs.versions.toml:88`), so just a `implementation` declaration needed

### 2. Create `MainActivity.kt` WindowSizeClass provider
- `MainActivity.kt:1380-1610` (3 `nestedScrollGuard` sites around 1393, 1475, 1602) — add `WindowSizeClass.calculateFromActivity(this)` in the root composable
- Provide `LocalWindowSizeClass` to the entire app

### 3. Fix `HomeScreen.kt:1783, 1830` LazyColumn in sidebar
- Add `BoxWithConstraints` to `HomeScreen.kt` to detect narrow width
- If `maxWidth < 600.dp`: collapse sidebar to drawer/bottom sheet, use single-pane layout
- If `maxWidth >= 600.dp`: use current two-pane layout

### 4. Fix `EditorScreen.kt` for narrow window
- `EditorScreen.kt:4414, 4496, 5022, 5389` (5 `nestedScrollGuard` sites) — wrap content in `BoxWithConstraints`
- If `maxWidth < 600.dp`: stack toolbar vertically (current behavior)
- If `maxWidth >= 600.dp`: use horizontal layout (toolbar top, canvas full width, live preview side panel)

### 5. Fix `MarkdownPreviewScreen.kt:301, 1462, 1491`
- Same pattern: `BoxWithConstraints` to detect width
- If narrow: keep vertical scroll (current)
- If wide: side-by-side editor + preview (split pane)

### 6. Fix `UnifiedSidebar.kt:115`
- Add `widthIn(max = 240.dp, min = 80.dp)` so it doesn't overflow
- If width too small, hide sidebar or move to drawer

### 7. Fix `MainActivity.kt` for floating window mode
- Detect if app is in floating window (`WindowInsets.systemBars` bigger than expected)
- Apply appropriate padding for system bars
- For floating window, use `WindowInsetsCompat.Type.systemGestures()` insets

## Constraints
- **No schema change** — pure Compose layout fixes
- **No `.github/workflows/` edits** — manual fix
- **Mobile (phone) MUST stay pixel-identical** — `@Preview(device = Devices.PHONE)` must produce same screenshot as before
- **No new dep** — `material3-window-size-class` is already in `composeBom 2024.12.01`

## DoD
- `gradle :app:assembleDebug` + `assembleRelease` green
- `gradle :app:lintDebug` 0 errors
- `gradle :app:testDebugUnitTest` 3420/0 green
- Phone `@Preview` golden screenshot unchanged
- Tablet `@Preview(Devices.TABLET)` golden screenshot now shows usable layout
- Floating-window test on `redfin-30` shows usable layout
- `workspace/phase-238/REPORT.md` with before/after Paparazzi goldens (phone + tablet + floating window)
- All `nestedScrollGuard()` sites in floating-window-safe paths

## Timeout
180 minutes (touches 6+ files including MainActivity)
