# Phase 175 — Step 0 Inventory (R2-KS-21 MEDIUM)

Date: 2026-08-20. Baseline captured before ANY code change.

## Baseline build state
- `gradle assembleDebug` → GREEN (exit 0).
- `gradle testDebugUnitTest` → 2398 tests completed, 1 failed:
  `Phase148UiFailureTextScrubTest` (pre-existing UNC-path failure, untouched — reproduced on
  a clean stash in prior phases).
- Baseline APK: `app/build/outputs/apk/debug/app-debug.apk`, 128,807,843 bytes.

## Current ML Kit wiring (file:line evidence)
- ML Kit deps in the BASE app (the source of the payload):
  - `app/build.gradle.kts:263` `implementation(libs.mlkit.text.recognition)`
    → drags `text-recognition:16.0.1` + `text-recognition-bundled-common:17.0.0`
    (native `libmlkit_google_ocr_pipeline.so` + bundled OCR model assets).
  - `app/build.gradle.kts:267` `implementation(libs.mlkit.translate)`
    → drags `translate:17.0.3` (native `libtranslate_jni.so` +
    `res/raw/translate_models_metadata.json` + `res/xml/rapid_response_client_defaults.xml`).
  - Transitives also present in the base APK's classpath: `com.google.mlkit:{common:18.11.0,
    vision-common:17.3.0, vision-interfaces:16.3.0}` + `com.google.android.gms:{
    play-services-mlkit-text-recognition:19.0.1, play-services-mlkit-text-recognition-common:19.1.0,
    play-services-tasks:18.2.0, play-services-base:18.5.0, play-services-basement:18.4.0}`.
- Version catalog entries (already present, kept for the new module):
  - `gradle/libs.versions.toml:18` `mlkitTextRecognition = "16.0.1"`
  - `gradle/libs.versions.toml:23` `mlkitTranslate = "17.0.3"`
- Engine (platform) sources living in the BASE app (must MOVE to the plugin module):
  - `app/src/main/kotlin/com/authorss81/noteflow/plugins/ocr/MlKitOcrEngine.kt`
    (TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) + Tasks API + suspendCancellableCoroutine)
  - `app/src/main/kotlin/com/authorss81/noteflow/plugins/ocr/PlatformOcrEngines.kt`
    (createMlKit factory)
  - `app/src/main/kotlin/com/authorss81/noteflow/plugins/translation/MlKitTranslatorEngine.kt`
    (RemoteModelManager + TranslateRemoteModel + downloadModelIfNeeded)
  - `app/src/main/kotlin/com/authorss81/noteflow/plugins/translation/PlatformTranslationEngines.kt`
    (create factory)
- Pure-JVM wrappers (unit-tested, currently app-side):
  - `plugins/ocr/OnDeviceOcrPlugin.kt` — manifest id
    `com.authorss81.noteflow.plugins.ocr`, ctor `engine: OcrEngine? = null`, lazily creates
    the bundled ML Kit engine via `PlatformOcrEngines.createMlKit` (lines 92-97).
  - `plugins/ocr/OcrEngine.kt` (interface), `OcrInputValidator.kt`, `OcrTextFormatter.kt`,
    `OcrErrorMapper.kt`.
  - `plugins/translation/OnDeviceTranslationPlugin.kt` — manifest id
    `com.authorss81.noteflow.plugins.translation`, ctor `engine: TranslatorEngine? = null`,
    lazily creates via `PlatformTranslationEngines.create` (lines 88-91).
  - `plugins/translation/TranslatorEngine.kt` (interface), `TranslationCatalog.kt`
    (pure-JVM `TARGETS`, 28 languages + source detection).
- Registry: `app/src/main/kotlin/com/authorss81/noteflow/plugins/PluginRegistry.kt`
  - imports + constructs both plugins in `defaultPlugins()`:
    - line 13 import `plugins.ocr.OnDeviceOcrPlugin`, used at line 858 `OnDeviceOcrPlugin()`
    - line 19 import `plugins.translation.OnDeviceTranslationPlugin`, used at line 868
      `OnDeviceTranslationPlugin()`
- UI / routing call sites (stay as capability-facade calls):
  - `ui/viewmodel/NoteflowViewModel.kt` — OCR route (`extractTextFromImage`, ~line 793,
    `withPluginAsync(PluginCapability.OCR)`), translation routes (~lines 1064-1087, 1190, 1232).
  - `ui/screens/EditorScreen.kt` — OCR availability/dialog (`availablePlugins(PluginCapability.OCR, context)`,
    lines 229/235).
  - `ui/screens/MarkdownPreviewScreen.kt` — translation dialog + menu.
- Tests that build the app-side plugins directly (must move/adapt with the plugin classes):
  - `app/src/test/java/com/authorss81/noteflow/TranslationPluginTest.kt`
    (`OnDeviceTranslationPlugin(fake)`).
  - `app/src/test/java/com/authorss81/noteflow/OcrPluginWrapperTest.kt`
    (`OnDeviceOcrPlugin(FakeOcrEngine)` etc.).
  - `app/src/test/java/com/authorss81/noteflow/PluginExecutionSimulationTest.kt`
    (`OnDeviceOcrPlugin(FakeOcrEngine(...))`).

## Baseline APK payload inventory (R2-KS-21) — BEFORE
`lib/<abi>/libmlkit_google_ocr_pipeline.so` (4 ABIs):
- arm64-v8a 11,064,544 · armeabi-v7a 6,781,940 · x86 11,561,048 · x86_64 11,626,128
  (total 41,033,660)
`lib/<abi>/libtranslate_jni.so` (4 ABIs):
- arm64-v8a 16,361,048 · armeabi-v7a 11,608,808 · x86 17,258,012 · x86_64 17,371,632
  (total 62,599,500)
`assets/mlkit-google-ocr-models/**` — bundled Latin OCR model pack (gocr/taser/aksara), ~1.6 MB.
`res/raw/translate_models_metadata.json` — 16,710 bytes.
`res/xml/rapid_response_client_defaults.xml` — 32,048 bytes.

Sum of the five R2-KS-21 payload groups present in the base APK ≈ 105.3 MB.

## Removed/stale items verified
- No other base-app code references `com.google.android.gms` (only the two MlKit engines,
  which move; base app then has zero `com.google.*` classes).
- `settings.gradle.kts` central allow-list already permits `com.google.*` (google()
  `includeGroupByRegex("com\\.google.*")`), so the new `:plugins:mlkit` module's deps stay
  inside the pinned allow-list (Phase146BuildIntegrityTest / B2Deps03 min all-module scans).

## App-side plugin classes removed from the base APK classpath after moving (Step 2)
The two app-side plugin classes + engines above are deleted from `app/`; OCR + Translation
capabilities then resolve through the plugin route (NO_PLUGIN_INSTALLED until the
downloadable `:plugins:mlkit` artifact is installed from the Plugin Store), exactly like the
already-established LLM pattern.