# Phase 175: Move ML Kit OCR + translation out of the base APK into downloadable plugins (R2-KS-21 MEDIUM) [NOT STARTED]

You are working on **InkFlow/Noteflow**. Kali round-2 static analysis (`docs/kali-report-round2.md` **R2-KS-21**, MEDIUM) found ML Kit OCR and translation **baked into the base release APK**, contradicting the user-approved downloadable-plugin architecture (AGENTS.md → hybrid downloadable-plugin model, commit `9292978`, `docs/plugin-architecture.md`):

> Native + assets in BASE APK contradicting plugin architecture:
> `assets/mlkit-google-ocr-models/` (1.6 MB OCR models), `libtranslate_jni.so` +
> `res/raw/translate_models_metadata.json` + `res/xml/rapid_response_client_defaults.xml`
> (ML Kit translation), `libmlkit_google_ocr_pipeline.so`.

No existing phase covers this: phase-170 covers only the **lingua** language-model pack + ABI splits, phase-171 only signing/pin. This phase moves the heavy ML Kit payloads out of the base APK and serves OCR + translation through the **existing** signature-verified downloadable-plugin runtime (Phases 22–24, 26, 29 pattern).

Read `docs/ARCHITECTURE.md` and `docs/phase-status.md` first.

## WORKFLOW RULE — YOU WILL BLOCK IF YOU DON'T COMMIT
A previous run of this phase got BLOCKED (3 attempts, zero commits). To guarantee
progress, work in SMALL STEPS and `git add -A && git commit -m "llops: phase-175 step N: <desc>" && git push` AFTER EVERY STEP below — even intermediate exploration. Never sit on uncommitted work. Do NOT attempt the whole move in one giant edit.

## Step 0 - Inventory first (commit it)
- Verify current state with file:line: `app/build.gradle.kts:205-211` ML Kit deps,
  `gradle/libs.versions.toml:18,23,69,72`, `plugins/ocr/MlKitOcrEngine.kt`,
  `plugins/translation/MlKitTranslatorEngine.kt`, `plugins/PluginRegistry.kt`,
  `EditorScreen.kt` (OCR dialog), `MarkdownPreviewScreen.kt` (translation dialog).
- Build the baseline: `gradle assembleDebug` + `gradle testDebugUnitTest` — record
  green/broken BEFORE state (except the pre-existing `Phase148UiFailureTextScrubTest`
  UNC-path failure, untouched). Record the release APK inventory
  (assets/libtranslate_jni.so/libmlkit_google_ocr_pipeline.so/res/raw/...).
- COMMIT this step.

## Step 1 - Follow the LLM-plugin pattern for a NEW OCR/translation plugin module
- Read `plugins/llm/` end-to-end (its `build.gradle.kts`, `LocalLlmPlugin.kt`,
  `engine/NativeLibraryBundle.kt`, `policy/ModelDownloadPolicy.kt`) and the runtime
  (`plugins/runtime/RuntimePluginLoader.kt`, `ArtifactSignatureVerifier.kt`,
  `ArtifactStaticScan.kt`, `PluginDownloader.kt`, `PluginUpdateEngine.kt`).
- Create a SEPARATE plugin module (e.g. `plugins/mlkit/`) that compiles against the
  ML Kit OCR/translate binaries and exposes `OnDeviceOcrPlugin` +
  `TranslationEngine` implementations that load THROUGH the plugin runtime (same
  as the LLM plugin) — NOT new base-APK deps. Move the existing
  `MlKitOcrEngine.kt`/`MlKitTranslatorEngine.kt` logic into it, adapted to run as a
  loaded engine. If ML Kit's model assets must be downloaded at runtime (like lingua),
  use the plugin's download path, not baked assets.
- COMMIT this step (module skeleton + moved code compiling).

## Step 2 - Strip ML Kit from the base app
- Remove `implementation(libs.mlkit.text.recognition)` +
  `implementation(libs.mlkit.translate)` from `app/build.gradle.kts` and their
  `libs.versions.toml` entries IF nothing else uses them. Nothing else in the base
  app changes. Keep the capability FAÇADES + UI dialogs intact — the base app calls
  plugin APIs only, never ML Kit directly.
- Ensure the base APK no longer contains: `assets/mlkit-google-ocr-models/`,
  `libtranslate_jni.so`, `libmlkit_google_ocr_pipeline.so`,
  `res/raw/translate_models_metadata.json`, `rapid_response_client_defaults.xml`.
- COMMIT this step.

## Step 3 - Honest absent-plugin behavior
- When the plugin is not installed, OCR/translation must return the existing
  `PluginResult.Failure` / `NO_PLUGIN_INSTALLED` non-alarming notice (see
  `docs/PLUGINS.md`) — never a silent fake. The feature stays disabled until the
  user installs it from the Plugin Store (AGENTS.md hardware rule: graceful on
  low-end devices).
- COMMIT this step.

## Step 4 - Security model intact
- Downloaded payloads go through the EXISTING pinned-cert HTTPS transport +
  `ArtifactSignatureVerifier` static scan; loaded engines see ONLY the capability
  facade's data (image bytes / text), NEVER the vault DEK, key material or DB handles.
- COMMIT this step.

## Definition of done
- `gradle assembleDebug` green and `gradle testDebugUnitTest` green (except the 1
  pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure — untouched).
- Release build (`assembleRelease` with `RELEASE_KEYSTORE_B64`/`KEYSTORE_FILE` env —
  fail-closed B1-PLAT-1) unzipped + inspected: NO `assets/mlkit-google-ocr-models/`,
  `libtranslate_jni.so`, `libmlkit_google_ocr_pipeline.so`,
  `res/raw/translate_models_metadata.json`, `rapid_response_client_defaults.xml`.
  BEFORE/AFTER byte counts in the REPORT.
- OCR + translation unit tests still green at model/pure-JVM level (no network, no
  real ML Kit inference in CI); base-APK registration resolves both capabilities
  through the plugin route.
- `workspace/phase-175/REPORT.md`: before/after APK inventory table + removal evidence
  + how each capability resolves when installed / absent.
- Commit + push.

## Constraints
- Do NOT edit `.github/workflows/`. No new dependencies in the BASE app. The
  downloaded plugin payload module is fine (outside the base APK).
- No DB schema change. Never log/dump decrypted content, keys, image pixels or text.
- Keep the PluginRuntime security model intact (no direct DB/keystore handles from
  loaded code; artifact verification unchanged). Base-APK-size rule applies.
- COMMIT AFTER EVERY STEP — a phase with zero committed steps is a failure.