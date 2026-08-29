# Phase 238 — Responsive Layout (Tablet + Landscape + Floating Window) [BUG]

## Goal
Fix the broken tablet / floating-window / landscape layout — app assumes phone portrait, breaks when window is narrow, stretched, or in landscape. Add `WindowSizeClass` adaptive layout (Compact/Medium/Expanded), minimum content width, and landscape/floating-window safe area.

## Context — Verified Root Cause (User Screenshots)
User shared screenshots from `redfin-30-en_US-portrait_video.mp4` showing the app in **floating-window mode** on a tablet. The app has NO responsive layout:
1. **Middle "Sections" column** is a **narrow vertical strip** (only shows minus icon) — `UnifiedSidebar.kt:115` is rendered without `BoxWithConstraints` or `WindowSizeClass` checking, so on narrow width it just clips to nothing
2. **EditorScreen with `Markdown Editor`** — toolbar icons stack vertically in a narrow right column, `Live Preview` is empty/huge gap
3. **"Welcome to InkFlow" dialog** appears on top, also broken

**The app assumes phone-portrait layout only**:
- `HomeScreen.kt` sidebar (`UnifiedSidebar.kt:115`) — fixed `width(240-260.dp)`, overflows on narrow window
- `EditorScreen.kt` — toolbar `LazyRow` with many items + `Live Preview` — doesn't adapt
- `MarkdownPreviewScreen.kt` reader mode split pane — doesn't have tablet branch
- `EditorScreen.kt:2715` only uses `LocalConfiguration.orientation` (binary portrait/landscape), not `WindowSizeClass`
- `DockPosturePolicy.kt:23-46` switches toolbar orientation but canvas size assumes portrait

**No `WindowSizeClass` (`androidx.compose.material3.windowsizeclass`) used anywhere in the codebase** (grep 0 hits).

**Current orientation handling is incomplete:**
- `EditorScreen.kt:2715` `isLandscape = ORIENTATION_LANDSCAPE` — binary check, doesn't catch landscape-phone with narrow height
- `DockPosturePolicy.kt:23` portrait → HORIZONTAL pill, landscape → VERTICAL side column — but canvas geometry (`1528+64dp` page stride) is portrait-fixed
- `OverflowMenuPolicy.kt:29-31` "landscape / small screens" cap calculation — partial handling
- `Phase166LayoutOverflowTest.kt:24` "On a 360dp portrait screen (the narrowest supported width) a fixed-width Row" — current 360dp portrait baseline

**What's missing:**
- No `WindowSizeClass.calculateFromActivity()` provider
- No `BoxWithConstraints` for canvas + side panels
- No `if (orientation == LANDSCAPE && widthSizeClass == Compact)` branch (landscape phone)
- No `if (orientation == LANDSCAPE && widthSizeClass >= Medium)` branch (landscape tablet, two-pane)
- No floating window detection

## Files to Fix

### 1. Add `WindowSizeClass` dependency
- `gradle/libs.versions.toml` add: `androidx-compose-material3-window-size-class = { group = "androidx.compose.material3", name = "material3-window-size-class" }`
- `app/build.gradle.kts` add: `implementation(libs.androidx.compose.material3.window.size.class)`
- This is already part of `composeBom 2024.12.01` (`gradle/libs.versions.toml:88`), so just a `implementation` declaration needed

### 2. Create `MainActivity.kt` WindowSizeClass provider
- `MainActivity.kt:1380-1610` (3 `nestedScrollGuard` sites around 1393, 1475, 1602) — add `WindowSizeClass.calculateFromActivity(this)` in the root composable
- Provide `LocalWindowSizeClass` to the entire app
- Also provide `LocalConfiguration.orientation` for landscape detection

### 3. Fix `HomeScreen.kt:1783, 1830` LazyColumn in sidebar
- Add `BoxWithConstraints` to `HomeScreen.kt` to detect narrow width
- If `maxWidth < 600.dp`: collapse sidebar to drawer/bottom sheet, use single-pane layout
- If `maxWidth >= 600.dp`: use current two-pane layout
- **Landscape handling**: if `maxHeight < 480.dp` (landscape phone), collapse sidebar to drawer; if `maxWidth >= 840.dp` and `maxHeight >= 480.dp` (landscape tablet), use two-pane

### 4. Fix `EditorScreen.kt` for narrow window AND landscape
- `EditorScreen.kt:4414, 4496, 5022, 5389` (5 `nestedScrollGuard` sites) — wrap content in `BoxWithConstraints`
- Logic:
  - If `maxWidth < 600.dp` (compact): stack toolbar vertically, canvas full screen
  - If `maxWidth >= 600.dp` AND `maxHeight < 480.dp` (landscape phone): horizontal toolbar top, canvas split (editor + live preview side-by-side)
  - If `maxWidth >= 840.dp` AND `maxHeight >= 480.dp` (tablet landscape): two-pane with sidebar
  - If `maxWidth >= 600.dp` AND `maxHeight >= 600.dp` (tablet portrait): two-pane with sidebar on left
- Replace `EditorScreen.kt:2715` `LocalConfiguration.orientation` with `WindowSizeClass` + `BoxWithConstraints`

### 5. Fix `MarkdownPreviewScreen.kt:301, 1462, 1491`
- Same pattern: `BoxWithConstraints` to detect width AND height
- If narrow (portrait phone): keep vertical scroll (current)
- If wide (landscape phone, tablet portrait): side-by-side editor + preview (split pane)
- If very wide (tablet landscape): three-pane (sidebar + editor + preview)

### 6. Fix `UnifiedSidebar.kt:115`
- Add `widthIn(max = 240.dp, min = 80.dp)` so it doesn't overflow
- If width too small, hide sidebar or move to drawer
- Hide sidebar entirely on landscape phone (`widthSizeClass == Compact` + landscape)

### 7. Fix `MainActivity.kt` for floating window mode AND landscape
- Detect if app is in floating window (`WindowInsets.systemBars` bigger than expected)
- Apply appropriate padding for system bars
- For floating window, use `WindowInsetsCompat.Type.systemGestures()` insets
- For landscape, ensure nav bar positioning is on bottom (not side) and status bar is at top (not hidden)
- Add `WindowInsetsControllerCompat.isAppearanceLightStatusBars = true` for proper status bar theming

### 8. Update existing landscape detection
- `DockPosturePolicy.kt:23-46` already switches toolbar orientation — make sure it still works with new adaptive layout
- `OverflowMenuPolicy.kt:29-31` cap calculation — update to consider `widthSizeClass` not just `screenHeightDp`

### 9. Add tests
- `@Preview(device = Devices.PHONE, uiMode = UI_MODE_TYPE_NORMAL)` — phone portrait baseline
- `@Preview(device = Devices.PHONE_LANDSCAPE, uiMode = UI_MODE_TYPE_NORMAL)` — phone landscape
- `@Preview(device = Devices.TABLET, uiMode = UI_MODE_TYPE_NORMAL)` — tablet portrait
- `@Preview(device = Devices.TABLET_LANDSCAPE, uiMode = UI_MODE_TYPE_NORMAL)` — tablet landscape
- `@Preview(device = Devices.TABLET, uiMode = UI_MODE_TYPE_NORMAL, fontScale = 1.3f)` — large font
- Capture Paparazzi goldens for each

## Constraints
- **No schema change** — pure Compose layout fixes
- **No `.github/workflows/` edits** — manual fix
- **Mobile portrait MUST stay pixel-identical** — `@Preview(device = Devices.PHONE)` must produce same screenshot as before
- **No new dep** — `material3-window-size-class` is already in `composeBom 2024.12.01`

## DoD
- `gradle :app:assembleDebug` + `assembleRelease` green
- `gradle :app:lintDebug` 0 errors
- `gradle :app:testDebugUnitTest` 3420/0 green
- Phone portrait `@Preview` golden screenshot unchanged
- Phone landscape `@Preview` golden screenshot shows usable layout
- Tablet portrait `@Preview(Devices.TABLET)` golden screenshot shows usable layout
- Tablet landscape `@Preview(Devices.TABLET_LANDSCAPE)` golden screenshot shows usable layout
- Floating-window test on `redfin-30` shows usable layout
- `workspace/phase-238/REPORT.md` with before/after Paparazzi goldens (5 viewports: phone portrait, phone landscape, tablet portrait, tablet landscape, floating)
- All `nestedScrollGuard()` sites in floating-window-safe AND landscape-safe paths

## Timeout
240 minutes (touches 7+ files including MainActivity + landscape + floating window)
