# Plugins — InkFlow's capability framework

InkFlow's high-level features (OCR, web search, AI assistants, file transfer,
export, text transforms) are built as **plugins**: small, isolated, verifiable
modules that register a well-defined *capability* and are routed to by a central
manager. The core app never hardcodes which plugin serves a feature — it asks the
framework "who serves X?" and gets back an answer (or a loud failure).

This document explains the architecture. **Plugin authors MUST also read
`docs/PLUGIN_SDK.md`** — the machine-readable contract (manifest schema,
lifecycle, error handling, namespacing, migration, testing) that a Phase 12+
plugin must follow.

## Why a plugin framework?

Phases 2–9 made the app honest: every claimed feature either works or is removed.
Future features must not be bolted into the core where they become untestable and
unmaintainable. The plugin framework gives every future feature:

- a **single registration point** (compile-time discovery),
- **user opt-in** (a plugin is off until the user enables it in Settings → Plugins),
- **capability gating + dependency resolution** (availability, deps and conflicts
  are re-derived on every change — never stale),
- **guarded invocation** (a throwing/buggy plugin is contained and surfaced as a
  typed result, never a crash),
- **loud failure** (a request that cannot be served returns a clear user-facing
  message — it never silently degrades or crashes),
- **observability** (per-plugin derived state, reason, version, last invocation
  result, "Test now" self-check),
- **JVM unit-testability** (the framework core has no Android dependencies).

## Package layout

Everything lives in `com.authorss81.noteflow.plugins`:

| File | Purpose |
|------|---------|
| `PluginCapability.kt` | The sealed extension points (`TextTransform`, `OCR`, `WebSearch`, `Export`, `TextTools`, `LanguageDetection`, `WebCapture`, `ClipShare`, `FileTransfer`, `Assistant`, `Dictation`, `ReadAloud`, `Translation`, `ScreenshotNote`, `ShapeFromInk`, plus the Phase 26 `Dictionary`, `Weather`, `UnitConversion`, `OutlineGenerator`, `CitationFormatter`) — each with an `exclusive` flag. |
| `PluginManifest.kt` | `PluginManifest`, `SemanticVersion` (`Major.Minor.Patch`), `PluginPermission`, and the manifest validator. |
| `NoteflowPlugin.kt` | The plugin interface (manifest-derived identity, tri-state `availability`, lifecycle hooks) + per-capability *serving interfaces* (`TextTransformPlugin`, …). |
| `PluginLifecycle.kt` | `PluginLifecycleState` (REGISTERED/ENABLED/AVAILABLE/UNAVAILABLE/DISABLED/REJECTED), derived `PluginStateInfo`, enable result + enable-order resolution types. |
| `PluginRegistry.kt` | Compile-time discovery, manifest validation, topological enable order, deterministic conflict arbitration, `resolve()` derived states, guarded lifecycle. |
| `PluginManager.kt` | Guarded capability routing returning `PluginResult` (Success/Failure/Unavailable) + `PluginFailureReason`, `withPluginAsync`, self-check, invocation records. |
| `PluginDiagnostics.kt` | Diagnostics surface: per-plugin state, version, last invocation outcome, "Test now". |
| `PluginEnableStore.kt` | Persistence abstraction for per-plugin opt-in (incl. "ever enabled"). |
| `PluginSettings.kt` | Namespaced per-plugin settings (`plugins.<id>.<key>`) + `PluginSettingKey`. |
| `PluginLogger.kt` | Logging abstraction (NoOp for JVM tests; Android logcat impl in the app). |
| `Rot13TransformPlugin.kt` | The first real plugin — a working end-to-end proof of the wiring (TextTransform). |
| `ocr/OnDeviceOcrPlugin.kt` | The real, on-device OCR plugin (Phase 12) — ML Kit text-recognition, offline, no API key, no INTERNET. Serves `OCR`. |
| `websearch/DuckDuckGoWebSearchPlugin.kt` | The real web-search plugin (Phase 12) — keyless DuckDuckGo Instant Answer API. Serves `WebSearch`. |
| `export/ExportEnginePlugin.kt` | The real Export Engine plugin (Phase 15) — Markdown/HTML via the app's CommonMark parser, PDF via the built-in `PdfDocument`; shares via `ACTION_SEND` + FileProvider. Serves `Export`. |
| `clipshare/ClipToInkFlowPlugin.kt` | The "Clip to InkFlow" share-target plugin (Phase 15) — classifies + size-guards incoming `ACTION_SEND`/`ACTION_SEND_MULTIPLE` content before it is stored encrypted. Serves `ClipShare`. |
| `texttools/TextToolsEngine.kt` | Text Tools (Phase 15) — word/char/paragraph/sentence counts, reading time, Flesch-Kincaid readability, note diff. PURE JVM. Serves `TextTools`. |
| `langdetect/LanguageDetectionEngine.kt` | Language Detection (Phase 15) — Lingua-based detection + `lang:<iso>` auto-tagging that honours user overrides. PURE JVM. Serves `LanguageDetection`. |
| `webcapture/WebCaptureEngine.kt` | Web Capture (Phase 15) — fetches an http(s) page (jsoup) and reduces it to clean Markdown for a new note. Serves `WebCapture`. |
| `dictation/…` | Dictation (Phase 16) — SpeechRecognizer glue + pure `DictationAssembler` (spacing/capitalization) + `SpeechErrorMapper`. Serves `Dictation`. |
| `readaloud/…` | Read Aloud (Phase 16) — platform TTS + pure `TtsChunkSplitter` (fenced code verbatim, prose sentence-packed) + `ReadAloudPolicy` (quiet-mode refusal). Serves `ReadAloud`. |
| `translation/…` | Translation (Phase 16) — ML Kit `translate` (keyless, API 26+) + pure `TranslationCatalog` (28 targets, source auto-detection via Lingua). Serves `Translation` (exclusive). |
| `assistant/…` | On-Device Assistant (Phase 16) — MediaPipe `tasks-genai` (Qwen2-0.5B GGUF, user-downloaded) + pure `AssistantPrompts`/`AssistantStoragePolicy` + `AssistantModelDownloader`. Serves `Assistant` (exclusive). |
| `store/…` | The Plugin Store (Phase 21) — `PluginInstallStore`, `PluginStoreCatalog`, `PluginStoreController` (bundled-definition install/uninstall lifecycle; no network catalog, no APK loading). |
| `runtime/…` | The hybrid-architecture seams (Phase 22) — `PluginEntry` (unified catalog entry for bundled + remote), `PluginVersion`, `PluginEntryStore` (+codec), `PluginContext` (deny-by-default capability facade), `PluginRuntime` (+`PluginRuntimeRegistry`, honest `NotYetImplemented` stubs for Phases 23/24). See `docs/plugin-architecture.md`. |
| `CaseChangePlugin.kt` | The store's OPTIONAL plugin (Phase 21) — UPPER/lower/Title Case TextTransform; NOT in `defaultPlugins()`, downloaded from the store. |
| `screenshot/…` | Screenshot→Note (Phase 16) — reuses the existing annotated-page renderer + persist + OCR route; pure `ScreenshotFlowPlanner`. Serves `ScreenshotNote`. |
| `inktos/…` | Ink→Shape (Phase 25) — free, lightweight, compile-time plugin that converts freehand ink strokes into crisp shapes (line / rectangle / rounded-rect / ellipse / arrow) on explicit user command. Pure JVM `InkToShapeGeometry` core + thin `InkToShapePlugin` wrapper. Serves `ShapeFromInk`. |
| `dictionary/…` | Dictionary (Phase 26) — keyless `dictionaryapi.dev` lookup with an honest bundled OFFLINE word list fallback; result is labelled with its source. Pure-JVM `DictionaryCore` + thin `DictionaryClient`. Serves `Dictionary`. |
| `weather/…` | Weather (Phase 26) — keyless Open-Meteo dated snapshot, no GPS; fixed default city or coarse lat/lon from the plugin's namespaced settings. Pure-JVM `WeatherCore` + `WeatherClient` + `WeatherAvailability`. Serves `Weather`. |
| `unitconverter/…` | Unit Converter (Phase 26) — PURE JVM "2 km to mi" conversion (length/mass/temperature/currency-basic reference rates), fully offline, zero deps. Serves `UnitConversion`. |
| `outline/…` | Outline & Checklist (Phase 26) — PURE Kotlin generator producing a Markdown outline or checkbox list from the note text. Serves `OutlineGenerator`. |
| `citation/…` | Citation Formatter (Phase 26) — formats a pasted URL into `[title](url)`, fetching the `<title>` over HTTPS or honestly falling back to a host-derived label. Serves `CitationFormatter`. |

Supporting pieces outside the framework package:

| File | Purpose |
|------|---------|
| `services/SettingsManager.kt` | Stores `plugin_enabled_<id>`, `plugin_ever_enabled_<id>` and namespace-`plugins.<id>.<key>` settings (persist across restarts). |
| `services/SettingsPluginEnableStore.kt` | Production `PluginEnableStore` adapter. |
| `services/SettingsPluginSettingsStore.kt` | Production `PluginSettingsStore` adapter. |
| `ui/components/PluginSettingsDialog.kt` | Settings → Plugins (state + reason + version + diagnostics "Test now" + refusal-aware toggles). |
| `ui/viewmodel/NoteflowViewModel.kt` | Wires registry/manager/diagnostics into the app + `transformNoteText()` + Phase 15 routes (`exportNote`, `analyzeNoteText`, `diffNoteTexts`, `detectNoteLanguage`, `autoTagNoteLanguage`, `captureWebPage`, `parseSharedClip`, `autoTagLanguageOnSave`) + Phase 25 `convertStrokeToShape`. |

## Core concepts

### `PluginCapability` — the extension points

A capability is a unit of behavior a plugin can provide: a sealed `object` with a
`key`, a user-facing `label`, and an `exclusive` flag. Non-exclusive capabilities
(like `TextTransform`) may be served by several plugins at once; **exclusive**
ones (`OCR`, `WebSearch`, `FileTransfer`, `Assistant`, `Export`) may be served by
exactly one enabled plugin at a time — conflicts are arbitrated deterministically
(higher version; tie → earlier registration) and the loser is disabled with a
reason.

Today `TextTransform` (ROT13 proof), `OCR` (on-device ML Kit), `WebSearch`
(keyless DuckDuckGo) and — since Phase 15 — `Export`, `ClipShare`, `TextTools`,
`LanguageDetection` and `WebCapture` are implemented for real. Phase 16 added
`Dictation`, `ReadAloud`, `Translation` and `ScreenshotNote`, and turned
`Assistant` from a declared extension point into a real, user-downloaded local
LLM. Phase 25 added `ShapeFromInk` — converting freehand ink into crisp shapes on
demand. Phase 26 added five lightweight compile-time plugins — `Dictionary`,
`Weather`, `UnitConversion`, `OutlineGenerator` and `CitationFormatter` (see the
Phase 26 section below). The only remaining capability with no serving plugin is
`FileTransfer` — requests for it fail loudly with `NO_PLUGIN_INSTALLED`. See the
phase-12 and phase-16 implementation notes below.

### `PluginManifest` & `NoteflowPlugin` — what a plugin is

```kotlin
class MyPlugin : NoteflowPlugin, TextTransformPlugin {
    override val manifest = PluginManifest(
        id = "com.authorss81.noteflow.plugins.mything",
        name = "My Thing",
        version = SemanticVersion(1, 0, 0),
        minSupportedApi = 26,
        description = "Does the thing.",
        capabilities = setOf(PluginCapability.TextTransform),
        permissions = emptySet(),        // declared for visibility
        dependencies = emptySet(),       // ids of required plugins
        requiresCapabilities = emptySet()// capabilities another plugin must serve
    )
    override fun availability(context: Context?) = PluginAvailability.Ok
    override fun onEnable(context: Context?, settings: PluginSettings) { /* warm up */ }
    override fun transformText(text: String): String = /* … */
}
```

`id`, `name`, `description`, `version` and `capabilities` are **all derived from
the manifest** — one source of truth that the registry validates at construction.
`availability()` is **tri-state** (`Ok` / `Unavailable(reason)` / `Unknown`) and
re-evaluated on every resolution, so permission loss or dependency loss flips the
derived state immediately.

### Derived lifecycle states (no stale state)

`PluginRegistry.resolve(context)` recomputes every plugin's state fresh on every
call, folding in opt-in, device availability, dependencies and conflict
arbitration:

```
REGISTERED → ENABLED → AVAILABLE          (happy path)
                │        └→ UNAVAILABLE   (device / permission / dependency)
                └→ DISABLED               (user off, or conflict arbitration)
REJECTED                                  (invalid manifest)
```

Only `AVAILABLE` plugins are routed. See the lifecycle matrix tests.

### `PluginResult` — never throw at the UI

Every capability request returns a sealed `PluginResult<T>` with a
machine-readable `PluginFailureReason`:

```kotlin
sealed class PluginResult<out T> {
    data class Success<T>(val value: T) : PluginResult<T>()
    data class Failure(val reason: PluginFailureReason, val message: String) : PluginResult<Nothing>()
    data class Unavailable(val reason: PluginFailureReason, val message: String) : PluginResult<Nothing>()
}
```

### `PluginManager` — the guarded router

```kotlin
pluginManager.withPlugin(PluginCapability.TextTransform, context) { plugin ->
    (plugin as TextTransformPlugin).transformText(text)
}                     // → PluginResult<String>, never a thrown exception
```

Routing rules, checked in order:

1. no installed **valid** plugin declares the capability → `Failure(NO_PLUGIN_INSTALLED)`,
2. declared but none opted-in → `Failure(NONE_ENABLED)`,
3. opted-in but derived state is not `AVAILABLE` → `Unavailable` (device /
   dependency / conflict, with the specific reason),
4. otherwise the winner's action runs **guarded**: any `Exception` (incl.
   `RuntimeException`) or null return becomes `Failure(PLUGIN_ERROR)` that names
   the plugin and exception class — recorded in diagnostics, never a crash.

`withPluginAsync` runs the same guard on `Dispatchers.Default` so work never
blocks the main thread.

### Diagnostics & observability

`PluginDiagnostics` exposes per plugin: current derived state + reason, version,
last invocation outcome (success / failure summary with exception class name
only) and a **"Test now"** `selfCheck` action. This is wired into Settings →
Plugins — not dead UI. Lifecycle events (enable/disable/config/failure) are
logged without content (ids, names and exception class names only).

## How a feature uses a plugin (the shipped example)

The Markdown editor (`MarkdownPreviewScreen`) shows a **Plugins** menu in the top
bar. It does **not** reference `Rot13TransformPlugin`. Menu items are grayed out
when the plugin is not yet opted-in (or not device-available), so a disabled
plugin never fails on click; enabling happens in Settings → Plugins.

A plugin transform is never applied silently. Selecting a runnable plugin opens a
confirmation dialog, and applying it first writes a snapshot of the current text
into **Version History** ("Before running `<plugin>`") so the original wording is
always one restore away before the transformed text is saved.

## The two full implementations (Phase 12)

### On-Device OCR (`OnDeviceOcrPlugin`, capability `OCR`)

- **Real model:** Google ML Kit `text-recognition` (bundled Latin model) — fully
  offline, no API key, no INTERNET. The only new dependency added for this phase.
- **Split for testability:** the plugin wraps a `OcrEngine` interface.
  `MlKitOcrEngine` (the model runner) is **platform-only** — it cannot run inside
  a JVM unit test, so `OcrPluginWrapperTest` covers the pure wrapper
  (input validation, whitespace formatting, error mapping, cancellation,
  plugin-manager routing) with an injected fake engine. Nothing is silently
  skipped: the model invocation itself is verified on-device/emulator, not in a
  JVM test.
- **UI:** the photo card on the canvas gains an "Extract text (OCR)" button
  (disabled when the plugin is off/unavailable). The result dialog
  (`OcrResultDialog`) shows real progress/error states, is cancelable, and only
  copies via `ClipboardGuard.recordCopy()`. "Insert into note" places the text in
  a sticky note under the source image.
- **Cancellation:** ML Kit's `Task` has no `cancel()` handle; cancellation is
  honoured by never resuming a cancelled continuation (`isActive` guards), so a
  cancel stops the coroutine immediately and cannot double-resume it.

### DuckDuckGo Web Search (`DuckDuckGoWebSearchPlugin`, capability `WebSearch`)

- **Real backend:** the keyless DuckDuckGo Instant Answer API
  (`https://api.duckduckgo.com/?q=…&format=json&no_html=1`) over plain
  `java.net.HttpURLConnection` on `Dispatchers.IO` — no new HTTP dependency.
- **Honest availability:** `availability()` reflects INTERNET permission presence
  *and* an active network with INTERNET capability, so the derived state flips to
  UNAVAILABLE the moment the device goes offline and recovers when connectivity
  returns.
- **UI:** the Markdown editor's **Plugins** menu → "Search the web…" opens
  `WebSearchDialog`; tapping a result inserts a `[title](url)` link into the note
  and dismisses. Offline shows a clear "check your connection" error — never a
  silent failure.
- **Tests:** `WebSearchPluginTest` is pure JVM (no network): URL building, JSON
  parsing over sample payloads (including empty/malformed responses and URL
  dedupe where the abstract wins over a related topic sharing its URL), the
  offline availability gate, and manager routing with an injected backend.

Both plugins live in their own packages under `plugins/` (not the core
ViewModel/screens), are registered in `PluginRegistry.defaultPlugins()`, are off
by default (opt-in via Settings → Plugins), and follow the Phase 11
`docs/PLUGIN_SDK.md` lifecycle/error-isolation contract.

## The five Phase 15 plugins (real, all individually toggleable)

Phase 15 ships five real plugins. Each is **pure-JVM-core-first**: the testable
core (parsing, validation, assembly, analysis, extraction) has no Android
classes and is covered by JVM unit tests; only the platform slice (network, PDF
canvas, FileProvider, intent) touches Android. All five are registered in
`PluginRegistry.defaultPlugins()`, off by default, and individually toggleable in
Settings → Plugins.

### Export Engine (`export/`, capability `Export`, exclusive)

- **Markdown/HTML:** assembled by the pure `ExportPayloadAssembler` using the
  app's existing CommonMark parser + GFM tables (`MarkdownHtmlConverter`); a
  plain-text-only note is escaped into `<p>` paragraphs; an empty note gets an
  explicit "Empty note" body.
- **PDF:** the built-in `PdfDocument` (A4) renders the plain-text body — no
  heavyweight PDF dependency.
- **Sharing:** the produced file is written to `cacheDir/exports` (FileProvider
  `cache-path` entry added in `res/xml/file_paths.xml`) and shared via an
  `ACTION_SEND` intent (`ExportShareHelper`). No new permissions.
- **UI:** EditorScreen's overflow menu → "Share via Export Engine…".
- **Tests:** `ExportPayloadAssemblerTest` (12 tests — sanitization, HTML doc
  structure, tables, fallbacks, escaping).

### Clip to InkFlow (`clipshare/`, capability `ClipShare`)

- **Parsing/validation:** pure `SharedClipParser` classifies the incoming share
  into TEXT / IMAGES / FILES / MULTIPART and applies a size guard mirroring the
  app's backup limits (single item 50 MB, total 200 MB, text 5 MB) BEFORE any
  bytes are copied or stored.
- **Storage:** a validated clip is stored through the same encrypted
  `NoteRepository.createPage` path as any note.
- **UI:** `MainActivity` routes `ACTION_SEND` / `ACTION_SEND_MULTIPLE` (now also
  `*/*` for arbitrary files) through `parseSharedClip`; a rejected clip shows the
  plugin's reason as a Snackbar instead of creating a note.
- **Tests:** `SharedClipParserTest` (10 tests — classification + size guard).

### Text Tools (`texttools/`, capability `TextTools`)

- **Analysis:** pure `TextToolsAnalyzer` — word/character/paragraph/sentence
  counts, reading time @200 wpm, Flesch-Kincaid grade + reading-ease label.
- **Diff:** pure `TextNoteDiff` (LCS line-diff → `DiffHunk`s).
- **UI:** MarkdownPreviewScreen's Plugins menu → "Text Tools: analyze & diff…"
  opens `TextToolsDialog`.
- **Tests:** `TextToolsTest` (12 tests — counts, reading time, readability,
  diff hunks, excerpt bounds).

### Language Detection (`langdetect/`, capability `LanguageDetection`)

- **Core:** `LanguageDetectionCore` wraps **Lingua** (Apache-2.0, pure JVM) in
  low-accuracy mode over a bounded 24-language subset to keep memory sane; short
  (< 20 char) inputs return a clear `NoMatch`.
- **Auto-tagging:** `autoTagLanguage` merges `lang:<iso>` into the note's tags
  and **never overwrites a user's existing `lang:*`/`language:*` tag**; the
  per-plugin `lang_auto_tag` setting (default on) gates auto-tagging on save.
- **UI:** MarkdownPreviewScreen's Plugins menu → "Detect language / auto-tag…";
  `MainActivity`'s markdown save hook calls `autoTagLanguageOnSave`.
- **Tests:** `LanguageDetectionTest` (11 tests — real EN/DE detection,
  too-short gate, override honouring, no-duplicate merges).

### Web Capture (`webcapture/`, capability `WebCapture`)

- **Policy:** `WebPageFetchPolicy.validateUrl` normalizes bare hostnames to
  https, allow-lists only http(s), and rejects ftp:/javascript:/hostless input.
- **Extraction:** pure jsoup `WebToMarkdownExtractor` strips scripts/styles/
  nav/footer/ads, prefers an article container, and converts to Markdown
  (headings, lists, blockquotes, links, images, code).
- **Fetch:** `WebPageFetcher` runs on `Dispatchers.IO` with a 5 MB streamed cap
  and redirect-loop protection; offline shows a clear error.
- **UI:** HomeScreen's ⋮ menu → "Capture Web Page as Note"; the captured
  Markdown becomes a new encrypted note.
- **Tests:** `WebCaptureExtractorTest` (13 tests — fixture extraction, chrome
  stripping, container fallback, URL policy) + `WebPageFetcher` is never
  exercised over a real network in unit tests.

## The five Phase 16 plugins (keyless + on-device, all individually toggleable)

Phase 16 ships five more real plugins, all keyless. Nothing is ambient: every
feature is strictly user-initiated (`dictation/` needs an explicit mic tap,
`readaloud/` an explicit Play tap, model downloads an explicit consent tap with
progress). All five use the same **pure-JVM-core-first** split — the testable
core has no Android classes and is JVM-unit-tested; only the platform glue
(SpeechRecognizer, TextToSpeech, ML Kit, MediaPipe, canvas render) touches
Android. All are registered in `PluginRegistry.defaultPlugins()`, off by
default, and individually toggleable in Settings → Plugins.

### Dictation (`dictation/`, capability `Dictation`)

- **Core:** pure `DictationAssembler` — whitespace normalization, single-space
  separation (or a clean newline), sentence-capitalization after `.`/`!`/`?`/
  blank-note. `SpeechErrorMapper` maps recognizer errors to user-facing text.
- **Glue:** `AndroidDictationEngine` uses `SpeechRecognizer` with
  `EXTRA_PREFER_OFFLINE=true` + `EXTRA_PARTIAL_RESULTS=true`; partials stream
  live, finals fold into the note via the pure assembler.
- **Honest on-device:** when offline recognition isn't available the dialog
  shows the plugin's message as a one-time banner rather than silently streaming
  network-backed hypotheses; the user still chooses explicitly.
- **UI:** MarkdownPreviewScreen's Plugins menu → "Dictate into this note…".
- **Tests:** `DictationAssemblerTest` (10 tests).

### Read Aloud (`readaloud/`, capability `ReadAloud`)

- **Core:** pure `TtsChunkSplitter` (fenced code spoken verbatim in line-sized
  code-tagged chunks; prose packed by paragraph then sentence, hard-wrapped at
  spaces when a sentence exceeds the cap — nothing is dropped) + pure
  `ReadAloudPolicy`.
- **Quiet mode:** a user-enabled SilentToggle makes the queue refuse with a
  clear `RefuseQuiet` result — **no bytes are ever spoken** in quiet mode
  (new `silentModeEnabled` setting on `SettingsManager`; the dialog's toggle).
- **Playback:** platform `TextToSpeech` speaks only in direct response to the
  explicit Play tap; the policy hands out the chunk plan before any speaking.
- **UI:** MarkdownPreviewScreen's Plugins menu → "Read this note aloud…".
- **Tests:** `TtsChunkSplitterTest` (9 tests — chunking + policy).

### Translation (`translation/`, capability `Translation`, exclusive)

- **Core:** pure `TranslationCatalog` — 28 curated target languages, code
  normalization (`nb→no`, `zh-CN/TW→zh`, `pt-br→pt`) and source auto-detection
  via the existing Lingua detector (falls back to `en` when inconclusive).
- **Glue:** `MlKitTranslatorEngine` wraps ML Kit `translate` (keyless, API 26+,
  `RemoteModelManager` for the downloaded-state check).
- **Model handling:** the small per-language model downloads only when the user
  taps Translate or "Download model" (that tap is the one-time consent);
  `downloadModelIfNeeded()` runs inside `translate()`, and any download
  failure surfaces a typed `ModelNotReady`/error — never silent.
- **UI:** MarkdownPreviewScreen's Plugins menu → "Translate this note…"
  (target dropdown, model status, download-with-progress, "Replace note").
- **Tests:** `TranslationPluginTest` (7 tests with a fake `TranslatorEngine`).

### On-Device Assistant (`assistant/`, capability `Assistant`, exclusive)

- **Core:** pure `AssistantPrompts` (per-task prompt assembly; long notes are
  truncated to a 6 000-char word-boundary context cap) and pure
  `AssistantStoragePolicy` (default model identity — Qwen2-0.5B-Instruct GGUF,
  398 MB — plus the free-space guard with a 64 MB safety margin).
- **Glue:** `MediaPipeLlmEngine` runs the GGUF via MediaPipe `tasks-genai`
  (pure-Java, minSdk 21, LLM Inference API, 4 ABIs); `AssistantModelDownloader`
  streams the model into `filesDir/noteflow/assistant` via a `.part` temp file
  (atomic rename, cancellation cleanup, progress callback).
- **Low-end gate:** on low-RAM / ≤2-core devices the plugin is `Unavailable`
  with an explicit reason (a real low-token GGUF keeps memory bounded).
  `model_url` is overridable via the per-plugin `plugins.<id>.model_url` setting.
- **UI:** the existing `OnDeviceSmartAssistantBottomSheet` now runs the real LLM
  (Summarize / Auto-Tags / Action Items / Ask) with a model-download consent +
  progress card; a low-end or disabled device shows the reason.
- **Tests:** `AssistantPromptTest` (11 tests — prompts, truncation, storage
  policy space checks).

### Screenshot → Note (`screenshot/`, capability `ScreenshotNote`)

- **Core:** pure `ScreenshotFlowPlanner` — decides IMAGE_ONLY vs IMAGE_WITH_OCR
  (OCR applies only when both requested AND an OCR plugin is available),
  "Screenshot · Mon d, yyyy" title and a `screenshot-yyyyMMdd-HHmmss.png`
  filename.
- **Reuse, no duplication:** rendering goes through the existing
  `ImportExportService.exportAnnotatedPage` + `persistFile` path; OCR (when
  chosen) runs through the existing `OCR` plugin route. The new image note is
  created via the usual `addPage(sourceFilePath, sourceFileType="image", …)`.
- **UI:** EditorScreen's overflow menu → "Screenshot → new note" (and "…+
  OCR"); success opens the new note via the existing page-open path.
- **Tests:** `ScreenshotFlowTest` (7 tests — mode + OCR-decision logic).

## Ink → Shape (`inktos/`, capability `ShapeFromInk`) — Phase 25

A free, lightweight, compile-time plugin that converts a **freehand ink stroke
into a crisp shape** (line / rectangle / rounded-rectangle / ellipse / arrow) on
explicit user command. It deliberately complements — and never replaces — the
existing **auto-snap** behaviour (`ShapeRecognitionHelper.trySnapShape` runs on
draw-end); Ink→Shape is strictly **on-demand** and is an independent,
user-triggered action.

- **Pure JVM core:** `InkToShapeGeometry` (Android-free, no Compose, no model
  classes) takes a `List<InkPoint>` and returns a `DetectedShape` or `null`. All
  decision logic is JVM-unit-tested. Detection order and thresholds:

  | Shape | Trigger |
  |-------|---------|
  | **LINE** | straightness > 0.82 AND perpendicular deviation < 10% of the span (2-point strokes accepted) |
  | **RECTANGLE** (incl. rounded) | closed loop, perimeter fit ≥ 0.72, corner coverage ≥ 2, margin = `max(5px, 6% of diagonal)` — checked BEFORE ellipse so a traced square stays a square |
  | **ELLIPSE** | closed loop, ≥ 10 points, ellipse-equation fit deviation < 0.35, circularity ≥ 0.30 (circle vs ellipse distanced by circularity) |
  | **ARROW** | ≥ 8 points, straightness in 0.55–0.95 (checked BEFORE the line gate so long arrows — whose head adds little to path length — still convert), perpendicular deviation < 12%, final-segment direction change ≥ 10° (the head vee). Snapped head geometry matches the canvas's own 24px / 30° arrowhead render. |

  Anything that fits none of these honestly returns `NotAShape` — the stroke is
  **never** mutated or faked into a shape.
- **Thin wrapper:** `InkToShapePlugin : NoteflowPlugin, ShapeFromInkPlugin`
  maps the app's `Stroke` points to `InkPoint`s and the detected shape back to a
  crisp `Stroke` (tool switched to `LINE` / `RECTANGLE` / `ELLIPSE` / `ARROW`,
  the stroke's color/width preserved). `convertToShape()` returns
  `ShapeFromInkOutcome.Success(kind, snappedStroke, replaceOriginal)` /
  `NotAShape` / `Error`.
- **Undoable:** the canvas routes the result through the existing
  `handleStrokesChange` undo path, so a conversion is one undo away. `keep
  original stroke` (`plugins.<id>.keepOriginal`, default **off** = replace) keeps
  the raw ink alongside the shape instead.
- **Canvas never reaches into the geometry core.** `EditorScreen` only talks to
  the plugin through the `ShapeFromInk` capability (via
  `NoteflowViewModel.convertStrokeToShape`), exactly like every other feature —
  no hardcoded plugin logic in the canvas.
- **UI:** `CanvasSettingsBottomSheet` gains an "Ink → Shape" section (below the
  Shape Auto-Snap toggle): a **Convert to Shape** button (only enabled when the
  plugin is opted in; otherwise it shows "Unavailable — enable Ink to Shape in
  Plugins") and the "Keep original stroke" toggle. Conversion shows a Snackbar
  naming the detected shape; non-shapes show an honest message ("No clean shape
  detected — the stroke is too rough or not a line, circle, rectangle or arrow.")
  and the raw stroke is left untouched.
- **Size:** the whole plugin is a few KB of Kotlin — it stays **compile-time**
  (base APK), consistent with the "lightweight pure-JVM stays bundled" rule in
  `docs/plugin-architecture.md`.
- **Tests:** `InkToShapePluginTest` (25 tests) — synthetic straight/wavy/closed
  ellipse/rect/rounded-rect/arrow/zigzag/triangle/blob point sets, correct-shape
  detection, wrong-shape rejection, plugin conversion, the keep-original toggle,
  capability routing (`NO_PLUGIN_INSTALLED` / `NONE_ENABLED` / `AVAILABLE`) and
  the store listing.

## Lightweight compile-time plugin pack (Phase 26)

Phase 26 adds **five** more real, individually toggleable plugins. Under the
hybrid model (`docs/plugin-architecture.md`) they are **compile-time** because
each is pure-JVM or tiny-keyless-HTTP and adds only a few KB to the base APK — no
ML Kit, no native engines (those stay downloadable). All five are registered in
`PluginRegistry.defaultPlugins()`, are non-optional in the store (installed by
default, like every built-in), are **off by default** (opt-in via Settings →
Plugins or the Plugin Store), and are reachable from the Markdown editor's
**Plugins** menu. Network runs strictly on `Dispatchers.IO`, is always
user-initiated, and every one has a graceful offline path or a clear error.

### Dictionary (`dictionary/`, capability `Dictionary`)

- **Core:** pure-JVM `DictionaryResponseParser` parses the real `dictionaryapi.dev`
  payload (array of entries → word, phonetic, up to 5 definitions) and
  `OfflineWordList` holds a small bundled word list.
- **Honest offline path:** on any network failure, a blank result, or a
  non-200 the plugin falls back to the bundled list and labels the result
  `source = offline` — the UI always shows which source served it. A word found
  in neither honestly returns `NotFound`, never a fake definition.
- **UI:** Plugins menu → "Look up a word…" (`DictionaryDialog`) inserts
  `**word** — definition` (plus `- ` extra definitions) into the note.
- **Tests:** `DictionaryPluginTest` (pure JVM, no network) — real JSON parse,
  malformed/empty payloads, the URL builder, offline fallback + case-insensitive
  lookup, honest not-found, and manager routing with an injected backend.

### Weather (`weather/`, capability `Weather`)

- **No GPS, keyless:** the plugin reads location ONLY from its namespaced
  settings (`plugins.<id>.city` / `.latitude` / `.longitude` / `.locationName`),
  never from the device. Default: fixed London coordinates (no geocoding).
- **Availability is honest:** `WeatherAvailability.evaluate` derives
  `Unavailable("Offline — check your connection.")` the moment the device loses
  INTERNET capability, so the menu item flips to "(offline)" rather than
  pretending to work.
- **Config:** `WeatherDialog` has a "Configure location" panel (city, or explicit
  lat/lon + label) that persists via the plugin's namespaced settings and calls
  `notifyConfigChanged`; half-configured coordinates are refused loudly.
- **Snapshot provenance:** `sourceNote` ("Default city" / "Configured location")
  is decided by the config path actually taken — never guessed from the city
  name.
- **Tests:** `WeatherPluginTest` (pure JVM, no network) — real forecast +
  geocoding JSON parsing, WMO-code mapping, dated formatting, URL building, the
  offline availability gate, default vs configured provenance, and manager
  routing with an injected backend.

### Unit Converter (`unitconverter/`, capability `UnitConversion`)

- **PURE JVM, fully offline, zero deps.** Grammar: `2 km to mi`, `2km in mi`,
  `2 km → mi`, `2 km -> mi`. Length (mm…mi), mass (mg…oz), temperature
  (C/F/K, non-linear via Kelvin) and **currency-basic** (USD/EUR/GBP/JPY/INR at
  fixed, clearly-labelled reference rates — no live FX).
- **Honest:** unparseable or cross-category queries return a typed error with an
  example; tiny results never silently become "0".
- **UI:** Plugins menu → "Unit Converter…" (`UnitConverterDialog`); "Insert into
  note" writes the result inline.
- **Tests:** `UnitConverterTest` (conversion-matrix correctness against
  hand-computed references, aliases, parsing variants, error paths, routing).

### Outline & Checklist (`outline/`, capability `OutlineGenerator`)

- **PURE Kotlin.** `OutlineGeneratorCore` groups the note's lines into sections
  (`## heading` + `- ` bullets) or converts them to `- [ ]` checkboxes, keeping
  existing checkbox lines and stripping list/heading decoration. Deterministic,
  no ML, no network.
- **Scope is explicit:** it operates on the note's FULL current text (there is no
  selection model), the result is **previewed** in the dialog, and it is only
  written into the note when the user taps Insert — never silently.
- **Tests:** `OutlineGeneratorTest` (grouping/indent, decoration normalisation,
  blank-input handling, plugin + manager routing).

### Citation Formatter (`citation/`, capability `CitationFormatter`)

- **Payload building:** `CitationFormatterCore` validates the URL (bare hostnames
  upgraded to https, non-http(s) rejected) and builds `[title](url)`; title text
  is escaped backslash-first so fetched/typed titles cannot break the Markdown,
  and destinations containing spaces/parentheses are angle-bracket wrapped.
- **HTTPS title fetch:** `HttpsTitleFetcher` GETs the page `<title>` (HTTPS only,
  10s timeouts, 512 KB cap) and a pure `extractHtmlTitle` decodes common
  entities. On ANY failure the plugin honestly falls back to a host-derived
  label — it never fabricates a title.
- **UI:** Plugins menu → "Cite a URL…" (`CitationFormatterDialog`) with an
  optional manual title (no network needed then).
- **Tests:** `CitationFormatterTest` (payload building, URL validation, title
  extraction + entity decoding, escaping, injected-fetcher plugin behaviour,
  routing).

## Plugin Store — install/uninstall lifecycle (Phase 21)

The **Plugin Store** (HomeScreen ⋮ menu → **Plugin Store**) adds a full
install/uninstall lifecycle ON TOP of the compile-time registry. It is honest
about the "no dynamic APK loading" rule:

- Every catalog entry is a **bundled definition already inside the APK** —
  there is no network catalog and Download never fetches an APK or needs the
  network. The store degrades gracefully offline by construction.
- **Download** installs a bundled *definition*: for a previously-deleted
  built-in it flips the persisted install state back on; for an *optional*
  plugin (e.g. **Case Converter**, `CaseChangePlugin`) it activates a compiled
  instance. Progress is reported (0f → 1f) and the install runs off the main
  thread, but nothing is ever loaded from outside the app.
- **Delete** removes a plugin **completely**: downloaded assets are deleted
  (e.g. the assistant's 398 MB on-device GGUF via `deleteDownloadedAssets`),
  the opt-in flag, ever-enabled history and every `plugins.<id>.*` setting are
  wiped, and the plugin disappears from the registry, enabling and capability
  routing until re-downloaded. Re-download starts fresh from `REGISTERED` (off).
- **Disable / Enable** are the ordinary opt-in lifecycle and **keep all data** —
  re-enabling restores the plugin's settings.

State machine per catalog entry: `Not downloaded → Download → REGISTERED (off)
→ Enable → ENABLED/AVAILABLE → Disable → DISABLED (data kept) → … → Delete →
Not downloaded (wiped)`.

**Phase 22 unified the catalog.** Every store row now wraps the single
[`PluginEntry`](plugin-architecture.md) type (`plugins/runtime/PluginEntry.kt`)
that covers bundled AND remote plugins, and the store UI labels each entry
**bundled** (compiled into the APK) or **remote** (downloadable + signature-verified,
Phase 23) alongside its version. The catalog merges any persisted remote entries
from `PluginEntryStore` automatically. See `docs/plugin-architecture.md` for the
full hybrid design (state machine, facade, update model, security).

**Phase 24 added dynamic updates — always manual, always approved, always
verified.** A downloaded remote plugin can be updated from the store:

- **Check for updates** (store header) fetches the hosted version manifest
  (`https://plugin-updates.inkflow.app/v1/manifest.json` — HTTPS, keyless,
  user-initiated; `plugins/runtime/HostedPluginManifest.kt` +
  `PluginManifestFetcher.kt`) and compares it against the INSTALLED downloadable
  plugins (`PluginUpdateChecker`). An update is listed ONLY when the manifest
  version is **strictly newer** than installed on the **same channel** — never a
  downgrade, never an equal no-op, never for a bundled plugin (built-ins are
  **"managed by app update"** and update with the app release).
- **Update** shows a per-update **"Approve & install"** dialog (current → new
  version, what changed, download size) — there is NO auto-update toggle, and the
  approval gate is enforced in both the store controller and the runtime engine.
  On approval the flow is: download (HTTPS) → **re-verify** pinned-cert hash +
  sha256 → **load smoke-test** → the previous version's files stay intact → atomic
  swap (`plugins/runtime/PluginUpdateEngine.kt`). ANY failure deletes the new
  artifact and keeps the previous version active with a clear "rolled back" message.
- **Rollback** is real: the previous version is recorded (`plugin_update_previous_<id>`,
  `SettingsPluginUpdateStore.kt`) before any update byte moves, and
  `PluginRuntime.rollback` restores a re-verified previous version — the sanctioned
  exception to the no-downgrade rule. Store Delete wipes the record too.

Where the pieces live:

| File | Purpose |
|------|---------|
| `plugins/store/PluginInstallStore.kt` | Install-state persistence seam (`isInstalled`/`setInstalled`) + in-memory impl for tests. |
| `plugins/store/PluginStoreCatalog.kt` | Bundled catalog: every compiled plugin + the optional store-only `CaseChangePlugin` entry (with category, capability, permission, install-size metadata). |
| `plugins/store/PluginStoreController.kt` | Pure-JVM lifecycle: `rows()`, `download()` (bundled-definition install, progress, never throws), `delete()` (assets + settings + registry). |
| `services/SettingsPluginInstallStore.kt` | Production install store over `SettingsManager` (`plugin_uninstalled_<id>`, default installed → backward compatible). |
| `PluginRegistry.installPlugin/uninstallPlugin` | The registry-level install/uninstall (guarded `onDisable`, cache drop, `compiledPlugins` vs `activePlugins()`). |
| `ui/components/PluginStoreDialog.kt` | The store UI (status line, Download progress, Enable/Disable/Delete, delete confirmation). |
| `NoteflowViewModel` | Store rows/progress/busy/messages `StateFlow`s + `storeDownload`/`storeDelete`/`clearStoreMessage`. |
| `plugins/runtime/HostedPluginManifest.kt` | Phase-24 hosted version manifest (offer fields + strict pure-JVM parser, whole-document refusal). |
| `plugins/runtime/PluginUpdateChecker.kt` | Phase-24 update comparison (strictly-newer only, channel-matched, remote-only, ordered). |
| `plugins/runtime/PluginUpdateEngine.kt` | Phase-24 verified update + rollback orchestrator (approval gate, re-verify, smoke-test, keep-previous, atomic swap). |
| `plugins/store/PluginUpdateCoordinator.kt` | Phase-24 store seam (fetchManifest / runUpdate) → `services/DownloadablePluginUpdater.kt` (production). |

Default behaviour is unchanged for anything that does not opt in: a `PluginRegistry`
constructed without an install store uses `AllInstalledInstallStore`, so every
plugin counts as installed (pre-store behaviour). The production ViewModel passes
`SettingsPluginInstallStore`, and the store's optional plugins only appear when
the user downloads them.

**Tests:** `PluginStoreLifecycleTest` (pure JVM) covers the full lifecycle —
built-ins installed by default, download → available + routable, disable keeps
data, deleted plugin wiped + skipped by routing, re-download starts `REGISTERED`,
refused double-download / unknown-delete. `PluginExecutionSimulationTest` proves
the end-to-end execution path (ROT13 round-trip, OCR via injected engine,
export routing, disabled-skip).

## Adding a NEW plugin

1. Read `docs/PLUGIN_SDK.md` (the authoring contract) **first**.
2. Create `class MyPlugin : NoteflowPlugin, ServingInterface` in
   `com.authorss81.noteflow.plugins`, with a valid manifest and honest
   `availability()`.
3. Add it to `PluginRegistry.defaultPlugins()` — that single list is the whole
   discovery mechanism for compile-time plugins. For a plugin the user must
   explicitly download, instead add it to the store as an OPTIONAL bundled
   definition (`PluginStoreCatalog` optional entry + `createInstance`) and — if it
   downloads model/assets on install — implement `NoteflowPlugin.deleteDownloadedAssets`
   so Delete frees them. Heavy native features should additionally be structured as
   a Phase-23 downloadable module so the base APK stays lean.
4. It is now visible in Settings → Plugins (with its derived state, reason,
   version and "Test now"), off by default, and automatically reachable from
   every feature wired to its capability.
5. Add tests mirroring the plugin test classes (`PluginFrameworkTest`, the
   lifecycle matrix, dependency/conflict and manifest tests).

## Adding a NEW capability

1. Add an `object` to the `PluginCapability` sealed class (mark it `exclusive`
   if only one engine may serve it at a time).
2. Define the serving interface next to `TextTransformPlugin` in `NoteflowPlugin.kt`.
3. Route it through `PluginManager` in the feature and in the ViewModel; surface
   the result in the UI (a `when` over Success/Failure/Unavailable).
4. Document the contract in `docs/PLUGIN_SDK.md` so future plugins implement it
   consistently.
5. If no plugin actually serves it yet, the capability is a *declared extension
   point* — `PluginManager` will fail loudly with "No plugin is installed" until a
   real one lands (that is the honest state; we do not fake capabilities).

## Design rules (non-negotiable)

- **Hybrid model — see `docs/plugin-architecture.md`.** The catalog has exactly ONE
  entry type, `PluginEntry` (`plugins/runtime/`), covering BOTH buckets; the store
  rows label each as **bundled** or **remote**. Everything below follows from that.
- **Base-APK size is a first-class constraint.** Heavy/native features (camera
  OCR/QR, large ML engines, the local LLM) are NOT baked into the base APK — they
  ship as **downloadable, signature-verified plugins** fetched over HTTPS only on
  explicit user consent. The Phase-23 runtime (download → pinned-cert-hash + sha256
  verify → `DexClassLoader`) plugs into the `PluginRuntime` seams
  (`plugins/runtime/PluginRuntime.kt`, currently honest `NotYetImplemented` stubs).
  Lightweight pure-JVM / small-keyless-HTTP plugins ship compile-time because they
  cost a few KB. Never add a large native dependency to the base app.
- **Compile-time registration for built-ins.** The `defaultPlugins()` list is the
  API for compile-time plugins. No `ServiceLoader` surprises. The Plugin Store
  installs bundled *definitions* today; the Phase-23 runtime adds verified
  downloadable DEX for heavy features. Downloadable code NEVER receives direct
  DB/keystore/`EncryptionService`/decrypted-content handles — only the whitelisted
  `PluginContext` capability facade (`plugins/runtime/PluginContext.kt`, deny-by-default
  until Phase 23 grants per-capability calls).
- **Delete ≠ disable.** Disable keeps data (re-enableable); Delete wipes opt-in,
  ever-enabled history and all `plugins.<id>.*` settings, deletes downloaded
  assets AND the persisted entry blob (`plugin_entry_<id>`), and removes the plugin
  from the registry until re-download (which starts fresh, off).
- **Verify before any load; re-verify on every load.** A remote artifact must pass
  its pinned cert + SHA-256 checks before ANY load and again on EVERY load. Tamper
  is a hard failure, never a partial load.
- **No new third-party dependencies** in the framework package.
- **Opt-in by default.** `SettingsManager.isPluginEnabled` defaults to `false`.
- **Derived state is never stale.** `resolve()` recomputes availability, deps and
  conflicts on every change; permission loss immediately flips `UNAVAILABLE`.
- **Guarded everywhere.** Route invocations AND lifecycle hooks are contained —
  an exception in one plugin never crashes the app. Never log plugin content,
  keys, or decrypted note data.
- **Namespaced settings.** Per-plugin settings live under `plugins.<id>.<key>` so
  two plugins never collide.
- **Loud failure, never silent.** Every unservable request returns a typed
  result with a message the UI can show.
- **No surprising content mutation.** Plugin-driven text transforms are confirmed
  by the user and snapshot into Version History before they overwrite a note.
- **No hardcoded single-plugin logic** in the framework — features address
  capabilities and serving interfaces, not concrete plugin classes.
- **Tests are mandatory.** The Phase 10/11 suites must stay green: discovery,
  persistence, routing, disabled-skip, unavailable-capability failure,
  once-per-process `onEnable`, dependency/conflict arbitration, manifest
  validation, error isolation, settings namespacing, and the plugin's output.
  Phase 22 adds the runtime-seam suites (`PluginVersionTest`, `PluginEntryStoreTest`,
  `PluginContextFacadeTest`, `PluginRuntimeSeamTest`).