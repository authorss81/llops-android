package com.authorss81.noteflow.plugins

import android.content.Context

/**
 * The result of asking a plugin "can you run right now?". Tri-state so the
 * registry can distinguish a definite NO ([Unavailable], with a reason) from
 * "can't tell yet" ([Unknown], e.g. no `Context` to check against).
 */
sealed class PluginAvailability {
    /** The plugin can serve requests on this device/context. */
    data object Ok : PluginAvailability()

    /** The plugin cannot serve here; [reason] is user-facing. */
    data class Unavailable(val reason: String) : PluginAvailability()

    /** Availability cannot be evaluated right now (e.g. no context). */
    data object Unknown : PluginAvailability()
}

/**
 * A single plugin installed into InkFlow.
 *
 * Plugins are registered **at compile time** via [PluginRegistry.defaultPlugins]
 * — there is deliberately NO dynamic (runtime-loaded APK) plugin loading. To add
 * a new plugin, implement this interface, register it in the registry, and the
 * UI/settings/manager wiring appears automatically.
 *
 * ## Identity & versioning
 *
 * [manifest] is the single source of truth: [id], [name], [description],
 * [version] and [capabilities] are derived from it. Bump `manifest.version` for
 * any behavior/settings change — a plugin runs its own settings migration on the
 * version bump (see docs/PLUGIN_SDK.md § Versioning & migration).
 *
 * ## Lifecycle (see docs/PLUGIN_SDK.md § Lifecycle contract)
 *
 * - [availability] — device/context gate (AGSL support, permission held, API
 *   level…). Re-checked on every registry resolution, so a revoked permission or
 *   lost dependency immediately flips the derived state to UNAVAILABLE.
 * - [onEnable] — invoked when the plugin becomes enabled: on first opt-in,
 *   on a disable→re-enable cycle in the same process, and at cold start via
 *   [PluginRegistry.onProcessStart] (once per process). Cheap and idempotent.
 * - [onDisable] — invoked when the user turns the plugin off AND when the
 *   deterministic capability-conflict arbitration demotes it to a loser
 *   (at most once per arbitration round). Release resources here.
 * - [onConfigChanged] — invoked when the user changes a `plugins.<id>.<key>`
 *   setting and the app calls [PluginRegistry.notifyConfigChanged].
 * - [selfCheck] — deep self-test used by the "Test now" diagnostics action;
 *   defaults to [availability].
 *
 * Every hook receives a [PluginSettings] slice scoped to this plugin's id — a
 * plugin can never read or write another plugin's settings.
 *
 * To actually serve a capability, a plugin must ALSO implement the capability's
 * serving interface (e.g. [TextTransformPlugin]) so the framework can invoke it
 * without reflection. See docs/PLUGINS.md for the integration guide.
 *
 * @param context nullable only to keep the framework JVM-unit-testable without
 *   Robolectric — production code always passes a real Context.
 */
interface NoteflowPlugin {
    /** The machine-readable manifest (identity, version, capabilities, deps). */
    val manifest: PluginManifest

    /** Globally-unique id, from [PluginManifest.id]. */
    val id: String get() = manifest.id

    /** User-facing name, from [PluginManifest.name]. */
    val name: String get() = manifest.name

    /** One-line user-facing description, from [PluginManifest.description]. */
    val description: String get() = manifest.description

    /** Semantic version, from [PluginManifest.version]. */
    val version: SemanticVersion get() = manifest.version

    /** The capabilities this plugin can serve, from [PluginManifest.capabilities]. */
    val capabilities: Set<PluginCapability> get() = manifest.capabilities

    /** Device/context gate with a reason. See [PluginAvailability]. */
    fun availability(context: Context?): PluginAvailability

    /** Convenience: true iff [availability] is [PluginAvailability.Ok]. */
    fun isAvailable(context: Context?): Boolean = availability(context) is PluginAvailability.Ok

    /** Called once per process on first opt-in (or cold-start reconciliation). */
    fun onEnable(context: Context?, settings: PluginSettings)

    /** Called when the plugin is turned off. */
    fun onDisable(context: Context?, settings: PluginSettings) {}

    /** Called after a user changes one of this plugin's settings. */
    fun onConfigChanged(context: Context?, settings: PluginSettings) {}

    /** Deep self-check for diagnostics; defaults to the availability gate. */
    fun selfCheck(context: Context?): PluginAvailability = availability(context)

    /**
     * Delete any downloaded assets/models this plugin keeps in app-private
     * files (Phase 21 store "Delete" — delete = gone, assets wiped). Default
     * no-op; plugins that download models (e.g. the assistant's GGUF) override
     * to remove them so a deleted plugin truly leaves nothing behind. Runs on
     * the caller's thread; keep it cheap and never throw into the store.
     */
    fun deleteDownloadedAssets(context: Context?) {}
}

/**
 * Serving interface for the [PluginCapability.TextTransform] capability.
 *
 * A plugin that implements this interface can transform note text. The feature
 * wiring (e.g. the Markdown editor's plugin menu) discovers plugins for this
 * capability through the registry and calls [transformText] — it never hardcodes
 * a specific plugin class.
 */
interface TextTransformPlugin {
    fun transformText(text: String): String
}

/**
 * Result of an OCR request, returned by [OcrPlugin.recognizeText].
 *
 * Typed so the UI can distinguish a real extraction ([Success]), a genuinely
 * empty image ([NoText], with a user-facing reason) and a validated, user-facing
 * failure ([Error]) — while the plugin still fails loudly instead of silently
 * returning nothing. A plugin must NEVER return null (the manager treats that as
 * [PluginResult.Failure]).
 */
sealed class OcrOutcome {
    /** The recognized text (may still need trimming). */
    data class Success(val text: String) : OcrOutcome()

    /** The model ran but found no readable text; [message] is user-facing. */
    data class NoText(val message: String) : OcrOutcome()

    /** The request failed; [message] is a validated, user-facing reason. */
    data class Error(val message: String) : OcrOutcome()
}

/**
 * A single web-search hit, as inserted into a note as `[title](url)`.
 *
 * @param title link label (never blank; falls back to a generic label).
 * @param url absolute http(s) URL of the result.
 * @param snippet optional one-line context from the search API.
 */
data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String? = null
)

/**
 * Result of a web-search request, returned by [WebSearchPlugin.searchWeb].
 *
 * Typed so the UI can show real results ([Success]) or a clear, user-facing
 * connectivity/service error ([Error], e.g. "offline — check connection")
 * without ever silently degrading.
 */
sealed class WebSearchOutcome {
    /** One or more real results (may be empty for a valid-but-empty response). */
    data class Success(val results: List<WebSearchResult>) : WebSearchOutcome()

    /** The request could not be served; [message] is user-facing. */
    data class Error(val message: String) : WebSearchOutcome()
}

/**
 * Serving interface for the [PluginCapability.OCR] capability.
 *
 * A plugin that implements this interface extracts text from an on-device image
 * (the file at [imagePath]). Implementations MUST run the model off the main
 * thread (e.g. `withContext(Dispatchers.IO)`) and MUST be cancelable when the
 * calling coroutine is cancelled. The returned [OcrOutcome] carries extracted
 * text or a user-facing failure — never a silent empty result.
 *
 * [context] is nullable exactly like the plugin lifecycle hooks — production
 * always passes a real Context; tests pass null.
 */
interface OcrPlugin {
    suspend fun recognizeText(context: Context?, imagePath: String): OcrOutcome
}

/**
 * Serving interface for the [PluginCapability.WebSearch] capability.
 *
 * A plugin that implements this interface performs a real, keyless web search
 * and returns typed results for insertion into a note as `[title](url)` links.
 * Implementations MUST make network calls off the main thread
 * (`withContext(Dispatchers.IO)`) and return [WebSearchOutcome.Error] with a
 * clear "offline — check connection" message on connectivity failure rather than
 * throwing or silently returning nothing.
 */
interface WebSearchPlugin {
    suspend fun searchWeb(query: String): WebSearchOutcome
}

// ---------------------------------------------------------------------------
// Phase 15 — productivity & knowledge plugin serving interfaces.
// Each capability below has a typed serving interface so the framework routes
// by capability + interface, never hardcoded plugin classes. The CORES are pure
// JVM (fully CI-testable); only thin platform wrappers (files, PDFs, intents,
// network) live in the plugins' platform slices.
// ---------------------------------------------------------------------------

/** Output formats served by the [PluginCapability.Export] engine plugin. */
enum class ExportFormat { MARKDOWN, HTML, PDF }

/** Everything the export engine needs to render a note into a document. */
data class ExportRequest(
    val title: String,
    /** Markdown source when the note holds one; null for ink/canvas-only notes. */
    val markdown: String? = null,
    /** Fallback plain text (e.g. extractedText) when no markdown is present. */
    val plainText: String? = null
)

/**
 * Outcome of an export request. [Success] carries the written file + format;
 * [Error] carries a validated, user-facing reason. A plugin must never return
 * null — the manager treats that as [PluginResult.Failure].
 */
sealed class ExportOutcome {
    data class Success(val file: java.io.File, val format: ExportFormat) : ExportOutcome()
    data class Error(val message: String) : ExportOutcome()
}

/** Serving interface for the [PluginCapability.Export] capability. */
interface ExportPlugin {
    /**
     * Render [request] to [format] and write it to a shareable file. Runs on
     * `Dispatchers.IO` (callers use [com.authorss81.noteflow.plugins.PluginManager.withPluginAsync]).
     * [context] is nullable for JVM tests; production always passes a real
     * `Context` (needed for PDF/PNG rendering and cache-dir placement).
     */
    suspend fun exportNote(context: Context?, request: ExportRequest, format: ExportFormat): ExportOutcome
}

/** Kind of clip parsed from an incoming share intent. */
enum class ClipKind { TEXT, IMAGES, FILES, MULTIPART }

/** One incoming shared stream (images, files, clips). */
data class SharedStream(
    val uriString: String,
    val mimeType: String? = null,
    /** null = size not yet measured (measured after the platform copies it). */
    val sizeBytes: Long? = null
)

/** A validated "clip" ready to be stored into an encrypted note. */
data class SharedClip(
    val kind: ClipKind,
    val text: String? = null,
    val streams: List<SharedStream> = emptyList()
)

/** The raw material the platform reads off an ACTION_SEND intent. */
data class SharedInput(
    val action: String? = null,
    val text: String? = null,
    val streams: List<SharedStream> = emptyList()
)

/** Result of parsing incoming share content. Never null, never stale. */
sealed class ClipParseOutcome {
    data class Success(val clip: SharedClip) : ClipParseOutcome()
    /** The share can't be clipped as-is; [reason] is user-facing. */
    data class Rejected(val reason: String) : ClipParseOutcome()
}

/** Serving interface for the [PluginCapability.ClipShare] capability. */
interface ClipSharePlugin {
    /** Classify + validate incoming share content. PURE JVM (unit-tested). */
    fun parse(input: SharedInput): ClipParseOutcome
}

/** Structural statistics of a note's text (word/char/paragraph/readability). */
data class TextAnalysis(
    val wordCount: Int,
    val characterCount: Int,
    val characterCountNoSpaces: Int,
    val paragraphCount: Int,
    val sentenceCount: Int,
    val readingTimeSeconds: Int,
    val fleschKincaid: Double,
    val fleschKincaidLabel: String
)

enum class DiffOp { ADDED, REMOVED, UNCHANGED }

/** One changed region of a note-diff, for a human-readable inline preview. */
data class DiffHunk(
    val op: DiffOp,
    val startLine: Int,
    val lineCount: Int,
    val excerpt: String
)

/** Serving interface for the [PluginCapability.TextTools] capability. */
interface TextToolsPlugin {
    /** Analyze [text] (word/character/paragraph/sentence/reading/readability). */
    fun analyzeText(text: String): TextAnalysis

    /** Simple line-diff of two note texts; empty result when identical. */
    fun diffTexts(oldText: String, newText: String): List<DiffHunk>
}

/** A detected language: BCP-47-ish iso code + display name + confidence. */
data class DetectedLanguage(
    val isoCode: String,
    val displayName: String,
    val confidence: Double
)

/** Outcome of a language-detection request. */
sealed class LanguageDetectionOutcome {
    data class Success(val language: DetectedLanguage) : LanguageDetectionOutcome()
    data class NoMatch(val message: String) : LanguageDetectionOutcome()
    data class Error(val message: String) : LanguageDetectionOutcome()
}

/** Serving interface for the [PluginCapability.LanguageDetection] capability. */
interface LanguageDetectionPlugin {
    /** Detect the dominant language of [text]. PURE JVM implementation. */
    fun detectLanguage(text: String): LanguageDetectionOutcome

    /**
     * Merge a freshly-detected `lang:<iso>` tag into [existingTags] (a
     * comma-separated tag string) honouring a user override: any existing
     * `lang:*` tag is left untouched. PURE JVM (unit-tested).
     */
    fun autoTagLanguage(text: String, existingTags: String): String

    /** True when [tag] is a language tag this plugin manages (case-insensitive). */
    fun isLanguageTag(tag: String): Boolean
}

/** A fetched web page reduced to its readable content. */
data class WebCaptureResult(
    val title: String,
    val markdown: String
)

/** Outcome of a web-page capture request. */
sealed class WebCaptureOutcome {
    data class Success(val result: WebCaptureResult) : WebCaptureOutcome()
    data class Error(val message: String) : WebCaptureOutcome()
}

/** Serving interface for the [PluginCapability.WebCapture] capability. */
interface WebCapturePlugin {
    /**
     * Fetch [url], extract its readable content and return it as clean
     * Markdown. Network runs on `Dispatchers.IO` and is strictly user-initiated;
     * failures always return a clear, user-facing [WebCaptureOutcome.Error]
     * (e.g. "offline — check connection") — never a silent empty result.
     */
    suspend fun captureWebPage(context: Context?, url: String): WebCaptureOutcome
}

// ---------------------------------------------------------------------------
// Phase 16 — privacy-first on-device AI & media plugin serving interfaces.
// The CORES are pure JVM (fully CI-testable); only thin platform wrappers
// (SpeechRecognizer, TextToSpeech, ML Kit models, LiteRT LLM, canvas capture)
// live behind injected engines so the routing + decision logic is unit-tested
// with fakes, exactly like the Phase 12/15 pattern.
// ---------------------------------------------------------------------------

/**
 * Listener for a live [DictationPlugin] session. The platform recognizer emits
 * partial hypotheses and final utterances; the plugin's pure-JVM assembler
 * (or the caller) folds the finals into the editor text.
 */
interface DictationListener {
    /** A live, still-changing hypothesis (shown as non-committed preview text). */
    fun onPartialUtterance(text: String)

    /** A committed utterance — safe to insert into the note. */
    fun onFinalUtterance(text: String)

    /** A recognised failure; [message] is user-facing (e.g. offline models missing). */
    fun onError(message: String)

    /** The recognizer finished / was stopped. */
    fun onEnd()
}

/** Handle to a live dictation session; calling [stop] ends it gracefully. */
interface DictationSession {
    /** Stop listening and finalize the current utterance, then tear down. */
    fun stop()

    /** Abort immediately without finalizing. */
    fun cancel()
}

/**
 * Serving interface for the [PluginCapability.Dictation] capability.
 *
 * Voice activation is ALWAYS explicit — the UI shows a mic button and nothing
 * is ever recorded ambiently. The grammar/assembly logic is pure JVM and
 * unit-tested; only the [startSession] glue touches `SpeechRecognizer`.
 */
interface DictationPlugin {
    /**
     * Whether the platform has on-device (offline) recognition models. When
     * false the UI surfaces [onDeviceAvailabilityMessage] instead of silently
     * streaming network-backed hypotheses.
     */
    fun isOnDeviceAvailable(context: Context?): Boolean

    /** User-facing reason when [isOnDeviceAvailable] is false. */
    fun onDeviceAvailabilityMessage(): String

    /** Start a live session. [listener] receives partials/finals/errors. */
    fun startSession(context: Context?, listener: DictationListener): DictationSession

    /**
     * Fold a committed [utterance] into the note's [currentText]. PURE JVM —
     * spacing, capitalization and whitespace normalization live here so the
     * whole assembly path is unit-testable without any Android dependency.
     */
    fun appendUtterance(currentText: String, utterance: String): String
}

/** One passage chunk safe to hand to the platform TTS engine. */
data class TtsChunk(
    val index: Int,
    val text: String,
    /** True when the chunk came from a fenced code block (speak verbatim/flat). */
    val isCode: Boolean
)

/** Result of asking the read-aloud engine to start speaking. */
sealed class ReadAloudOutcome {
    data class Started(val chunkCount: Int) : ReadAloudOutcome()
    data class Empty(val message: String) : ReadAloudOutcome()
    data class Quiet(val message: String) : ReadAloudOutcome()
    data class Error(val message: String) : ReadAloudOutcome()
}

/** The pure-JVM plan produced by [ReadAloudPlugin.plan] before any speaking. */
sealed class TtsSpeechPlan {
    data class Play(val chunks: List<TtsChunk>) : TtsSpeechPlan()
    data class RefuseQuiet(val message: String) : TtsSpeechPlan()
    object NothingToSpeak : TtsSpeechPlan()
}

/**
 * Serving interface for the [PluginCapability.ReadAloud] capability.
 *
 * Playback is NEVER automatic: [play] only runs in direct response to an
 * explicit user action, and a user-enabled quiet mode (SilentToggle) makes the
 * queue refuse with [ReadAloudOutcome.Quiet] — no bytes are ever spoken in
 * quiet mode. Uses the platform `TextToSpeech` engine (no API key, no new
 * permission).
 */
interface ReadAloudPlugin {
    /** Split a passage into TTS-safe chunks. PURE JVM (unit-tested). */
    fun chunkText(passage: String, maxChunkChars: Int = 500): List<TtsChunk>

    /** Decide whether a chunked passage may be spoken right now. PURE JVM. */
    fun plan(passage: String, quietMode: Boolean, maxChunkChars: Int = 500): TtsSpeechPlan

    /** Begin speaking. Returns a typed outcome; never throws into the caller. */
    fun play(context: Context?, passage: String, quietMode: Boolean): ReadAloudOutcome

    /** Stop any active playback. */
    fun stop(context: Context?)

    /** Release the TTS engine (on disable/process teardown). */
    fun shutdown(context: Context?)
}

/** A language the on-device translator can translate into (code + label). */
data class TranslationLanguage(val code: String, val displayName: String)

/** Outcome of an on-device translation request. */
sealed class TranslationOutcome {
    data class Success(val translatedText: String) : TranslationOutcome()
    data class ModelNotReady(val message: String) : TranslationOutcome()
    data class Error(val message: String) : TranslationOutcome()
}

/** Progress state of an on-demand translation model download. */
sealed class TranslationModelStatus {
    data object Downloaded : TranslationModelStatus()
    data object NotDownloaded : TranslationModelStatus()
    data class Downloading(val progress: Float) : TranslationModelStatus()
    data class Error(val message: String) : TranslationModelStatus()
}

/**
 * Serving interface for the [PluginCapability.Translation] capability.
 *
 * Models are NOT bundled: they download once on first use after explicit user
 * consent (with clear progress) and then work fully offline. A failed/offline
 * download surfaces [TranslationModelStatus.Error]/[TranslationOutcome.Error]
 * with a clear message — it never crashes. The translator engine is injected
 * (fake in unit tests; ML Kit behind it in production).
 */
interface TranslationPlugin {
    /** The target languages offered by the UI (a curated ML Kit subset). PURE JVM. */
    fun supportedTargetLanguages(): List<TranslationLanguage>

    /** True when [targetLanguage]'s model is already stored on-device. */
    suspend fun isModelDownloaded(targetLanguage: String): Boolean

    /** Download [targetLanguage]'s model on demand. User-initiated, guarded. */
    suspend fun downloadModel(targetLanguage: String): TranslationModelStatus

    /** Translate [text] into [targetLanguage]. */
    suspend fun translate(targetLanguage: String, text: String): TranslationOutcome
}

/** Outcome of an on-device LLM request (summarize / action items / Q&A / tags). */
sealed class AssistantOutcome {
    data class Success(val text: String) : AssistantOutcome()
    data class ModelNotReady(val message: String) : AssistantOutcome()
    data class Error(val message: String) : AssistantOutcome()
}

/**
 * Serving interface for the [PluginCapability.Assistant] capability.
 *
 * Runs a small local LLM via the LiteRT-family runtime ([com.google.mediapipe
 * .tasks-genai], the engine LiteRT-LM continues). The model is NOT bundled:
 * the user downloads it once (~100-300 MB, consent + progress) into app-private
 * files, after which everything works with no network. A low-end device gate
 * makes the plugin [PluginAvailability.Unavailable] with a clear reason. Prompt
 * assembly and conversation logic are PURE JVM (unit-tested with a fake engine);
 * only [OnDeviceAssistantPlugin]'s model driver is platform.
 */
interface AssistantPlugin {
    /** True once the user-downloaded model file exists on-device. */
    fun isModelDownloaded(context: Context?): Boolean

    /** The downloaded model file, or null when not downloaded. */
    fun modelFile(context: Context?): java.io.File?

    /** Expected on-disk size of the model (used for the free-space guard). */
    fun expectedModelSizeBytes(): Long

    /** User-facing reason the assistant can't run here, or null when eligible. */
    fun unavailableReason(context: Context?): String?

    /** Download the model with progress [0f..1f]. User-initiated, guarded. */
    suspend fun downloadModel(context: Context?, onProgress: (Float) -> Unit): AssistantOutcome

    /** All assistant tasks. PURE JVM prompt assembly, fake-engine testable. */
    suspend fun summarize(context: Context?, noteText: String): AssistantOutcome
    suspend fun extractActionItems(context: Context?, noteText: String): AssistantOutcome
    suspend fun answerQuestion(context: Context?, noteText: String, question: String): AssistantOutcome
    suspend fun suggestTags(context: Context?, noteText: String): AssistantOutcome

    /** Release the loaded model (on disable/teardown). */
    fun close()
}

/** How a captured screenshot should become a note. */
enum class ScreenshotCaptureMode { IMAGE_ONLY, IMAGE_WITH_OCR }

/** Everything the caller needs to turn a screenshot into a note. PURE JVM. */
data class ScreenshotCapturePlan(
    val capturedAtMillis: Long,
    val mode: ScreenshotCaptureMode,
    val title: String,
    val fileName: String,
    val shouldOcr: Boolean,
    val ocrReusable: Boolean
)

/** Outcome of turning a canvas page into a note. */
sealed class ScreenshotCaptureOutcome {
    data class Success(
        val plan: ScreenshotCapturePlan,
        val imagePath: String,
        val extractedText: String?
    ) : ScreenshotCaptureOutcome()

    data class Error(val message: String) : ScreenshotCaptureOutcome()
}

// ---------------------------------------------------------------------------
// Phase 25 — InkStroke→Shape: on-demand freehand-stroke-to-shape conversion.
// The geometry core (`plugins/inktos/InkToShapeGeometry`) is PURE JVM and is
// deliberately Android-free; only this serving interface + the thin plugin
// wrapper touch the app's `Stroke` model. The canvas NEVER reaches into the
// geometry — it calls the plugin through this capability exactly like any
// other plugin feature.
// ---------------------------------------------------------------------------

/** The four geometric kinds the Ink→Shape plugin can produce. */
enum class ShapeKind(val label: String) {
    LINE("Line"), RECTANGLE("Rectangle"), ELLIPSE("Ellipse"), ARROW("Arrow")
}

/**
 * Outcome of converting a freehand stroke into a clean shape. Typed so the
 * canvas can distinguish a real conversion ([Success], with the crisp stroke
 * and whether it should REPLACE the original or be inserted alongside), an
 * honest rejection ([NotAShape] — the mark is too rough / the wrong kind, so
 * NOTHING changes and no fake shape is produced) and a hard failure ([Error]).
 */
sealed class ShapeFromInkOutcome {
    /**
     * The stroke converted successfully.
     *
     * @param kind the detected geometric kind.
     * @param snappedStroke the crisp, clean stroke to place on the canvas
     *   (same id/color/width/page/layer as the raw stroke).
     * @param replaceOriginal when true the canvas should REPLACE the original
     *   freehand stroke with [snappedStroke]; when false the original stays and
     *   the shape is inserted alongside it (the namespaced `keepOriginal`
     *   setting decides).
     */
    data class Success(
        val kind: ShapeKind,
        val snappedStroke: com.authorss81.noteflow.data.model.Stroke,
        val replaceOriginal: Boolean
    ) : ShapeFromInkOutcome()

    /**
     * No clean shape could be detected in this stroke. [message] is
     * user-facing. The stroke is left untouched — the plugin never guesses.
     */
    data class NotAShape(val message: String) : ShapeFromInkOutcome()

    /** The conversion request failed; [message] is user-facing. */
    data class Error(val message: String) : ShapeFromInkOutcome()
}

/**
 * Serving interface for the [PluginCapability.ShapeFromInk] capability.
 *
 * A plugin that implements this interface converts a raw freehand stroke into a
 * crisp geometric shape (line / rectangle / rounded-rect / ellipse / arrow) on
 * demand. It is distinct from the canvas's built-in auto-snap-on-draw-end: this
 * is an EXPLICIT, user-triggered convert that goes through the plugin framework
 * so it can be toggled on/off in Settings → Plugins / the Plugin Store.
 *
 * Implementations must be pure geometry — no ML, no network, no native code.
 */
interface ShapeFromInkPlugin {
    /**
     * Convert a raw freehand [stroke] into a clean shape, or reject it
     * honestly. Runs on the caller's thread (the canvas calls it through
     * [PluginManager.withPluginAsync] so it can never block the UI). Must never
     * return null — the manager treats null as [PluginResult.Failure].
     */
    fun convertToShape(rawStroke: com.authorss81.noteflow.data.model.Stroke): ShapeFromInkOutcome
}

// ---------------------------------------------------------------------------
// Phase 26 — lightweight compile-time plugin serving interfaces.
// Pure-JVM cores + thin keyless-HTTP platform slices. Each plugin is a few KB
// in the base APK (safe under the hybrid model — see docs/plugin-architecture.md).
// ---------------------------------------------------------------------------

/** One definition entry for a looked-up word (part of speech + definition). */
data class DictionaryDefinition(
    val partOfSpeech: String?,
    val definition: String
)

/** Outcome of a dictionary lookup. [source] states where the result came from. */
data class DictionaryLookup(
    val word: String,
    val phonetic: String?,
    val definitions: List<DictionaryDefinition>,
    /** "online" (dictionaryapi.dev) or "offline" (bundled word list). */
    val source: String
)

/** Outcome of a dictionary request. */
sealed class DictionaryOutcome {
    data class Success(val lookup: DictionaryLookup) : DictionaryOutcome()
    /** The word was not found online or offline; [message] is user-facing. */
    data class NotFound(val message: String) : DictionaryOutcome()
    /** The request failed; [message] is a validated, user-facing reason. */
    data class Error(val message: String) : DictionaryOutcome()
}

/**
 * Serving interface for the [PluginCapability.Dictionary] capability.
 *
 * Keyless [dictionaryapi.dev] JSON with an honest OFFLINE fallback to a small
 * bundled word list, so a lookup genuinely works without a network (the result
 * is labelled with its source). Network runs on `Dispatchers.IO` and is strictly
 * user-initiated. Never throws into the caller.
 */
interface DictionaryPlugin {
    suspend fun lookupWord(word: String): DictionaryOutcome
}

/** A single weather forecast period (daily minimum/maximum + conditions). */
data class WeatherSnapshot(
    val date: String,
    val city: String,
    val tempMinC: Double,
    val tempMaxC: Double,
    val weatherCode: Int,
    val weatherDescription: String,
    val windSpeedKmh: Double,
    /** When false, [date]/conditions were derived from the fixed default city. */
    val sourceNote: String
)

/** Outcome of a weather request. */
sealed class WeatherOutcome {
    data class Success(val snapshot: WeatherSnapshot) : WeatherOutcome()
    /** The forecast could not be fetched; [message] is user-facing. */
    data class Error(val message: String) : WeatherOutcome()
}

/**
 * Serving interface for the [PluginCapability.Weather] capability.
 *
 * Keyless [Open-Meteo] forecast (no GPS, no API key). The location is the
 * fixed default city or coarse lat/lon from the plugin's namespaced settings —
 * NEVER the device's location. Network runs on `Dispatchers.IO` and is strictly
 * user-initiated; offline yields a clear [WeatherOutcome.Error].
 */
interface WeatherPlugin {
    suspend fun currentWeather(): WeatherOutcome
}

/** Outcome of a unit-conversion request. */
sealed class UnitConversionOutcome {
    /** [text] is the human-readable result, e.g. "2 km = 1.2427 mi". */
    data class Success(val text: String) : UnitConversionOutcome()
    /** The query could not be parsed/converted; [message] is user-facing. */
    data class Error(val message: String) : UnitConversionOutcome()
}

/**
 * Serving interface for the [PluginCapability.UnitConversion] capability.
 *
 * PURE JVM, fully offline, zero dependencies: "2 km to mi" → "2 km = 1.2427 mi".
 * Supports length, mass, temperature and basic (fixed reference-rate) currency
 * conversion. The conversion matrix lives in the plugin package and is unit-tested.
 */
interface UnitConverterPlugin {
    fun convert(query: String): UnitConversionOutcome
}

/** The two structural styles the outline generator can produce. */
enum class OutlineStyle { OUTLINE, CHECKLIST }

/** Outcome of an outline/checklist generation request. */
sealed class OutlineOutcome {
    /** [text] is the generated Markdown (headings, nested bullets or checkboxes). */
    data class Success(val text: String) : OutlineOutcome()
    /** The input had nothing to structure; [message] is user-facing. */
    data class Error(val message: String) : OutlineOutcome()
}

/**
 * Serving interface for the [PluginCapability.OutlineGenerator] capability.
 *
 * PURE Kotlin: from the current selection/note it produces a structured outline
 * or a checkbox checklist. Grouping/indent logic is pure-JVM unit-tested.
 */
interface OutlineGeneratorPlugin {
    fun generateOutline(text: String, style: OutlineStyle): OutlineOutcome
}

/** Outcome of a citation-formatting request. */
sealed class CitationOutcome {
    /** [markdown] is the clean `[title](url)` link; [titleFetched] tells how. */
    data class Success(val markdown: String, val titleFetched: Boolean) : CitationOutcome()
    /** The input was unusable; [message] is user-facing. */
    data class Error(val message: String) : CitationOutcome()
}

/**
 * Serving interface for the [PluginCapability.CitationFormatter] capability.
 *
 * Formats a pasted URL (and optional title) into a clean Markdown
 * `[title](url)` link. When no title is supplied the plugin fetches the page's
 * `<title>` over HTTPS and, on any network failure, honestly falls back to a
 * host-derived label (never silently wrong). Network runs on `Dispatchers.IO`,
 * strictly user-initiated.
 */
interface CitationPlugin {
    suspend fun formatCitation(url: String, title: String?): CitationOutcome
}

/**
 * Serving interface for the [PluginCapability.ScreenshotNote] capability.
 *
 * Captures the current canvas/note as an image and stores it as an image note —
 * optionally OCR'ing it via the EXISTING OCR plugin path so the note is
 * text-searchable. The decision/metadata logic is PURE JVM (unit-tested); the
 * capture implementation REUSES the existing annotated-page export path
 * (ImportExportService) for rendering — nothing is duplicated.
 */
interface ScreenshotNotePlugin {
    /** Decide flow (image-only vs OCR, naming, metadata). PURE JVM. */
    fun planCapture(
        capturedAtMillis: Long,
        shouldOcr: Boolean,
        ocrPluginAvailable: Boolean
    ): ScreenshotCapturePlan

    /**
     * Render + persist the current page. [context] nullable for JVM tests;
     * production always passes a real `Context`.
     */
    suspend fun captureAnnotatedPage(
        context: Context?,
        pageTitle: String,
        strokes: List<com.authorss81.noteflow.data.model.Stroke>,
        layers: List<com.authorss81.noteflow.data.model.LayerEntity>,
        stickyNotes: List<com.authorss81.noteflow.data.model.CanvasStickyNote>,
        mediaEmbeds: List<com.authorss81.noteflow.data.model.CanvasMediaEmbed>,
        bgBitmap: android.graphics.Bitmap?,
        template: String,
        pageIndex: Int,
        shouldOcr: Boolean,
        ocrPluginAvailable: Boolean
    ): ScreenshotCaptureOutcome
}