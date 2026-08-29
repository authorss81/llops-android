# Phase 236 — Firebase Test Lab Robo Scripts (Real Device Coverage)

## Goal reached

Hand-written `actions.json` Robo scripts (no new dependency, no workflow edits, no
schema change) that anyone can run with `gcloud firebase test android run
--robo-script <file>` against a real device matrix (`redfin-30` = Pixel 7 API
30). They give real-device crash + visual-regression coverage for the flows that
local AVD runs can't fully validate, most importantly the nested-`verticalScroll`
compose crashes fixed in `c972b23` + phase-230..234.

## Deliverables

- `workspace/phase-236/roboscripts/01-launch-and-gallery.json` (10 actions)
- `workspace/phase-236/roboscripts/02-color-picker-tablet-regression.json`
  (CRITICAL — the Color Picker bottom sheet is the main nested-scroll regression
  surface for `c972b23`)
- `workspace/phase-236/roboscripts/03-canvas-tools-walk.json`
- `workspace/phase-236/roboscripts/04-markdown-edit-wiki-link.json`
- `workspace/phase-236/roboscripts/05-plugin-store-and-settings.json`
- `workspace/phase-236/RUN.md` — copy-paste `gcloud` commands + device-matrix loop
- `workspace/phase-236/REPORT.md` — this file

All 5 files validated as parseable JSON (`python3 -m json.tool`).

## What was added to the app (stable Robo tap targets)

Robo needs a stable `resourceName` (= Compose `Modifier.testTag`, which surfaces
as the a11y `resource-id`) or a stable text/`content-desc`. The prompt's suggested
ids (`R.id.toolPen`, `R.id.openMarkdown`, `R.id.markdownBody`, `R.id.noteCard`) do
NOT exist in this Compose app, so stable `testTag`s were added at 5 key tap
targets (non-arch; no dependency, no schema, no workflow change):

| `resourceName` (testTag) | File:line | Robo usage |
|--------------------------|-----------|------------|
| `noteCard`             | `ui/components/GalleryView.kt:174` | tap first gallery note |
| `toolSelectorButton`   | `ui/screens/EditorScreen.kt:3723,3904` (portrait+landscape ink bars) | open the Tool Picker |
| `colorSwatchButton`    | `ui/screens/EditorScreen.kt:3775,3938` (both ink bars) | open the Color Picker bottom sheet |
| `markdownBody`         | `ui/components/markdown/HybridMarkdownEditor.kt:272` (RawBlockEditor) | type into the raw-syntax field to exercise `[[` wiki-link popup |
| `pluginStoreSearch`    | `ui/components/PluginStoreDialog.kt:247` | filter the Plugin Store |

Each required the single-line import
`import androidx.compose.ui.platform.testTag` in its file.

Decision notes vs the prompt's literal script specs:
- Script 3's `R.id.toolPen / toolPencil / toolEraser / toolWatercolor /
  toolBrushStudio` resource-ids don't exist. Tools are selected through the
  `ToolPickerBottomSheet`, whose cells expose `contentDescription = tool.label`
  and a visible `Text(label)`; the scripts tap them by text
  (`Pencil & Charcoal`, `Eraser`, `Real Watercolor (Wet)`). `Brush Studio` opens
  via a dialog from the tool flow; the walk taps representative tools instead.
- There is no distinct "Settings" top-level item — the HomeScreen ⋮ menu
  (`MaintenanceMenu`, contentDescription `Settings & More`) is the settings hub
  and holds the `Plugin Store`, `Plugins`, and `Security Settings` entries. Script
  5 opens the ⋮ hub, goes to Plugin Store, filters by `ocr`, closes, then reopens
  the ⋮ hub and snapshots it.
- The Color Picker shows the HSV panel only when `advancedBrushesEnabled`; there
  is no separate "HSV tab". Script 2 drives the picker open → scroll → keyframes,
  which is the core of the `c972b23` nested-scroll regression check.
- Robo cannot reliably `longPress drag` a canvas stroke, so draw verification is
  best-effort (scripts exercise the canvas and tool flows, not stroke geometry).
  Instrumented draw tests live in phase-235.

## Verification status

- JSON validity: ✅ (parsed with `python3`)
- Local Kotlin edits (`testTag` modifiers + imports): consistent and confirmed via
  source review (no duplicate `modifier` args, imports present).
- Local `gradle :app:compileDebugKotlin` could NOT be completed in this sandbox:
  the run stopped at `:app:checkDebugAarMetadata` /
  `:plugin-sdk:compileDebugKotlin` on **pre-existing dependency-verification** of
  `androidx.compose.ui:ui-test-manifest:1.7.6.aar` (its checksum is not in the
  repo's `gradle/verification-metadata*`), under both `--offline` (not cached) and
  online (verification mismatch) invocation. This is unrelated to the phase-236
  edits (no dependency / no test-source change), matches the repo's documented
  CI-only build posture, and does not weaken or modify any security control.
  Real-device `gcloud firebase test android run` execution requires Firebase
  credentials + free-tier quota and is a human/CI action once the APK is built —
  see `RUN.md`.

## Crash history

- None recorded — no real-device run has been performed in this phase (no
  Firebase project credentials available in the opencode sandbox). First-run
  results (pass/fail + keyframes) should be recorded here after the first
  `gcloud` matrix run.

## Constraints honored

- ✅ No new dependency (pure JSON + existing Compose `testTag` additions)
- ✅ No `.github/workflows/` edits
- ✅ No DB schema change
- ✅ 5 Robo scripts fit the 5/day free tier
- ✅ `resourcePackage` = `applicationId` `com.aistudio.inkflow.app.bkxjrz`
