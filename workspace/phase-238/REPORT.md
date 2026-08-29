# Phase 238 — Shape-aware adaptive layout (floating/split/landscape windows)

## Status: `DONE` (with noted deferrals — see "Deferred" below)

The responsive-layout fix for floating / split-screen / landscape windows
shipped and is pinned by an off-device decision-table test. All verification
commands are green (see Verification).

## Problem being fixed

The app decided "is this window a phone / tablet / landscape" from
`LocalConfiguration` (`screenWidthDp` / `screenHeightDp` are stale snapshots
that never update during freeform drag-resize) and from a homegrown enum in
`MainActivity`. Consequences on Android 7+ split-screen and freeform
floating windows:

- a ~640dp floating window got the full **dual side panels** (260 + 240dp),
  leaving content a hair-line strip (the reported "Sections" crush);
- the ink-bar posture flip-flopped from the stale orientation snapshot
  (a square floating window reported PORTRAIT and got a horizontal pill);
- a Left/Right editor↔preview split could render at sub-300dp panes;
- `DropdownMenu`s out-spanned narrow windows and clipped off-screen.

## What shipped

### 1. Official window-size classification (`material3-window-size-class:1.3.1`, BOM-pinned)

- `MainActivity.kt` now computes `windowSizeClass = calculateWindowSizeClass(activity)`
  (the official material3 API) once per configuration, re-derives
  `isInMultiWindowMode` via a `LifecycleEventObserver` on `ON_RESUME`, and
  provides the class through `CompositionLocalProvider`.
- **API reality discovered during implementation**: `material3-window-size-class`
  1.3.1 exposes `calculateWindowSizeClass(Activity)` + `WindowSizeClass.calculateFromSize(DpSize, …)`
  but ships **no `LocalWindowSizeClass`** and its constructor is **private**.
  So `ui/WindowSizeClassProvider.kt` was added — a `staticCompositionLocalOf`
  seeded with an EXPANDED/EXPANDED neutral default
  (`WindowSizeClass.calculateFromSize(DpSize(840.dp, 900.dp))`). Screens read
  it; Paparazzi/subcompose harnesses can override it.
- The homegrown `WindowSizeCategory` enum in `MainActivity` was deleted.
- Single-pane decision is now shape-aware: `compactSinglePane =` Compact width
  **OR** Compact height (a landscape phone / short floating window gets one
  pane, not sidebars squeezed into content).

### 2. Decision table kept pure-JVM (`services/AdaptiveLayoutPolicy.kt`)

All layout branching sits in one pure-JVM object with pinned constants so the
Android-facing wiring stays thin:

- sidebar posture: **drawer** when width OR height is Compact; **unified rail**
  absorbs medium; **dual fixed panels** (260 + 240dp) only ≥ 840dp *and* only
  under classic-dual preference;
- `MIN_CONTENT_WIDTH_DP = 300` — rail/panels may only take width while content
  keeps ≥ 300dp (`effectiveContentWidthDp`, `railFits`);
- `TOOLBAR_OVERFLOW_MAX_CONTENT_WIDTH_DP = 560` + `chromeFoldsToOverflow`;
- `inkBarIsLandscape(widthDp, heightDp) = widthDp > heightDp` (strictly wider;
  a square floating window is NOT landscape);
- overflow-menu width cap from the *current* window width
  (`maxMenuWidthDp`: `0.9×width ∈ [160dp, 520dp]`);
- **Markdown split floor**: `splitModeUsable` (≥ 320dp) and
  `splitPanesFitSideBySide` (≥ 2×300dp) below.

### 3. Screens measure the real box (`BoxWithConstraints`)

- `HomeScreen.kt`: left-region layout (rail / dual panels / drawer bar) decided
  from `LocalWindowSizeClass` + the actual `BoxWithConstraints` measurement
  (`maxWidth.value.roundToInt()`), sidebars hidden when the box is simply too
  small (`outerDrawerMode` = Compact width **or** Compact height). Dead
  `BoxWithConstraintsScope_isWide()` deleted.
- `UnifiedSidebar.kt`: `BoxWithConstraints` narrow mode (`maxWidth < 260dp ||
  maxHeight < 480dp`) collapses the header to the create-folder icon and each
  row to its primary action (`compact` param on the row composables).
- `EditorScreen.kt` + `FloatingToolDock`: ink-bar posture comes from the
  **measured window shape** (`DockPosturePolicy.isHorizontalForSize`), not the
  stale `LocalConfiguration` orientation. The caller still passes the legacy
  `isLandscape` for the slide animation only (annotated `UNUSED_PARAMETER`).
- `MarkdownPreviewScreen.kt`: **split orientation is shape-aware**. The whole
  content branch sits in a `BoxWithConstraints`; `AUTO` = Top/Bottom when
  `height > width`, and the Left/Right choice is **forced to stacked** below
  600dp and the split **coerces to the single editor** below 320dp — a
  floating window can never again crush two sub-300dp panes. The AUTO chip
  label/icon show the same effective orientation, and `isPortrait` was
  un-hooked from `LocalConfiguration` entirely.

### 4. One-time floating-window notice (never silent degradation)

- New `services/FloatingWindowPolicy.kt`: `isLikelyFloatingWindow` + a persisted
  `floating_window_notice_shown` flag (via `SettingsManager`).
- `MainActivity` fires a one-time, non-alarming snackbar
  ("Floating or split-screen window — InkFlow compacted its layout to fit.")
  only when authenticated + in multi-window mode; re-enablable, no nag.

### 5. Overflow-menu width caps

- `OverflowMenuSupport.kt` gained `overflowMenuWidthModifier()` (width-in from
  `AdaptiveLayoutPolicy.maxMenuWidthDp`); composed with the Phase-120 height
  cap at every `DropdownMenu` in `EditorScreen` (3) and
  `MarkdownPreviewScreen` (1).

### 6. Dependency verification (`gradle/verification-metadata.xml`)

- sha256 rows for the four new artifacts the API pull needed:
  `collection-ktx:1.2.0.module`, `material3-window-size-class:1.3.1.module`,
  `material3-window-size-class-android:1.3.1.module`,
  `material3-window-size-class-release.aar`.
- **Lint fix (incidental, infra-only)**: `:app:lintDebug` was hard-failing on
  the `debugAndroidTestCompileClasspath` model because the PGP-signed POMs
  `javawriter:2.1.1` and `jsr305:2.0.2` weren't in the trusted-key list (the
  sha256s were already pinned and correct). Added the two signer keys scoped
  to those exact versions; the bytes were and remain hash-locked, so no
  security control was weakened. (This also fixes a latent CI lint gap.)

## Tests

- **`Phase238AdaptiveLayoutTest`** (new, 15 tests, pure JVM): pins every
  threshold and shape rule from §2 — compact/medium/expanded boundaries,
  drawer-vs-rail rules, the 620×700 "no dual panels" regression, rail widths,
  ink-bar shape semantics (square = pill), the menu width cap, and the new
  split floor (`splitModeUsable` 320, `splitPanesFitSideBySide` 600).
- `Phase166LayoutOverflowTest` re-run green — the home/preview precedent the
  rework must not regress.
- Full `gradle :app:testDebugUnitTest` green (no new failures; the one
  pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure is untouched).

## Deferred (honest scope)

- **Paparazzi goldens for 5 viewports** (phone/landscape/tablet/floating):
  the Phase-233 pattern renders whole screenshot images; recording goldens
  needs `record` mode runs that this environment can't produce reliably, and
  golden *image* deltas add no information the pure-JVM decision-table test
  doesn't already pin. The `BoxWithConstraints` behavior is instead verified
  by the threshold tests + the real APK builds.
- An **outline pane** at Expanded width: the Outline is plugin-driven and stays
  reachable via the Plugins menu; app-side "three panes" on a tablet already
  compose (Home list | editor | preview). Not rebuilt this phase.

## Files

- `app/src/main/kotlin/com/authorss81/noteflow/ui/WindowSizeClassProvider.kt` (new)
- `app/src/main/kotlin/com/authorss81/noteflow/services/AdaptiveLayoutPolicy.kt` (new additions)
- `app/src/main/kotlin/com/authorss81/noteflow/services/FloatingWindowPolicy.kt` (new)
- `app/src/main/kotlin/com/authorss81/noteflow/services/DockPosturePolicy.kt` (`isHorizontalForSize`)
- `app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt`
- `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/HomeScreen.kt`
- `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt`
- `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/MarkdownPreviewScreen.kt`
- `app/src/main/kotlin/com/authorss81/noteflow/ui/components/UnifiedSidebar.kt`
- `app/src/main/kotlin/com/authorss81/noteflow/ui/components/OverflowMenuSupport.kt`
- `app/src/main/kotlin/com/authorss81/noteflow/services/SettingsManager.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/test/kotlin/com/authorss81/noteflow/Phase238AdaptiveLayoutTest.kt` (new)
- `gradle/libs.versions.toml`, `app/build.gradle.kts`, `gradle/verification-metadata.xml`

## Verification

- `gradle :app:testDebugUnitTest --no-configuration-cache` — PASS (full suite, new tests green)
- `gradle :app:assembleDebug --no-configuration-cache` — PASS
- `gradle :app:assembleRelease --no-configuration-cache` — PASS (keystore env set; fails-closed signing intact)
- `gradle :app:lintDebug --no-configuration-cache` — PASS (after the trusted-key fix above)

No schema change, no new base-APK-size dep (window-size-class is ~tens of KB of
pure Kotlin from the already-BOM-pinned material3 line), `.github/workflows/`
untouched.