# Phase 236 — Firebase Test Lab Robo Scripts (Real Device Coverage) [OPTIONAL]

## Goal
Provide **ready-to-use `actions.json` Robo scripts** for each major feature area so anyone can run `gcloud firebase test android run --robo-script <file>` against `redfin-30` to catch real-device crashes, drawing regressions, and tablet scroll crashes on a real device matrix — **no new dependency**, only hand-written JSON.

## Context
- Robo scripts are **JSON** lists of `VIEW_CLICKED` / `TEXT_INPUT` / `SCROLL` / `WAIT` / `KEYCODE` / `CLEAR_TEXT` actions
- The `c972b23` + `phase-230..234` fixes for nested `verticalScroll` are validated locally on AVD, but **not on a real device** — Robo on `redfin-30` (Pixel 7 API 30) is the closest cheap real-device matrix
- Test Lab free tier = `5` Robo/day + `10 min/test` (`firebase.google.com/docs/test-lab`)
- `gcloud firebase test android run --robo-script <file>` is the standard CLI
- App `applicationId com.aistudio.inkflow.app.bkxjrz` from `app/build.gradle.kts:15`
- Robo can't `longPress drag` (only `VIEW_CLICKED` by resource-id and `TEXT_INPUT`) — Robo records **partial** draw tests, but `longPress + drag` for canvas strokes is limited. Draw verification via Robo is best-effort; **instrumented tests (phase-235) cover draw correctly**

## Tasks

### 1. Create `workspace/phase-236/roboscripts/` directory with 5 scripts

Each script format:
```json
{
  "resourcePackage": "com.aistudio.inkflow.app.bkxjrz",
  "actions": [
    {"eventType": "LAUNCH", "packageName": "com.aistudio.inkflow.app.bkxjrz"},
    {"eventType": "WAIT", "timeout": 2000},
    {"eventType": "VIEW_CLICKED", "resourceName": "<res-id>", "optional": true},
    {"eventType": "SCROLL", "direction": "DOWN"},
    {"eventType": "TEXT_INPUT", "text": "Hello InkFlow"},
    {"eventType": "SCREENSHOT", "keyFrame": true},
    {"eventType": "WAIT", "timeout": 1000},
    {"eventType": "KEYCODE", "keyCode": 4}
  ]
}
```

#### Script 1: `01-launch-and-gallery.json` (10 actions)
- Launch app
- Wait 2s
- `SCREENSHOT` (welcome screen)
- `SCROLL DOWN` (scroll gallery)
- `SCREENSHOT` (gallery)
- `VIEW_CLICKED` on first notebook in `GalleryView` (resource-id `R.id.noteCard` or content-desc)
- Wait 1s
- `SCREENSHOT` (EditorScreen open)
- **Keyframe**: 5 screenshots at each step for visual regression

#### Script 2: `02-color-picker-tablet-regression.json` (CRITICAL for `c972b23`)
- Launch app → tap notebook → `EditorScreen`
- Tap color swatch (pen color toolbar)
- Wait 1s
- `SCREENSHOT` (ColorPickerBottomSheet open — verifies `c972b23` fix)
- `SCROLL DOWN` (test scroll on tablet)
- `SCREENSHOT`
- Tap HSV tab
- `SCREENSHOT` (HSV panel)
- Close
- `SCREENSHOT`

#### Script 3: `03-canvas-tools-walk.json` (10 actions)
- Launch → tap notebook → EditorScreen
- `SCREENSHOT` (canvas)
- For each tool icon: `VIEW_CLICKED` on `R.id.toolPen` / `R.id.toolPencil` / `R.id.toolEraser` / `R.id.toolWatercolor` / `R.id.toolBrushStudio`
- `SCREENSHOT` between each
- Best-effort drag on canvas (Robo `swipe` may not record longPress drag for canvas stroke)

#### Script 4: `04-markdown-edit-wiki-link.json` (10 actions)
- Tap `Markdown` notebook (or open via `R.id.openMarkdown`)
- `FOCUS` `R.id.markdownBody`
- `TEXT_INPUT` "Hello [[Note"
- Wait 500ms
- `SCREENSHOT` (wiki link suggestion popup — verifies `phase-209` recent chips work)
- `TEXT_INPUT` " test"
- `SCREENSHOT`
- `KEYCODE ENTER`
- `SCREENSHOT`

#### Script 5: `05-plugin-store-and-settings.json` (10 actions)
- Navigate to Home
- Tap ⋮ menu → tap "Plugin Store"
- `SCREENSHOT` (PluginStoreDialog)
- Tap search field → `TEXT_INPUT` "ocr"
- `SCREENSHOT` (filter)
- Close
- Tap ⋮ → "Settings" → `SCREENSHOT`

### 2. Create `workspace/phase-236/RUN.md` with copy-paste commands

```bash
# Free Test Lab: 5 Robo/day, run from main directory

# Test 1: launch + gallery
gcloud firebase test android run \
  --app app/build/outputs/apk/debug/app-debug.apk \
  --device model=redfin,version=30 \
  --robo-script workspace/phase-236/roboscripts/01-launch-and-gallery.json \
  --timeout 10m

# Test 2: color-picker tablet regression
gcloud firebase test android run \
  --app app/build/outputs/apk/debug/app-debug.apk \
  --device model=redfin,version=30 \
  --robo-script workspace/phase-236/roboscripts/02-color-picker-tablet-regression.json \
  --timeout 10m

# Tests 3-5: same pattern

# Or run all 5 with --device matrix
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

### 3. Document resource-id discovery
- Robo needs **stable `resource-id`** on tap targets. Run `adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml` to see what `resource-id`s exist
- For Compose, add `Modifier.semantics { testTag = "colorPickerButton" }` to tap targets so Robo can find them
- OR use `content-desc` strings: `Modifier.semantics { contentDescription = "Color picker" }` — Robo matches by text/description

### 4. Verify on real device
- For each script, run `gcloud firebase test android run` once with `--robo-script` + `--device model=redfin,version=30`
- Check the `Test Lab` console for crash logs and screenshot keyframes
- Fix any `VIEW_CLICKED resourceName` that fails to find target (Compose `Modifier.semantics { testTag = "..." }`)
- Commit Robo scripts + `RUN.md` to repo

## Constraints
- **No new dependency** (Robo is `gcloud` CLI + `actions.json` JSON files)
- **No `.github/workflows/` edits** (no CI changes; Robo runs locally or via `gcloud`)
- **No schema changes** (pure JSON + optional Compose `testTag` modifier additions are non-arch)
- **5 Robo tests fit free tier** (`5/day` quota)
- App must have stable `resource-id` or `content-desc` on each Robo tap target — if not, add `Modifier.semantics { testTag = "..." }` to 10-15 key buttons

## DoD
- `workspace/phase-236/roboscripts/` has 5 `actions.json` files
- `workspace/phase-236/RUN.md` has copy-paste `gcloud` commands
- All 5 scripts run successfully on `gcloud firebase test android run --device model=redfin,version=30` — no crash, keyframes captured
- `workspace/phase-236/REPORT.md` with test results + crash history
- No dependency added, no workflow edited, no schema changed

## Timeout
120 minutes (5 JSON files + verification on real device)
