# Phase 233 — Paparazzi tablet + phone golden regression tests for fixed scrollable screens

## Goal
Add Paparazzi golden-screenshot tests covering the fixed scrollable screens on **both** tablet and phone configs, proving **zero mobile regression** while tablets render correctly (no nested-scroll crash).

## Context (from phase-229 research)
The screens with scrollable containers are: `EditorScreen` (ColorPickerBottomSheet), `InteractiveTutorial`, `TutorialDemos`, `MarkdownPreviewScreen` (reader/split panes), `HomeScreen` sidebar panels, and the various dialogs/bottom sheets.

Paparazzi is already wired (see `app/src/test/java/com/authorss81/noteflow/paparazzi/PaparazziSmokeTest.kt`). It renders Compose on the JVM via layoutlib. AGSL/compose-native-renderer screens must be excluded (PaparazziSmokeTest notes "AGSL-free composables" to keep JVM screenshot renderer working).

## Implementation

### 1. Create `Phase233ScrollableGoldenTest`
- **Tablet golden:** render `InteractiveTutorial` (with its demo panels) and `TutorialDemos` at a `DeviceConfig(screenWidth=2560, screenHeight=1600, density=XHIGH, size=LARGE)` tablet config. These are AGSL-free panels.
- **Phone golden:** render the SAME composables at the phone config (`360×800dp @xxhdpi` like the smoke test).
- Snapshot names: `tutorial_tablet_*`, `tutorial_phone_*`.
- Assert both render without throwing (no nested-scroll crash) and produce distinct-but-valid golden images.

### 2. Mobile-regression parity assertion
- Assert the Paparazzi phone `verticalScroll` count / layout matches the pre-fix intent: i.e., both phone + tablet goldens are captured, and the phone golden is unchanged relative to a baseline (the guard that the modifier reorder didn't change phone layout).
- Where a real full screen (EditorScreen/HomeScreen) is too heavy for layoutlib (state, viewmodel, native deps), extract the bounded panel composables OR capture a lighter representative (e.g., `NotebookPanel`, `TemplateLibraryDialog`) and add an explicit `@Preview(device = Devices.PHONE, uiMode=UI_MODE_NIGHT_YES)` + tablet preview.

### 3. Content-scope note
If a screen requires Android runtime (Bitmap/renderer), it is NOT Paparazzi-safe — cover those with the source-scan test from phase-232 instead. Focus golden tests on pure-composable panels.

## Verification
- `gradle :app:testDebugUnitTest` green (Paparazzi goldens are JVM unit tests)
- Generated golden images exist under `app/src/test/snapshots` / Paparazzi output
- `gradle assembleDebug` green (1 pre-existing UNC-path test failure untouched — Paparazzi goldens must NOT break other tests)

## DoD
- `Phase233ScrollableGoldenTest` created with tablet + phone configs
- Cellphone and tablet goldens render for at least InteractiveTutorial + TutorialDemos (+ any bounded pure-composable panel)
- Guards drawn: no INTENTIONAL error golden (goldens encode correct layouts)
- `gradle testDebugUnitTest` green
- `workspace/phase-233/REPORT.md` written
- Update docs

## Timeout
180 minutes
