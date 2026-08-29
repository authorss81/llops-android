# Phase 236 — Firebase Test Lab Robo Scripts (Real Device Coverage)

Ready-to-run `actions.json` Robo scripts for real-device (physical `redfin-30` =
Pixel 7 / API 30) smoke + regression coverage. No new dependency — Robo is the
`gcloud` CLI + hand-written JSON. Free tier = **5 Robo test runs/day, 10
min/test**.

These catch things local AVD runs can't: real-device crashes, drawing regressions,
and the **nested-`verticalScroll` compose crashes fixed in `c972b23` +
phase-230..234** (the Color Picker bottom-sheet in particular).

## Prereqs

```bash
# 1. App installed from this repo's target dir (gcloud uploads the APK for you):
#    Build a debug APK first.
gcloud auth login
gcloud config set project <YOUR_FIREBASE_PROJECT_ID>
# Enable Test Lab (one-time): https://console.firebase.google.com -> your project -> Test Lab
```

## Run a single script (free tier: use one per day, or few per day)

```bash
# Test 1: launch + gallery
gcloud firebase test android run \
  --app app/build/outputs/apk/debug/app-debug.apk \
  --device model=redfin,version=30 \
  --robo-script workspace/phase-236/roboscripts/01-launch-and-gallery.json \
  --timeout 10m

# Test 2: color-picker / nested-scroll regression (CRITICAL for c972b23)
gcloud firebase test android run \
  --app app/build/outputs/apk/debug/app-debug.apk \
  --device model=redfin,version=30 \
  --robo-script workspace/phase-236/roboscripts/02-color-picker-tablet-regression.json \
  --timeout 10m

# Test 3: canvas tools walk
gcloud firebase test android run \
  --app app/build/outputs/apk/debug/app-debug.apk \
  --device model=redfin,version=30 \
  --robo-script workspace/phase-236/roboscripts/03-canvas-tools-walk.json \
  --timeout 10m

# Test 4: markdown edit + wiki-link
gcloud firebase test android run \
  --app app/build/outputs/apk/debug/app-debug.apk \
  --device model=redfin,version=30 \
  --robo-script workspace/phase-236/roboscripts/04-markdown-edit-wiki-link.json \
  --timeout 10m

# Test 5: plugin store + settings
gcloud firebase test android run \
  --app app/build/outputs/apk/debug/app-debug.apk \
  --device model=redfin,version=30 \
  --robo-script workspace/phase-236/roboscripts/05-plugin-store-and-settings.json \
  --timeout 10m
```

## Run all five on a device matrix

```bash
for s in workspace/phase-236/roboscripts/*.json; do
  gcloud firebase test android run \
    --app app/build/outputs/apk/debug/app-debug.apk \
    --device model=redfin,version=30 \
    --device model=panther,version=33 \
    --device model=panther,version=34 \
    --robo-script "$s" \
    --timeout 10m
done
```

> Note: the `redfin`/`panther` physical matrix consumes 3 of the 5 daily Robo
> runs per script. Watch the daily quota.

## Reading results

- `gcloud` prints a test matrix URL when each run starts.
- Open the **Test Lab** console → your matrix → the **Robo** test.
- **Screenshots / keyframes**: each `SCREENSHOT ... keyFrame:true` becomes a
  visual-regression anchor in the Robo report (marked with a camera icon).
- **Crash history**: the console lists stack traces + "steps" for any ANR/crash.

## resource-id discovery

Robo needs a stable `resourceName` (= Compose `Modifier.testTag(...)`, surfaced as
the a11y `resource-id`) or a stable text/`content-desc` on every tap target.

Discover what's exposed on a real/emulated device:

```bash
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml
```

`resource-id` values in this app come from `Modifier.testTag("<tag>")`:

| Robo `resourceName` used | Meaning | Where |
|--------------------------|---------|-------|
| `noteCard`               | Gallery card (tap opens first note) | `ui/components/GalleryView.kt` `GalleryCardItem` |
| `toolSelectorButton`     | Floating toolbar tool selector | `ui/screens/EditorScreen.kt` `InkBarPortraitBar`/`InkBarLandscapeBar` |
| `colorSwatchButton`      | Floating toolbar color swatch | `ui/screens/EditorScreen.kt` both ink bars |
| `markdownBody`           | Raw-syntax editor text field | `ui/components/markdown/HybridMarkdownEditor.kt` `RawBlockEditor` |
| `pluginStoreSearch`      | Plugin Store filter field   | `ui/components/PluginStoreDialog.kt` |

Text / `content-desc` matched by `"text"` in a tap action (tools in
`ToolPickerBottomSheet`, the ⋮ menu items in `MaintenanceMenu`).

## Adding / relaxing a tap target

If a `VIEW_CLICKED resourceName` keeps failing on a real device, `R.drawable`
ids won't help — add `Modifier.testTag("...")` to the Compose node
(non-arch, see the constraint in the phase-236 PROMPT) and rebuild:

```kotlin
Surface(onClick = ..., modifier = Modifier.testTag("myTarget"))
```

Recipes:
- **Greenfield note**: scripts assume a vault with ≥1 note. If a fresh vault
  has none, `noteCard` taps are `optional` (skipped, no fail). Seed a note via
  `adb shell am start` + the UI, or a restore, before re-running.
- Script 4's `Edit ` tap targets a block's raw-syntax edit icon; `markdownBody`
  `TEXT_INPUT` is `optional` so an empty-note path still passes.
- Script 3 tool picks use `"text": "<label>"` (e.g. `Pencil & Charcoal`,
  `Eraser`, `Real Watercolor (Wet)`) against the Tool Picker bottom sheet.

## DoD reached

- ✅ `workspace/phase-236/roboscripts/` has 5 `actions.json` files
- ✅ `workspace/phase-236/RUN.md` copy-paste `gcloud` commands (this file)
- ✅ Scripts use only stable `resourceName`/`text`/`content-desc` targets
- ✅ Optional taps so empty-vault and absent-HSV paths pass clean
- ⏳ Real-device execution is a human/CI action (free-tier quota); see
  `REPORT.md` for last-run status + crash history
