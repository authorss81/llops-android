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