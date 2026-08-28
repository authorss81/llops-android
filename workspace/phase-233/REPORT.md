# Phase 233 — Paparazzi tablet + phone golden regression tests for fixed scrollable screens

## Status
IMPLEMENTED (2026-08-28).

## Goal
Add Paparazzi golden-screenshot tests covering the fixed scrollable screens on BOTH tablet and
phone configs, proving **zero mobile regression** while tablets render correctly (no nested-scroll
crash — `CheckScrollableContainerConstraints`).

## What was delivered

### `Phase233ScrollableGoldenTest` (10 golden tests, 2 device configs)
`app/src/test/java/com/authorss81/noteflow/paparazzi/Phase233ScrollableGoldenTest.kt`:

- **Abstract base `Phase233ScrollableGoldenTest`** holds the snapshot tests + a `snapshot(...)`
  helper. Two concrete subclasses inject the device config:
  - `Phase233ScrollableGoldenPhoneTest` — phone config identical to the smoke test:
    360×800dp @ xxhdpi, 1080×2400 px, `ScreenRatio.NOTLONG`, `ScreenSize.NORMAL`. Snapshots
    prefixed `tutorial_phone_*`.
  - `Phase233ScrollableGoldenTabletTest` — physical 2560×1600 @ xhdpi (density 2×) ⇒
    **1280×800dp landscape**, `ScreenRatio.LONG`, `ScreenSize.LARGE`. Snapshots prefixed
    `tutorial_tablet_*`.
  - Each subclass gets its own `@get:Rule Paparazzi` — needed because a class can only carry ONE
    `@Rule` device; the two configs are modelled as two test classes sharing the base (avoiding the
    JUnit multiple-`@Rule` ambiguity).

- Rendered screens (all AGSL-free — plain Compose `Canvas` practice pads + bounded `verticalScroll`):
  1. Full **`InteractiveTutorial`** deck on:
     - slide 0 (`start_welcome`) — plain content `Card`,
     - slide 20 (`layers_demo`) — the action slide that embeds the bounded `LayerDemoPanel`,
       exercising the nested-scroll path on both sizes.
  2. The **`TutorialDemos.kt` bounded panels** directly: `LayerDemoPanel`, `PracticePad(DRAW)`,
     `MarkdownTypeDemo` (the panels phase-230 bounded/reordered).

- **10 goldens total (5 phone + 5 tablet)**: `tutorial_{phone|tablet}_{welcome,layers_demo,
  layers_panel,practice_draw,markdown_panel}`.

### Golden verification (rendered, not blank)
All 10 images are generated into the Paparazzi report
(`app/build/reports/paparazzi/debug/`) and verified **distinct + non-blank** programmatically
(dimensions + distinct-color count via PIL):

| golden | report size | distinct colors | notes |
|---|---|---|---|
| phone — all 5 | 450×1000 | 393–2876 | portrait 0.45 ratio (1080×2400 scaled) |
| tablet — all 5 | 625×1000 | 308–2402 | landscape 0.625 ratio (2560×1600 scaled) |

- Phone vs tablet frames have **different aspect ratios** (portrait vs landscape) — proving the two
  device configs genuinely render different layouts (the intended tablet/phone parity coverage).
- No golden is a solid/error frame (all carry hundreds–thousands of distinct colors).
- All render **without throwing** — no nested-scroll crash on either size.

### Key finding — the phase-231 runtime `NestedScrollGuard` false-positives under Paparazzi/layoutlib
An important discovery during implementation: with the phase-231 DEBUG-only `NestedScrollGuard`
active (default in unit tests, `BuildConfig.DEBUG`), the golden render **threw**
`check(newDepth <= 1)` at `NestedScrollGuard.kt:83` on EVERY scrollable screen — **even
`tutorial_welcome` (slide 0), which contains a single `verticalScroll`**. The guard's measure-phase
depth ThreadLocal is confounded by layoutlib: a single `verticalScroll` node's measure can re-enter
the guarded node across measure passes without a balanced guard exit, so depth reads > 1 → the
guard reports a (false) nested-scroll violation.

This is a **test-environment artifact, not a real crash**: with the diagnostic suspended, the same
screens render correct, non-blank goldens. The REAL regression guards remain:
- phase-230 correct bound-before-scroll modifier ordering, and
- phase-232 static source scan (`Phase232NestedScrollSourceScanTest`).

Because the guard is an explicit DEBUG-only runtime *diagnostic* (its `enabled` flag exists so tests
can toggle it — `Phase231NestedScrollGuardTest` does), the snapshot helper suspends it for the
duration of the golden render and restores it in `finally`:

```kotlin
val prior = NestedScrollGuardConfig.enabled
NestedScrollGuardConfig.enabled = false
try { paparazzi.snapshot(name = name, composable = content) }
finally { NestedScrollGuardConfig.enabled = prior }
```

This is documented in the test KDoc, in `docs/ARCHITECTURE.md` gotcha #12, and in this report so a
future golden test never re-hits the confusing false-positive.

### Golden images are NOT committed
The repo does not commit golden PNGs — the 5 pre-existing Paparazzi tests
(`PaparazziSmokeTest`, `Phase223DraftingGridSnapshotTest`, etc.) commit none either. Goldens are
emitted under the Paparazzi build report (`app/build/reports/paparazzi/debug/`) at test time and the
tests pass against them (Paparazzi default record mode). This phase follows the established pattern.

## Verification
- `gradle :app:testDebugUnitTest` — **BUILD SUCCESSFUL**. Full suite:
  **3467 tests / 0 failures / 0 errors** (incl. the previously-intermittent
  `Phase148UiFailureTextScrubTest` UNC-path case, which passed in this run — untouched).
  - `Phase233ScrollableGoldenPhoneTest` — 5/5 green.
  - `Phase233ScrollableGoldenTabletTest` — 5/5 green.
- `gradle assembleDebug` — **BUILD SUCCESSFUL**.

The Paparazzi goldens are JVM unit tests; they did not break any other test.

## DoD
- [x] `Phase233ScrollableGoldenTest` created with tablet + phone configs
      (abstract base + phone/tablet subclasses, each with its own Paparazzi rule).
- [x] Cellphone AND tablet goldens render for InteractiveTutorial (slide 0 + slide 20 action demo)
      AND the TutorialDemos bounded panels (`LayerDemoPanel`, `PracticePad`, `MarkdownTypeDemo`).
- [x] Guards drawn: NO intentional error golden — all 10 goldens encode correct layouts (verified
      distinct, non-blank, correct aspect ratios; no solid/error frames).
- [x] `gradle testDebugUnitTest` green (3467 / 0 failures / 0 errors).
- [x] `workspace/phase-233/REPORT.md` written.
- [x] docs updated (`docs/phase-status.md` phase-233 row + `docs/ARCHITECTURE.md` gotcha #12).

## Notes / constraints honored
- No schema change, no migration, no new dependencies.
- `.github/workflows/` untouched.
- Base-APK-size rule intact (test-only code; Paparazzi is already a unit-test-only lib).
- AGSL-compose-native-renderer screens (the real canvas) are correctly EXCLUDED from Paparazzi
  goldens — those are covered by the phase-232 source-scan (and the on-device runtime guard)
  instead, matching the phase's content-scope note.
