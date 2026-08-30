# Phase 251 — WindowSizeClass refreshes on freeform drag-resize (not just ON_RESUME)

## Goal
The `WindowSizeClass` is only re-derived on `Lifecycle.Event.ON_RESUME` (`MainActivity.kt:283-300, 644-655`). A freeform window being drag-resized by the user does NOT trigger `onResume` — the activity stays in the foreground the whole time. Result: a freeform drag that crosses 600dp or 840dp width boundaries with no pause/resume leaves the entire `AdaptiveLayoutPolicy` system (single-pane vs double-pane, NavigationRail, HomeScreen+EditorScreen split, dock posture) stuck on the OLD size class. This is a CRITICAL class of defect because every screen reacts to the class.

## Context — verified at `2709453`

- `MainActivity.kt:283-300`:
  ```kotlin
  val multiWindowLifecycleObserver = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
          ...
          sizeClassRefreshKey++  // or similar
      }
  }
  ```
- `MainActivity.kt:644-655`:
  ```kotlin
  key(sizeClassRefreshKey) {
      calculateWindowSizeClass(activity)
  }
  ```
- A freeform drag-resize does NOT trigger `onResume` (the activity was already resumed). It also does not necessarily trigger `onConfigurationChanged` if the system uses the modern `resize` event (which only updates `WindowMetrics`).
- No `DisposableEffect(LocalConfiguration)` or `LocalConfiguration` listener in this composition.
- Result: a freeform window crossing 600dp or 840dp with no pause/resume keeps the OLD size class. The single-pane vs double-pane branch (`MainActivity.kt:670-892`) — including the decision to render the HomeScreen+EditorScreen two-pane Row — stays stuck.

## Files to change

### 1. `app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt`
- Add a `DisposableEffect(LocalConfiguration.current)` that bumps `sizeClassRefreshKey` on every config change (which includes orientation, density, AND resize events that fire `onConfigurationChanged`).
- Alternative: add a `LocalConfiguration.current.orientation` AND `screenWidthDp` AND `screenHeightDp` listener via a custom `ConfigurationChangeObserver` (the simplest: `LaunchedEffect(LocalConfiguration.current) { sizeClassRefreshKey++ }`).
- The official `calculateWindowSizeClass(activity)` takes the activity; re-keying on `LocalConfiguration.current` triggers a re-call of `calculateWindowSizeClass(activity)`, which queries `WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity)` — this DOES track real freeform resizes per the Android docs.
- Pin: the `WindowSizeClass` is re-derived on `LocalConfiguration.current` change, not just on `ON_RESUME`.

### 2. `app/src/main/kotlin/com/authorss81/noteflow/ui/WindowSizeClassProvider.kt`
- The `staticCompositionLocalOf { ... 840x900 }` default is a tablet-shaped placeholder (already flagged in audit 3/5 M1). Either:
  - (a) default to `Compact/Compact` (the strictest), or
  - (b) read `LocalConfiguration.current.screenWidthDp`/`screenHeightDp` inside the default lambda.
- Recommend (a) — Compact/Compact is the safest one-frame placeholder; the actual class is provided by MainActivity on the very next composition.

## New tests

### `app/src/test/java/com/authorss81/noteflow/Phase251WindowSizeClassRefreshTest.kt` (pure JVM, 3+ tests)
- `MainActivity.kt` source pin: the `key(sizeClassRefreshKey) { calculateWindowSizeClass(...) }` is preceded by a `LaunchedEffect(LocalConfiguration.current) { sizeClassRefreshKey++ }` (or equivalent) so a config change bumps the key.
- `WindowSizeClassProvider.kt` source pin: the default `WindowSizeClass` is `Compact/Compact` (or reads from `LocalConfiguration`), not a hardcoded `840x900` EXPANDED.
- `Phase238AdaptiveLayoutTest` continues to pass (the 15 threshold tests).
- A new simulation test: a `LocalConfiguration` value change in a test rule (using `ComposeContentTestRule` — not used here, so this is optional) — or a static-only pin test as above.

## Constraints
- No schema change
- No new dependencies
- No `.github/workflows/` edits
- The `LaunchedEffect(LocalConfiguration.current)` MUST also fire on first composition (otherwise the default-Compact is sticky until the first config change). The effect's body runs on initial composition as part of the standard `LaunchedEffect` contract — verify.
- `WindowSizeClassProvider` default change is a one-frame visual flash difference; document that the first frame may briefly show the Compact layout before the real class is provided.
- `verification-metadata.xml` untouched

## DoD
- `gradle :app:testDebugUnitTest` 3556+ green
- `gradle :app:assembleDebug` + `assembleRelease` green
- `gradle :app:lintDebug` 0 errors
- Manual: open the app in a freeform window, drag the window from Compact width to Expanded width — the layout transitions from single-pane to double-pane WITHOUT leaving and reopening the app
- Manual: drag back from Expanded to Compact — the layout returns to single-pane
- Manual: orientation change still triggers the re-derive (no regression)
- `workspace/phase-251/REPORT.md` with file:line evidence
