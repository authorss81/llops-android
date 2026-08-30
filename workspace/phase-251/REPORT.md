# Phase 251 — WindowSizeClass refreshes on freeform drag-resize

**Date:** 2026-08-30
**Status:** DONE

## 1. Summary

The `WindowSizeClass` was only re-derived on `Lifecycle.Event.ON_RESUME`
(`MainActivity.kt`) — a freeform window drag-resized across a 600dp/840dp
boundary without any pause/resume left the entire `AdaptiveLayoutPolicy`
system (single- vs double-pane, NavigationRail, HomeScreen+EditorScreen split,
dock posture) stuck on the OLD size class. Two changes land in this phase:

1. **MainActivity re-derives on config changes too** — a
   `LaunchedEffect(LocalConfiguration.current) { sizeClassRefreshKey++ }` sits
   in front of the existing `key(sizeClassRefreshKey) { calculateWindowSizeClass(activity) }`
   block, so every `LocalConfiguration` invalidation (orientation, density,
   and the resize events that update configuration) bumps the key and
   re-queries `calculateWindowSizeClass(activity)` against the CURRENT window
   metrics — the single/double-column posture never stays stuck mid-drag.
   The `LaunchedEffect` contract runs its body on the FIRST composition too,
   so the new strict default is replaced on the very next frame (never sticky).
2. **Provider default is now the strictest Compact/Compact** — the old
   `WindowSizeClass.calculateFromSize(DpSize(840.dp, 900.dp))` placeholder
   (audit 3/5 M1) claimed an EXPANDED 840x900 tablet frame (a wide double-pane
   flash on phones / a lie to any probe reading the default). The default is
   now `WindowSizeClass.calculateFromSize(DpSize(0.dp, 0.dp))` → Compact/Compact.
   The real class is provided by MainActivity on the very next composition.

## 2. Changes — file:line evidence

### `app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt`

- `:85` — `import androidx.compose.ui.platform.LocalConfiguration`.
- `:283-315` — the phase-238 multi-window block. After the existing
  `DisposableEffect(Unit) { lifecycle.addObserver(...) }` (`:298-301`) that
  bumps the key on `ON_RESUME` (`:295`), the new config listener lands:
  - `:313-315`
    ```kotlin
    LaunchedEffect(LocalConfiguration.current) {
        sizeClassRefreshKey++
    }
    ```
  - comment `:303-312` documents: dragging keeps the activity resumed (no
    pause/resume), the modern resize may only update WindowMetrics without
    firing `onConfigurationChanged`, `LocalConfiguration` IS invalidated by
    those events, so the effect relaunches (also on first composition per the
    `LaunchedEffect` contract), bumps the key, and the keyed block re-queries
    the current metrics.
- `:668-669` — the re-derivation site, unchanged by this phase:
  `val windowSizeClass = key(sizeClassRefreshKey) { calculateWindowSizeClass(activity = this@MainActivity) }`.
  Re-keying on `LocalConfiguration.current` triggers a re-call of
  `calculateWindowSizeClass(activity)`, which queries
  `WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity)`
  — per the Android docs this tracks real freeform resizes.

### `app/src/main/kotlin/com/authorss81/noteflow/ui/WindowSizeClassProvider.kt`

- `:18-25` — KDoc rewritten: the neutral default is the STRICTEST class; the
  placeholder at most lasts one frame before MainActivity provides the real
  class (which is now also re-derived on every config change, phase 251).
- `:29` — the default expression:
  `WindowSizeClass.calculateFromSize(DpSize(0.dp, 0.dp))` (width 0 < 600dp ⇒
  Compact, height 0 < 480dp ⇒ Compact). The old `840.dp` EXPANDED placeholder
  is gone (grep: zero `840.dp` tokens remain in the file).

## 3. New tests — `app/src/test/java/com/authorss81/noteflow/Phase251WindowSizeClassRefreshTest.kt`

5 tests (pure JVM; 3 source pins + 1 hybrid semantic/source pin + 1 pure-JVM
library-semantic test):

1. **`WindowSizeClass re-derives on LocalConfiguration change, not just ON_RESUME`**
   — pins `LaunchedEffect(LocalConfiguration.current)` exists BEFORE
   `key(sizeClassRefreshKey) {` and that the effect body bumps
   `sizeClassRefreshKey` (the SAME key the block is keyed on).
2. **`the keyed block still re-derives via calculateWindowSizeClass(activity)`**
   — pins `val windowSizeClass = key(sizeClassRefreshKey) {` +
   `calculateWindowSizeClass(activity = this@MainActivity)` survive (the
   re-derivation route is intact after the change).
3. **`config listener is keyed on LocalConfiguration and bumps the key in-body`**
   — pins the EXACT expression `LaunchedEffect(LocalConfiguration.current) {`
   with the increment in-body, and asserts it is NOT re-keyed onto `Unit`
   (a Unit key would only fire at boot and abandon the drag fix). Documents
   that the body runs on first composition per the standard `LaunchedEffect`
   contract, so the default-Compact placeholder is replaced next frame.
4. **`provider default is the strictest Compact placeholder, never 840x900`**
   — source pin: `calculateFromSize(DpSize(0.dp, 0.dp))` present and `840.dp`
   absent.
5. **`the provider default expression actually classifies as Compact by both axes`**
   — pure-JVM behavior test against the REAL library: `calculateFromSize(DpSize(0.dp, 0.dp))`
   ⇒ Compact/Compact; the old `840x900` ⇒ Expanded/Expanded (the old lie); and
   599dp sanity-check stays under the 600dp Compact floor.

**`Phase238AdaptiveLayoutTest`** (the 15 threshold tests) continues to pass
unchanged — no `AdaptiveLayoutPolicy` logic was touched.

## 4. DoD verification

| DoD item | Result |
|---|---|
| `gradle :app:testDebugUnitTest` **3556+ green** | **3626 / 0 failures / 0 errors** (phase-250 baseline 3621 + 5 new) |
| `gradle :app:assembleDebug` green | green |
| `gradle :app:assembleRelease` green (R8+signed) | green |
| `gradle :app:lintDebug` 0 errors | 0 errors (106 pre-existing warnings) |
| Manual freeform drag Compact→Expanded → single→double-pane without restart | needs a device/emulator (not runnable on CI); mechanism per Android docs + pinned source |
| Manual drag back Expanded→Compact | needs a device (above) |
| Manual orientation change still re-derives | needs a device; `LocalConfiguration` also fires on orientation |
| `workspace/phase-251/REPORT.md` with file:line evidence | this file |

Notes:
- R8 on this low-RAM runner OOM'd at the default `-Xmx2048m` daemon heap; the
  release build was run with a command-line `-Dorg.gradle.jvmargs="-Xmx5120m ..."`
  override (no repo file change — the repo `gradle.properties` still says
  `-Xmx2048m`).
- Manual DoD items require a device/emulator (freeform drag events) and are
  documented for the human verifier. The code mechanism matches the prompt's
  specified approach exactly.
- `verification-metadata.xml` untouched; `.github/workflows/` untouched; no
  schema change; no new dependencies; base-APK-size rule intact.

## 5. Compatibility & first-frame note (constraint)

`WindowSizeClassProvider` default change is a one-frame visual difference: the
first frame may briefly show the Compact layout before the real class is
provided. That is the documented intent of choosing the strictest placeholder;
before this phase a pre-provider read would have shown a false EXPANDED/EXPANDED
wide-tablet layout (audit 3/5 M1).
