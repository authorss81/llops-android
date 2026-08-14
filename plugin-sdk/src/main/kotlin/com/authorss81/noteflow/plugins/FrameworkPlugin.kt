package com.authorss81.noteflow.plugins

import android.content.Context
import java.io.File

/**
 * The result of asking a plugin "can you run right now?". Tri-state so the
 * registry can distinguish a definite NO ([Unavailable], with a reason) from
 * "can't tell yet" ([Unknown], e.g. no `Context` to check against).
 *
 * PHASE 29: this type (and [NoteflowPlugin], [AssistantPlugin]) moved into the
 * `plugin-sdk` module so downloadable plugin artifacts are compiled against the
 * exact interfaces the base app resolves through its classloader.
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
 * A single plugin installed into InkFlow — the framework contract BOTH
 * compile-time and downloadable plugins implement.
 *
 * ## Identity & versioning
 *
 * [manifest] is the single source of truth: [id], [name], [description],
 * [version] and [capabilities] are derived from it. Bump `manifest.version` for
 * any behavior/settings change — a plugin runs its own settings migration on the
 * version bump.
 *
 * ## Lifecycle
 *
 * - [availability] — device/context gate (AGSL support, permission held, API
 *   level, presence of a configured API key…). Re-checked on every registry
 *   resolution.
 * - [onEnable] — invoked when the plugin becomes enabled: on first opt-in,
 *   on a disable→re-enable cycle in the same process, and at cold start via
 *   [PluginRegistry.onProcessStart] (once per process). Cheap and idempotent.
 * - [onDisable] — invoked when the user turns the plugin off AND when the
 *   deterministic capability-conflict arbitration demotes it to a loser.
 * - [onConfigChanged] — invoked when the user changes a `plugins.<id>.<key>`
 *   setting.
 * - [selfCheck] — deep self-test used by the "Test now" diagnostics action;
 *   defaults to [availability].
 *
 * To actually serve a capability, a plugin must ALSO implement the capability's
 * serving interface (e.g. [AssistantPlugin]).
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
     * files (store "Delete" = delete = gone, assets wiped). Default no-op;
     * plugins that download models override to remove them.
     */
    fun deleteDownloadedAssets(context: Context?) {}
}

/** Outcome of an on-device LLM request (summarize / action items / Q&A / tags). */
sealed class AssistantOutcome {
    data class Success(val text: String) : AssistantOutcome()
    data class ModelNotReady(val message: String) : AssistantOutcome()
    data class Error(val message: String) : AssistantOutcome()
}

/**
 * Serving interface for the [PluginCapability.Assistant] (offline local LLM)
 * capability — ALSO implemented by the Phase-29 Cloud AI plugin (serving the
 * [PluginCapability.CloudAI] capability) so the same task vocabulary
 * (summarize / action items / Q&A / tags) is served by both engines. The two
 * plugins are deliberately DIFFERENT capabilities: the caller always knows
 * whether it talked to the offline engine or the cloud engine.
 */
interface AssistantPlugin {
    /** True once the user-downloaded model file exists on-device. */
    fun isModelDownloaded(context: Context?): Boolean

    /** The downloaded model file, or null when not downloaded. */
    fun modelFile(context: Context?): File?

    /** Expected on-disk size of the model (used for the free-space guard). */
    fun expectedModelSizeBytes(): Long

    /** User-facing reason the assistant can't run here, or null when eligible. */
    fun unavailableReason(context: Context?): String?

    /** Download the model with progress [0f..1f]. User-initiated, guarded. */
    suspend fun downloadModel(context: Context?, onProgress: (Float) -> Unit): AssistantOutcome

    /** All assistant tasks. */
    suspend fun summarize(context: Context?, noteText: String): AssistantOutcome
    suspend fun extractActionItems(context: Context?, noteText: String): AssistantOutcome
    suspend fun answerQuestion(context: Context?, noteText: String, question: String): AssistantOutcome
    suspend fun suggestTags(context: Context?, noteText: String): AssistantOutcome

    /** Release the loaded model (on disable/teardown). */
    fun close()
}
