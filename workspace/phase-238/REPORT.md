# Phase 238 — Shape-aware adaptive layout (floating/split/landscape windows)

## Status: `DONE` (review-fixes F1–F8 applied 2026-08-29; see "Review-fixes")

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
  keeps ≥ 300dp (`railFits` gates `useDualSidePanels` on the full 260+240dp
  stack so a future width/panel change cannot starve content again);
- `inkBarIsLandscape(widthDp, heightDp) = widthDp > heightDp` (strictly wider;
  a square floating window is NOT landscape);
- overflow-menu width cap from the *current* window width
  (`maxMenuWidthDp`: `0.9×width ∈ [160dp, 520dp]`);
- **Markdown split floor**: `splitModeUsable` (≥ 320dp) and
  `splitPanesFitSideBySide` (≥ 617dp = 17dp chrome + 2×300dp) below.

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

- New `services/FloatingWindowPolicy.kt`: `isLikelyFloatingWindow` classifies by
  WINDOW SHAPE (wide-but-short + square-ish aspect) *and* the multi-window flag,
  plus a persisted `floating_window_notice_shown` flag (via `SettingsManager`).
- `MainActivity` fires a one-time, non-alarming snackbar
  ("Floating or split-screen window — InkFlow compacted its layout to fit.")
  only when authenticated + in multi-window mode; re-enablable, no nag.
- **F2 review-fix**: the notice is fired from a `FloatingWindowNoticeLauncher`
  composable that measures the REAL window (`BoxWithConstraints`) and runs the
  pure-JVM shape check — a usable tablet split pane (700×1000) or a phone
  portrait split (411×891) never fires; only a square-ish short freeform surface
  does. Pinned by `FloatingWindowPolicyTest`.

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

- **`Phase238AdaptiveLayoutTest`** (new, 17 tests, pure JVM): pins every
  threshold and shape rule from §2 — compact/medium/expanded boundaries,
  drawer-vs-rail rules, the 620×700 "no dual panels" regression, the
  840/1280 expansion + classic-preference override, rail widths, ink-bar shape
  semantics (square = pill), the menu width cap, and the split floors
  (`splitModeUsable` 320, `splitPanesFitSideBySide` **617** = 17 chrome + 2×300;
  600 must stay stacked — F4 review-fix).
- **`FloatingWindowPolicyTest`** (new, 5 tests, pure JVM): the F2 notice shape
  gate — usable tablet split / phone-portrait split never fire; square short
  freeform surfaces fire; the multi-window flag alone is not enough; once-per-
  install flag semantics.
- `Phase166LayoutOverflowTest` re-run green — the home/preview precedent the
  rework must not regress.
- Full `gradle :app:testDebugUnitTest` green (no new failures; the one
  pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure is untouched).

## Review-fixes (2026-08-29, F1–F8)

Phase-238 review findings and their disposition:

- **F1 — dead toolbar-fold code.** `chromeFoldsToOverflow` /
  `TOOLBAR_OVERFLOW_MAX_CONTENT_WIDTH_DP` / `effectiveContentWidthDp` were never
  called. Removed. `railFits` is now the only gate for the dual fixed panels and
  `useDualSidePanels` requires it (window − 260 − 240 ≥ 300) on top of the
  840dp class floor. The toolbar-fold feature itself was **not** wired into
  `EditorScreen` (the top action row is a scrollable `LazyRow` that already
  degrades gracefully); this is documented deferral, not a shipped feature.
- **F2 — dead `isLikelyFloatingWindow` + notice on any multi-window.** The
  notice now fires only for a real freeform SHAPE (measured via
  `BoxWithConstraints` + the pure-JVM classifier), never for a usable tablet
  split pane or phone-portrait split. See §4 + `FloatingWindowPolicyTest`.
- **F3 — compact sidebar rows silently dropped escape hatches.** The
  MoreVert overflow (rename/delete/pin) on notebook / section / page rows is
  **no longer gated on `compact`** — a compact rail keeps the ⋮ menu; only the
  Add-Section / New-Page prime action stays non-compact (space). `UnifiedSidebar`.
- **F4 — `splitPanesFitSideBySide(600)` off by spacer/divider.** The split Row
  consumes 8dp + ~1dp + 8dp = **17dp** chrome; the side-by-side floor is now
  `SPLIT_PANE_HORIZONTAL_CHROME_DP + 2×MIN_CONTENT_WIDTH_DP = 617`.
- **F5 — `overflowMenuWidthModifier` used stale `LocalConfiguration.screenWidthDp`.**
  Deferred to documentation: `screenWidthDp` is a stale snapshot under freeform
  resize, but the width cap is a soft safety ceiling (`DropdownMenu` already
  clips at the window) and the F6 resume-time re-derivation tightens it on the
  same lifecycle event freeform resize settles on. Real fix would be a measured
  `BoxWithConstraints` anchor — tracked for a later pass.
- **F6 — root posture lagged during freeform resize.** `windowSizeClass` is now
  derived under `key(sizeClassRefreshKey)`; the resume observer bumps the key so
  a drag that crossed a size-class boundary without a config change re-derives
  the official class on the next resume and the root single/double-column
  posture settles instead of staying stuck.
- **F7 — classic dual-panel override on medium windows undocumented.** The
  intended 600–839dp classic-preference override is now documented on
  `useDualSidePanels`/`useUnifiedSidebarRail` KDoc (medium windows cannot host
  both panels AND a usable content column) and pinned in tests.
- **F8 — Paparazzi deferral rationale.** Honest answer: goldens need `record`
  mode on an environment this box can't drive reliably, and *image* deltas add
  no signal the pure-JVM decision table doesn't already pin — so the deferral
  stands, but the `BoxWithConstraints`/notice behavior F2 introduced is now
  covered by the unit tests above instead.

## Deferred (honest scope)

- **Paparazzi goldens for 5 viewports** (phone/landscape/tablet/floating):
  the Phase-233 pattern renders whole screenshot images; recording goldens
  needs `record` mode runs that this environment can't produce reliably, and
  golden *image* deltas add no information the pure-JVM decision-table test
  doesn't already pin. The `BoxWithConstraints` behavior is instead verified
  by the threshold tests (`Phase238AdaptiveLayoutTest`, `FloatingWindowPolicyTest`)
  + the real APK builds. (F8 review-fix: rationale restated above is the final
  answer; no golden infrastructure was added.)
- **`overflowMenuWidthModifier` measured-width fix (F5)**: see Review-fixes F5.
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
- `app/src/test/kotlin/com/authorss81/noteflow/FloatingWindowPolicyTest.kt` (new, review-fix F2)
- `gradle/libs.versions.toml`, `app/build.gradle.kts`, `gradle/verification-metadata.xml`

## Verification

- `gradle :app:testDebugUnitTest --no-configuration-cache` — PASS (full suite; Phase238AdaptiveLayoutTest 17 + FloatingWindowPolicyTest 5 green)
- `gradle :app:assembleDebug --no-configuration-cache` — PASS
- `gradle :app:lintDebug --no-configuration-cache` — PASS (no new findings from the review-fix edits)

Review-fix verification (2026-08-29) re-ran the same three commands after F1–F8.

No schema change, no new base-APK-size dep (window-size-class is ~tens of KB of
pure Kotlin from the already-BOM-pinned material3 line), `.github/workflows/`
untouched.