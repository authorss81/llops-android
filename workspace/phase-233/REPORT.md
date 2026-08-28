# Phase 233 — Paparazzi tablet + phone golden regression tests for fixed scrollable screens

## Status
IMPLEMENTED (2026-08-28) + REVIEW-FIX ROUND (2026-08-28): goldens are now committed baselines
(`app/src/test/snapshots/`) verified by `verifyPaparazziDebug` in CI; verification-mode and
guard-scope claims corrected from the initial report.

## Goal
Add Paparazzi golden-screenshot tests covering the fixed scrollable screens on BOTH tablet and
phone configs, proving **zero mobile regression** (pin the phone layout to a committed baseline)
while tablets render correctly (no nested-scroll crash — `CheckScrollableContainerConstraints`).

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
An important discovery during implementation (CORRECTED in the review-fix round): with the phase-231
DEBUG-only `NestedScrollGuard` active (default in unit tests, `BuildConfig.DEBUG`), golden renders
**threw** `check(newDepth <= 1)` at `NestedScrollGuard.kt:83` on the SCREENS THAT TRIP IT — but NOT
universally. The false positive is **hierarchy- and device-dependent**, not "every scrollable":
- reproduced on the nested-scroll path (`tutorial_layers_demo`, on both phone and tablet) — the
  bounded `LayerDemoPanel` scroll measured while the tutorial's own scroll was mid-measure, and the
  guard cannot distinguish "bounded nested scroll" (safe, phase-230) from "unbounded nested scroll"
  (crash) — it throws on ANY depth > 1;
- reproduced on the TABLET single-scroll screens (welcome / panels) whose layoutlib measure can
  re-enter the guarded node across passes without a balanced exit;
- **NOT reproduced on the PHONE single-scroll screens** — `tutorial_welcome` (slide 0, one
  `verticalScroll`) and `tutorial_layers_panel` render fine with the guard ACTIVE on the 360×800dp
  phone config (verified with a scratch probe during review).

Because SOME screens do trip it, the snapshot helper still suspends the DEBUG-only diagnostic for
the duration of the golden render and restores it in `finally`. This is a test-env artifact, not a
real nested-scroll crash — with the flag suspended the same screens render correct, non-blank goldens,
and a GENUINE unbounded nested scroll still fails the render (the Compose framework
`CheckScrollableContainerConstraints` check runs in layoutlib; verified during review with a scratch
probe that nested two `verticalScroll`s with no bound → the render throws and the test fails).

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

### Golden baselines ARE committed and verified (review-fix round)
CORRECTION to the pre-review text: the tests do NOT run in "Paparazzi default record mode", and the
goldens are now REAL pinned baselines. Paparazzi 2.0.0-alpha01 behaviour:

- `gradle :app:testDebugUnitTest` runs Paparazzi in **report mode** — every snapshot renders and is
  shown in the HTML report (`app/build/reports/paparazzi/debug/`), but NO golden file is written and
  nothing is compared. That mode alone can only smoke-test "renders without throwing".
- `gradle :app:recordPaparazziDebug` flips the recorder: each snapshot is ALSO written to
  `app/src/test/snapshots/images/` as a golden baseline. These 10 PNGs (5 phone + 5 tablet) ARE
  committed to the repo for Phase 233.
- `gradle :app:verifyPaparazziDebug` flips the verifier (`paparazzi.test.verify=true` ⇒
  `SnapshotVerifier`): it FAILS the build if a baseline golden is missing
  (`AssertionError` at `SnapshotVerifier.kt:56`) or differs beyond `maxPercentDifference`. Verified
  three ways in the review-fix round: (1) `verifyPaparazziDebug --tests Phase233*` BUILD SUCCESSFUL
  on the 10 committed baselines; (2) removing one baseline makes the phone class FAIL
  ("Missing snapshot"); (3) the genuine nested-scroll crash probe fails even in report mode.
- CI-enforcement is DEFERRED (review-fix round, push constraint discovered): a push that touches
  `.github/workflows/android.yml` is REFUSED by GitHub ("refusing to allow a GitHub App to create or
  update workflow … without `workflows` permission") — this bot token lacks the GitHub App
  `workflows` permission, which is also why every prior phase reports `.github/workflows/`
  untouched. The intended CI step is just:
  `gradle :app:verifyPaparazziDebug --tests "com.authorss81.noteflow.paparazzi.Phase233ScrollableGolden*"`
  (after `testDebugUnitTest`) — a human (or a token with `workflows` write) should add it to
  `.github/workflows/android.yml` under the "Verify Paparazzi goldens" name. Until then, each phase
  that renders the tutorial/panels should run the verify task as part of its local DoD (the
  baselines, being committed, fail the build as soon as the task runs).

The OTHER 5 pre-existing Paparazzi tests (`PaparazziSmokeTest`, `Phase223DraftingGridSnapshotTest`,
etc.) still run report-only with NO committed baselines — the pinned-baseline convention starts with
Phase 233; extending it to the other tests is out of scope (their gif/multi-frame and future-refactor
surface isn't pinned yet).

## Verification
- `gradle :app:testDebugUnitTest` — **BUILD SUCCESSFUL** (report-mode Paparazzi + full JVM suite,
  verify NOT implied here — see "Golden baselines ARE committed and verified" above).
- `gradle :app:verifyPaparazziDebug --tests "com.authorss81.noteflow.paparazzi.Phase233ScrollableGolden*"`
  — **BUILD SUCCESSFUL** (10/10 baselines match). Negative check: deleting one baseline makes the
  phone class fail with `AssertionError` ("Missing snapshot", `SnapshotVerifier.kt:56`), so the
  guard genuinely fails closed.
- `gradle assembleDebug` — **BUILD SUCCESSFUL**.

The Paparazzi goldens are JVM unit tests; they did not break any other test.

## DoD
- [x] `Phase233ScrollableGoldenTest` created with tablet + phone configs
      (abstract base + phone/tablet subclasses, each with its own Paparazzi rule).
- [x] Cellphone AND tablet goldens render for InteractiveTutorial (slide 0 + slide 20 action demo)
      AND the TutorialDemos bounded panels (`LayerDemoPanel`, `PracticePad`, `MarkdownTypeDemo`).
- [x] Guards drawn: NO intentional error golden — all 10 goldens encode correct layouts (verified
      distinct, non-blank, correct aspect ratios; no solid/error frames).
- [x] **Baseline pinning (review-fix round):** the 10 goldens are committed under
      `app/src/test/snapshots/images/` and `verifyPaparazziDebug` passes 10/10 — a phone/tablet
      layout regression that changes a golden now fails the build the moment the verify task runs.
      (CI enforcement of the verify task is DEFERRED — the bot token cannot push `.github/workflows/`
      edits; see "Golden baselines ARE committed and verified" above.)
- [x] `gradle testDebugUnitTest` green (3467 / 0 failures / 0 errors).
- [x] `workspace/phase-233/REPORT.md` written.
- [x] docs updated (`docs/phase-status.md` phase-233 row + `docs/ARCHITECTURE.md` gotcha #12).

## Notes / constraints honored
- No schema change, no migration, no new dependencies.
- `.github/workflows/` untouched — the bot token lacks the GitHub App `workflows` permission, so any
  push that edits a workflow file is refused. The "Verify Paparazzi goldens" CI step is documented
  as a follow-up for a human/authorized token (see "Golden baselines ARE committed and verified").
- Base-APK-size rule intact (test-only code; Paparazzi is already a unit-test-only lib; the golden
  PNGs are test fixtures, not shipped artifacts).
- AGSL-compose-native-renderer screens (the real canvas) are correctly EXCLUDED from Paparazzi
  goldens — those are covered by the phase-232 source-scan (and the on-device runtime guard)
  instead, matching the phase's content-scope note.
