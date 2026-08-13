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
| `PluginCapability.kt` | The sealed extension points (`TextTransform`, `OCR`, `WebSearch`, `Export`, `TextTools`, `LanguageDetection`, `WebCapture`, `ClipShare`, `FileTransfer`, `Assistant`, `Dictation`, `ReadAloud`, `Translation`, `ScreenshotNote`) — each with an `exclusive` flag. |
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
| `screenshot/…` | Screenshot→Note (Phase 16) — reuses the existing annotated-page renderer + persist + OCR route; pure `ScreenshotFlowPlanner`. Serves `ScreenshotNote`. |

Supporting pieces outside the framework package:

| File | Purpose |
|------|---------|
| `services/SettingsManager.kt` | Stores `plugin_enabled_<id>`, `plugin_ever_enabled_<id>` and namespace-`plugins.<id>.<key>` settings (persist across restarts). |
| `services/SettingsPluginEnableStore.kt` | Production `PluginEnableStore` adapter. |
| `services/SettingsPluginSettingsStore.kt` | Production `PluginSettingsStore` adapter. |
| `ui/components/PluginSettingsDialog.kt` | Settings → Plugins (state + reason + version + diagnostics "Test now" + refusal-aware toggles). |
| `ui/viewmodel/NoteflowViewModel.kt` | Wires registry/manager/diagnostics into the app + `transformNoteText()` + Phase 15 routes (`exportNote`, `analyzeNoteText`, `diffNoteTexts`, `detectNoteLanguage`, `autoTagNoteLanguage`, `captureWebPage`, `parseSharedClip`, `autoTagLanguageOnSave`). |

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
LLM. The only remaining capability with no serving plugin is `FileTransfer` —
requests for it fail loudly with `NO_PLUGIN_INSTALLED`. See the phase-12 and
phase-16 implementation notes below.

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

## Adding a NEW plugin

1. Read `docs/PLUGIN_SDK.md` (the authoring contract) **first**.
2. Create `class MyPlugin : NoteflowPlugin, ServingInterface` in
   `com.authorss81.noteflow.plugins`, with a valid manifest and honest
   `availability()`.
3. Add it to `PluginRegistry.defaultPlugins()` — that single list is the whole
   discovery mechanism (compile-time only; no dynamic APK loading, ever).
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

- **Compile-time registration only.** No runtime-loaded APK plugins. No
  `ServiceLoader`/reflection surprises — the `defaultPlugins()` list is the API.
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