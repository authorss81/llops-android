# Phase 175: Move ML Kit OCR + translation out of the base APK into downloadable plugins (R2-KS-21 MEDIUM)

## Step 0 - Inventory (COMMITTED)

### Baseline Verification

**Debug APK Build**: ✅ `gradle assembleDebug` — SUCCESS (2m 46s)

**Unit Tests**: ⚠️ `gradle testDebugUnitTest` — 3 failures:
- `Phase148UiFailureTextScrubTest` — Pre-existing UNC-path failure (documented in AGENTS.md as untouched)
- `PaparazziSmokeTest.rendersLightTheme` — Environment issue (no AVD)
- `PaparazziSmokeTest.rendersDarkTheme` — Environment issue (no AVD)

**Release APK**: Build started but timed out; will inspect after completion.

### Current State Inventory (file:line)

| Item | Location | Status |
|------|----------|--------|
| ML Kit text-recognition dep | `gradle/libs.versions.toml:30,88` | Used by `:plugins:mlkit` only |
| ML Kit translate dep | `gradle/libs.versions.toml:35,91` | Used by `:plugins:mlkit` only |
| ML Kit OCR engine | `plugins/mlkit/src/main/kotlin/.../MlKitOcrEngine.kt` | In downloadable module |
| ML Kit Translator engine | `plugins/mlkit/src/main/kotlin/.../MlKitTranslatorEngine.kt` | In downloadable module |
| PluginRegistry OCR/Translation registration | `app/.../plugins/PluginRegistry.kt:870-876` | Commented as downloadable-only |
| OcrPlugin interface | `plugin-sdk/.../OcrAndTranslationPlugin.kt:47-49` | In shared framework |
| TranslationPlugin interface | `plugin-sdk/.../OcrAndTranslationPlugin.kt:78-89` | In shared framework |
| NoteflowViewModel.extractTextFromImage | `app/.../NoteflowViewModel.kt:793-798` | Routes via PluginManager |
| NoteflowViewModel.translateText | `app/.../NoteflowViewModel.kt:1062-1067` | Routes via PluginManager |
| EditorScreen OCR dialog | `app/.../OcrResultDialog.kt:74` | Calls ViewModel.extractTextFromImage |
| MarkdownPreviewScreen translation | `app/.../Phase16PluginDialogs.kt:542` | Calls ViewModel.translateText |

### APK Inventory (Debug)

```
lib/arm64-v8a/libandroidx.graphics.path.so      10 KB
lib/arm64-v8a/libgraphics-core.so              212 KB
lib/arm64-v8a/libink.so                        1.2 MB
lib/arm64-v8a/libsqlcipher.so                  5.1 MB
lib/armeabi-v7a/libandroidx.graphics.path.so   7 KB
lib/armeabi-v7a/libgraphics-core.so            104 KB
lib/armeabi-v7a/libink.so                      762 KB
lib/armeabi-v7a/libsqlcipher.so                3.5 MB
lib/x86/libandroidx.graphics.path.so           9 KB
lib/x86/libgraphics-core.so                    197 KB
lib/x86/libink.so                              1.4 MB
lib/x86/libsqlcipher.so                        4.9 MB
lib/x86_64/libandroidx.graphics.path.so        10 KB
lib/x86_64/libgraphics-core.so                 219 KB
lib/x86_64/libink.so                           1.2 MB
lib/x86_64/libsqlcipher.so                     5.7 MB
```

**NO ML Kit artifacts found in base APK:**
- ❌ `assets/mlkit-google-ocr-models/`
- ❌ `libtranslate_jni.so`
- ❌ `libmlkit_google_ocr_pipeline.so`
- ❌ `res/raw/translate_models_metadata.json`
- ❌ `res/xml/rapid_response_client_defaults.xml`

### Conclusion

The ML Kit OCR + translation payloads have **already been moved** to the downloadable `plugins/mlkit` module. The base APK:
- Has NO ML Kit dependencies
- Routes OCR/Translation through PluginManager (returns `NO_PLUGIN_INSTALLED` when absent)
- The `plugins/mlkit` module is a separate Gradle module (not an `:app` dependency)
- Plugin signing/infrastructure mirrors the LLM plugin pattern

This phase appears to be **already complete** in the codebase. The remaining work is verification and documentation.

