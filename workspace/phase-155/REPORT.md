# phase-155 — REVIEW REPORT

Phase 155 (canvas & brush workshop) was reviewed, and the review findings were
fixed. This report records the review, the fixes, and the verification evidence.

## What phase-155 shipped (original commit `7bf9723`)

- Two-finger undo/redo + two-finger double-tap classifier (`GestureRedoUndoClassifier.kt`)
- Quick-color ring long-press picker (`QuickColorRingMath.kt`)
- `.inkbrush` brush-preset import/export (`BrushPresetFileCodec.kt`,
  `BrushPresetImportPolicy.kt`, `ExportDestinationPolicy.kt`,
  `SettingsManager` prefs, `EditorScreen` wiring, `AnnotationCanvas` gestures)
- Marked `DONE` with a `.done` marker **without a build, without tests, without a
  report** — and the phase **did not compile** (see FINDING #1 below).

## Review findings and fixes

### FINDING #1 (CRITICAL) — the phase did not compile

- `QuickColorRingMath.kt` imported `kotlin.math.TWO_PI`, which does not exist in
  the stdlib (only `PI`). **Fix:** `private val TWO_PI = 2.0 * PI`
  (`QuickColorRingMath.kt:34`).
- `AnnotationCanvas.kt` read `LocalViewConfiguration.current` inside a non-composable
  `pointerInput` lambda and called `kotlinx.coroutines.withTimeoutOrNull(...)`
  inside the `@RestrictsSuspension` `awaitEachGesture` scope (whose receiver
  provides its own `withTimeoutOrNull` member). **Fix:** the long-press timeout is
  captured in composable scope (`quickColorRingLongPressMillis`) and the
  restricted block now uses the scope's own `withTimeoutOrNull`
  (`AnnotationCanvas.kt:789-794`).
- `GestureRedoUndoClassifier.kt` declared `const val` in a class body (only legal
  at top level / named object / companion). **Fix:** plain `val`
  (`GestureRedoUndoClassifier.kt:51-83`).
- `QuickColorRingMath.kt` mixed `Double` from `cos/sin` into a
  `Pair<Float, Float>` accumulator (`val` reassignment + type mismatch).
  **Fix:** explicit `.toFloat()` on both coordinates (`QuickColorRingMath.kt:83`).
- **Pre-existing (phase-154) break surfaced by the build:** `KnowledgeGraphScreen.kt`
  uses `launch { }` but the `kotlinx.coroutines.launch` import was dropped in
  phase-154. **Fix:** re-added the import (`KnowledgeGraphScreen.kt:65`).

### FINDING #2 (HIGH) — Feature 3 (`.inkbrush` import/export) was unimplemented / dead code

The codec, policy, classifier and math existed as pure-JVM objects but were
**unreachable from the app**: `EditorScreen` had no import launcher, no export
path, no persistence, and the dormant `ProtobufBrushLoader` was still dormant.
**Fix (all in `EditorScreen.kt`):**

- SAF `GetContent` import launcher (`brushPresetImportLauncher`) that routes
  through `AttachmentIngestPolicy.boundedReadBytes` (bounded, cap =
  `BrushPresetImportPolicy.MAX_BRUSH_FILE_BYTES` — also satisfies the
  `B2Dos05AttachmentIngestTest` source-pin).
- Decode routing: JSON bundle → `DecodeResult.Preset` (dedupe by id, apply,
  persist); raw non-JSON binary → `ProtobufBrushLoader.loadFromByteArray` (the
  dormant native loader is now genuinely reachable from production code).
- Persistence: "My presets" survive restarts via
  `SettingsManager.importedBrushPresetsJson` + `encodeList`/`decodeList`.
- Export: stage to `cacheDir`, then `SaFExporter.export(ExportKind.BRUSH_PRESET, …)`
  with `SaFExportResult` verdicts.
- UI: Import/Export buttons + "My presets" grid in `BrushPresetPickerBottomSheet`.

### FINDING #3 (HIGH) — Features 1 & 2 were unwired

- `AnnotationCanvas` never received the classifier/ring inputs. **Fix:** new
  defaulted params (`twoFingerGesturesEnabled`, `onTwoFingerUndo/Redo`,
  `quickColorRingEnabled`, `quickColorSwatches`, `onQuickColorPicked`,
  `importedBrushPresets`) + `LaunchedEffect` resolving imported presets; both
  gestures default OFF so pinch-zoom/one-finger drawing is untouched.
- `EditorScreen` has no toggles. **Fix:** two `Switch` rows in
  `CanvasSettingsBottomSheet` (Two-Finger Undo/Redo, Quick-Color Ring) with
  one-time non-alarming hint snackbars; ring seeds from DesignerPalette
  SWATCHes; quick-color pick forces `StrokeColorMode.SOLID` for multi-color
  modes.

### FINDING #4 (HIGH) — no unit tests

**Fix:** 44 new pure-JVM tests under
`app/src/test/java/com/authorss81/noteflow/`:

- `GestureRedoUndoClassifierTest` (15) — swipe left/right, below-threshold,
  pinch-out/pinch-in never fire, pinch is not a tap, vertical pan, degenerate
  start, single/double tap, interval expiry, `reset()`, swipe never pairs with a
  next tap, session age.
- `QuickColorRingMathTest` (10) — swatch budget, ring layout geometry (mid-band,
  12-o'clock start, clockwise), hit-testing (center disc, on-swatch, slop snap,
  outside, empty ring), angle normalization, selection progress.
- `BrushPresetFileCodecTest` (19) — JSON round-trip, list round-trip + garbage
  entries, **BOM + leading-whitespace JSON routing** (FINDING #7), raw-protobuf
  pass-through untouched, empty/whitespace/oversize, wrong magic, bad version,
  unknown tool, out-of-range params/size/color, name sanitization, deterministic
  ids, import policy caps + known curves + `canImport`, curated-pack integrity.

### FINDING #5 (HIGH) — no REPORT.md, falsely certified DONE

**Fix:** this report; `docs/phase-status.md` row 156 → `DONE`;
`docs/ARCHITECTURE.md` "Brush preview" note.

### FINDING #6 (MEDIUM) — docs not updated

`docs/phase-status.md` still said `NOT STARTED`. **Fix:** updated (above).

### FINDING #7 (MEDIUM) — `decode()` misrouted non-`{`/`[` bytes and BOM handling was dead

The pre-fix gate peeked only at byte 0, so a UTF-8 BOM or leading newline
misrouted otherwise-valid JSON to the raw-binary path. **Fix:**
`firstMeaningfulByteIndex` skips the 3-byte BOM then ASCII whitespace, then
`looksLikeJsonStart` decides; non-JSON still returns `RawProtobuf` with the bytes
UNTOUCHED (never pre-parses protobuf). Covered by the BOM/whitespace/binary tests.

### FINDING #8 (MEDIUM) — dead classifier state

`lastFrameTimeMs` was written/updated every frame and never read; `reset()`
missed `lastTapUpTimeMs`, `sceneTimeMs`, `startTimeMs`. **Fix:** removed the
field and all writes (plus the session-age counterpart), `reset()` now clears
every scalar.

### FINDING #9 (LOW) — misleading `.inkbrush` semantics / unused Base64 import

- `.inkbrush` was described as protobuf but the bundle is a JSON document. The
  deviation is now documented in `BrushPresetFileCodec.kt` (the Google Ink
  Tooling `.inkbrush` is `androidx.ink`-internal protobuf and NOT stable enough
  to serialize without SDK-internals; we keep the same extension and MIME for
  ecosystem compatibility and round-trip losslessly).
- Removed the unused `java.util.Base64` import; kept `Gson` + re-added
  `JsonArray` (used by `encodeList`).

### FINDING #10 (LOW) — unused Gson/Base64 seed field

The `protobufSeed` base64 field was never populated; `parsePreset` now skips it
explicitly as belonging to the Android/native path.

## Verification

- `gradle testDebugUnitTest` — **2164 tests, 2 failed:**
  - `Phase148UiFailureTextScrubTest` — pre-existing UNC-path failure, documented
    in AGENTS.md (reproduced on a clean stash, untouched by this phase).
  - `WikiLinkParserCacheUnitTest "a cancelled scan propagates cancellation"` —
    timing flake in code untouched by phase-155; **passes when run in
    isolation** (`:app:testDebugUnitTest --tests …`).
- `gradle assembleDebug` — **BUILD SUCCESSFUL** (90 tasks).
- `gradle :app:testDebugUnitTest --tests` re-runs of the three fixed/new test
  classes — green.

## Scope notes

- No DB schema change / migration; new state lives in `SharedPreferences`.
- No new base-APK dependencies (all new code is pure Kotlin); `ProtobufBrushLoader`
  was already present.
- All three features default OFF (opt-in), so existing drawing/zoom behavior is
  unchanged.
