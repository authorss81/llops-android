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