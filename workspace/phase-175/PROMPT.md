# Phase 175: Move ML Kit OCR + translation out of the base APK into downloadable plugins (R2-KS-21 MEDIUM) [NOT STARTED]

You are working on **InkFlow/Noteflow**. Kali round-2 static analysis (`docs/kali-report-round2.md` **R2-KS-21**, MEDIUM) found ML Kit OCR and translation **baked into the base release APK**, contradicting the user-approved downloadable-plugin architecture (AGENTS.md → hybrid downloadable-plugin model, commit `9292978`, `docs/plugin-architecture.md`):

> Native + assets in BASE APK contradicting plugin architecture:
> `assets/mlkit-google-ocr-models/` (1.6 MB OCR models), `libtranslate_jni.so` +
> `res/raw/translate_models_metadata.json` + `res/xml/rapid_response_client_defaults.xml`
> (ML Kit translation), `libmlkit_google_ocr_pipeline.so`.

No existing phase covers this: phase-170 covers only the **lingua** language-model pack + ABI splits, phase-171 only signing/pin. This phase moves the heavy ML Kit payloads out of the base APK and serves OCR + translation through the **existing** signature-verified downloadable-plugin runtime (Phases 22–24, 26, 29 pattern).

Read `docs/ARCHITECTURE.md` and `docs/phase-status.md` first.

## Context — current state (verify with file:line before editing)

- Heavy deps compiled into the base app: `app/build.gradle.kts:205-211`
  (`implementation(libs.mlkit.text.recognition)` + `implementation(libs.mlkit.translate)`),
  versions in `gradle/libs.versions.toml:18,23,69,72`.
- Compile-time plugin implementations that CONSTRUCT ML Kit clients directly:
  `plugins/ocr/MlKitOcrEngine.kt` (see `OnDeviceOcrPlugin` + OCR dialog wiring in
  `EditorScreen.kt`, `plugins/ocr/`), `plugins/translation/MlKitTranslatorEngine.kt`
  (translation dialog in `MarkdownPreviewScreen.kt`), registered in `plugins/PluginRegistry.kt`.
- The plugin model already in place: `plugins/NoteflowPlugin.kt` (capabilities incl. `OCR`),
  `plugins/runtime/RuntimePluginLoader.kt`, `ArtifactSignatureVerifier.kt`
  (pinned-cert hash + static content scan `ArtifactStaticScan.kt`), `PluginDownloader.kt`,
  `PluginUpdateEngine.kt`, `plugins/store/PluginStoreCatalog.kt`. See `docs/PLUGINS.md`.
- The base APK must stay lean: AGENTS.md hard rule — heavy/native features ship as
  downloadable signature-verified plugins, NEVER baked into the base APK (Phases 23/26/29).

## What to do

1. Remove the ML Kit text-recognition + translate libraries from the base app
   build (`app/build.gradle.kts` + `gradle/libs.versions.toml` entries only if
   nothing else uses them). NOTHING else about the base app should change.
2. Re-serve the OCR and translation capabilities through the **plugin system**:
   - OCR: the `OCR` capability route must resolve to a real OCR engine. Follow the
     existing downloadable-plugin path (e.g. the LLM plugin in `plugins/llm/` — a
     SEPARATE plugin module that compiles against the ML Kit binaries and is
     installed/loaded via `RuntimePluginLoader` + `ArtifactSignatureVerifier`),
     not new base-APK dependencies.
   - Translation: same treatment for the `TranslationEngine`/assistant translate
     surface used by `MarkdownPreviewScreen`.
   - Keep the capability FAÇADES and their UI dialogs intact — the base app must
     keep calling the plugin APIs, never touch ML Kit directly.
3. When the plugin is not installed, the feature must behave honestly: reuse the
   existing non-alarming "plugin not installed" notice (PluginResult/Failure /
   `NO_PLUGIN_INSTALLED` path, see `docs/PLUGINS.md`) — never a silent fake.
4. Respect the plugin SECURITY model: downloaded payloads go through the existing
   pinned-cert HTTPS transport + `ArtifactSignatureVerifier` static scan; the loaded
   engine only ever sees the capability facade's data (image bytes / text), NEVER the
   vault DEK, key material or DB handles.

## Definition of done

- `gradle assembleDebug` green and `gradle testDebugUnitTest` green (except the 1
  pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure — untouched).
- Release build (`assembleRelease` with the `RELEASE_KEYSTORE_B64`/`KEYSTORE_FILE`
  env — fail-closed B1-PLAT-1) unzipped and inspected: it must NOT contain
  `assets/mlkit-google-ocr-models/`, `libtranslate_jni.so`,
  `libmlkit_google_ocr_pipeline.so`, `res/raw/translate_models_metadata.json`,
  `rapid_response_client_defaults.xml`. BEFORE/AFTER byte counts in the REPORT.
- OCR + translation unit tests still green at the model/pure-JVM level (no network,
  no real ML Kit inference needed in CI), and the base-APK registration of the two
  capabilities resolves through the plugin route.
- `workspace/phase-175/REPORT.md`: the before/after APK inventory table + the removal
  evidence + how each capability resolves when installed / absent.
- Commit + push.

## Constraints

- Do NOT edit `.github/workflows/`. No new dependencies in the BASE app. The
  downloaded plugin payload module is fine (outside the base APK).
- No DB schema change. Never log/dump decrypted content, keys, image pixels or text.
- Keep the PluginRuntime security model intact (no direct DB/keystore handles from
  loaded code; artifact verification unchanged). Base-APK-size rule applies.
- The base app must fall BACK gracefully on low-end/old devices (AGENTS.md hardware
  rule): absent plugin ⇒ non-alarming one-time message + the feature stays disabled
  until the user installs it from the Plugin Store.