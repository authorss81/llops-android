package com.authorss81.noteflow.plugins

import android.content.Context
import com.authorss81.noteflow.services.PluginInvocationJournal
import kotlinx.coroutines.withContext

/**
 * Failure categories for a plugin capability request. The user-facing message
 * carried alongside carries the detail; the reason is machine-readable so the
 * UI can style/route it (and so tests can assert the WHY without string-matching).
 */
enum class PluginFailureReason {
    /** No installed (valid) plugin declares the capability. */
    NO_PLUGIN_INSTALLED,

    /** Declared, but no plugin is opted in. */
    NONE_ENABLED,

    /** Enabled plugin(s) exist but none is usable on this device. */
    DEVICE_UNAVAILABLE,

    /** An enabled plugin has unmet dependencies / missing capability requirements. */
    DEPENDENCY_UNMET,

    /** An enabled plugin was disabled by capability-conflict arbitration. */
    CONFLICT,

    /** The plugin threw or returned null/unusable output. */
    PLUGIN_ERROR,

    /** The plugin is enabled but its device availability is not yet verified. */
    NOT_VERIFIED
}

/**
 * Outcome of a capability request. [Success] carries the plugin's value;
 * [Failure] and [Unavailable] carry a user-facing message + a machine-readable
 * [PluginFailureReason] so the UI can fail loudly instead of crashing or
 * silently degrading.
 */
sealed class PluginResult<out T> {
    data class Success<T>(val value: T) : PluginResult<T>()

    /** The request could not be served (no plugin / none enabled / plugin error). */
    data class Failure(val reason: PluginFailureReason, val message: String) : PluginResult<Nothing>()

    /** The capability exists and plugins are enabled, but none is usable right now. */
    data class Unavailable(val reason: PluginFailureReason, val message: String) : PluginResult<Nothing>()
}

/** Outcome of a plugin self-check ("Test now" in Settings → Plugins). */
sealed class PluginCheckResult {
    data class Success(val pluginId: String) : PluginCheckResult()
    data class Failure(val pluginId: String, val reason: String) : PluginCheckResult()
}

/** Diagnostic record of the most recent invocation of a plugin. */
data class PluginInvocationRecord(
    val atMillis: Long,
    val ok: Boolean,
    val summary: String
)

/**
 * Routes capability requests to the right plugin.
 *
 * Routing is **guarded**: a plugin that throws, returns null, or hangs the
 * caller is contained — the manager catches `Throwable` (incl. `Error` such as
 * an `AssertionError` from a buggy `require/check`), logs only ids/names and
 * exception CLASS names (never content), records a diagnostic, and returns a
 * typed [PluginResult] instead of propagating the failure.
 *
 * Routing rules (checked in order):
 * 1. NO installed plugin declares the capability → Failure(NO_PLUGIN_INSTALLED).
 * 2. Declared, but none opted-in → Failure(NONE_ENABLED).
 * 3. Opted-in, but derived state is not AVAILABLE (device gate / dependency /
 *    conflict) → Unavailable with the specific reason.
 * 4. Otherwise [action] runs guarded; any `Throwable` (incl. RuntimeException)
 *    becomes Failure(PLUGIN_ERROR) naming the plugin and exception class.
 *
 * The framework never hardcodes which concrete plugin serves a capability — it
 * resolves by capability + derived state and hands back the plugin instance.
 */
class PluginManager(
    private val registry: PluginRegistry,
    private val logger: PluginLogger = PluginLogger.NoOp,
    private val journal: PluginInvocationJournal.Store = PluginInvocationJournal.NoOpStore
) {

    private val lastResults = java.util.concurrent.ConcurrentHashMap<String, PluginInvocationRecord>()

    // Review-fix (phase-173): the journal is a read-modify-write (read → record →
    // write) which must not race between concurrent invocations (withPluginAsync
    // runs on Dispatchers.Default). One serializing lock makes "records every
    // invocation" hold under concurrency; the critical section is tiny.
    private val journalLock = Any()

    /** Most recent invocation outcome for diagnostics (null = never invoked). */
    fun lastInvocation(pluginId: String): PluginInvocationRecord? = lastResults[pluginId]

    /**
     * Invoke [action] against the single plugin currently serving [capability],
     * returning a typed [PluginResult]. A failing request never throws.
     *
     * [action] runs on the caller's thread. Use [withPluginAsync] when the work
     * must not block the main thread.
     */
    fun <T> withPlugin(
        capability: PluginCapability,
        context: Context?,
        action: (NoteflowPlugin) -> T
    ): PluginResult<T> = when (val resolved = resolvePlugin(capability, context)) {
        is Resolution.Success -> invokeGuarded(resolved.plugin, capability) { action(resolved.plugin) }
        is Resolution.Rejected -> resolved.result
    }

    /**
     * [withPlugin] variant that runs the plugin work on [kotlinx.coroutines.Dispatchers.Default]
     * so a slow/hung plugin can never block the main thread. The plugin action may
     * be a suspension function (e.g. a network/model call that itself hops to
     * `Dispatchers.IO`), so the caller's coroutine is suspended for the whole
     * request and the UI stays responsive. A throwing plugin is contained exactly
     * like [withPlugin].
     */
    suspend fun <T> withPluginAsync(
        capability: PluginCapability,
        context: Context?,
        action: suspend (NoteflowPlugin) -> T
    ): PluginResult<T> = withContext(kotlinx.coroutines.Dispatchers.Default) {
        when (val resolved = resolvePlugin(capability, context)) {
            is Resolution.Success -> invokeGuardedSuspend(resolved.plugin, capability) { action(resolved.plugin) }
            is Resolution.Rejected -> resolved.result
        }
    }

    /**
     * Run a plugin's [NoteflowPlugin.selfCheck] under the same guards as a real
     * request and record it for diagnostics ("Test now"). Never throws.
     */
    fun selfCheck(pluginId: String, context: Context?): PluginCheckResult {
        val plugin = registry.allPlugins.firstOrNull { it.id == pluginId }
            ?: return PluginCheckResult.Failure(pluginId, "unknown plugin id '$pluginId'")
        if (registry.isRejected(pluginId)) {
            val errors = registry.validationErrorsOf(pluginId)?.joinToString("; ") ?: "rejected"
            return PluginCheckResult.Failure(pluginId, "plugin rejected: $errors")
        }
        return try {
            when (val availability = plugin.selfCheck(context)) {
                PluginAvailability.Ok -> {
                    record(pluginId, "self-check", ok = true, summary = "Self-check passed")
                    PluginCheckResult.Success(pluginId)
                }
                is PluginAvailability.Unavailable -> {
                    record(pluginId, "self-check", ok = false, summary = "Self-check failed: ${availability.reason}")
                    PluginCheckResult.Failure(pluginId, availability.reason)
                }
                PluginAvailability.Unknown -> {
                    record(pluginId, "self-check", ok = false, summary = "Self-check inconclusive (no context)")
                    PluginCheckResult.Failure(pluginId, "Self-check inconclusive — try again with a device context.")
                }
            }
        } catch (e: Throwable) {
            val detail = e::class.java.simpleName
            record(plugin.id, "self-check", ok = false, summary = "Self-check threw $detail")
            logger.error(pluginId, plugin.name, "selfCheck threw $detail")
            PluginCheckResult.Failure(pluginId, "Self-check threw $detail. See plugin diagnostics.")
        }
    }

    // ---- internals ---------------------------------------------------------

    private sealed class Resolution {
        class Success(val plugin: NoteflowPlugin) : Resolution()
        class Rejected(val result: PluginResult<Nothing>) : Resolution()
    }

    /**
     * Apply the routing rules (installed → enabled → available) and return the
     * winning plugin, or a rejected [PluginResult] explaining why no plugin can
     * serve the capability right now. Shared by the sync and async entry points.
     */
    private fun resolvePlugin(
        capability: PluginCapability,
        context: Context?
    ): Resolution {
        val declarers = registry.pluginsForCapability(capability)
        if (declarers.isEmpty()) {
            return Resolution.Rejected(
                PluginResult.Failure(
                    PluginFailureReason.NO_PLUGIN_INSTALLED,
                    "No plugin is installed for '${capability.label}'. " +
                        "Check Settings \u2192 Plugins for what's available."
                )
            )
        }
        val states = registry.resolve(context)
        val optedIn = declarers.filter { states[it.id]?.enabled == true }
        if (optedIn.isEmpty()) {
            return Resolution.Rejected(
                PluginResult.Failure(
                    PluginFailureReason.NONE_ENABLED,
                    "No plugin is enabled for '${capability.label}' — " +
                        "enable one in Settings \u2192 Plugins, then try again."
                )
            )
        }
        val winner = optedIn.firstOrNull { states[it.id]?.state == PluginLifecycleState.AVAILABLE }
            ?: return Resolution.Rejected(
                unavailableFor(optedIn.first(), states[optedIn.first().id], capability)
            )
        return Resolution.Success(winner)
    }

    private fun <T> invokeGuarded(plugin: NoteflowPlugin, capability: PluginCapability, action: () -> T): PluginResult<T> {
        return try {
            val value = action()
            if (value == null) {
                record(plugin.id, capability.key, ok = false, summary = "Returned no result")
                PluginResult.Failure(
                    PluginFailureReason.PLUGIN_ERROR,
                    "Plugin '${plugin.name}' returned no result."
                )
            } else {
                record(plugin.id, capability.key, ok = true, summary = "Success")
                PluginResult.Success(value)
            }
        } catch (e: Throwable) {
            val detail = e::class.java.simpleName
            record(plugin.id, capability.key, ok = false, summary = "Threw $detail")
            logger.error(plugin.id, plugin.name, "invocation threw $detail")
            PluginResult.Failure(
                PluginFailureReason.PLUGIN_ERROR,
                "Plugin '${plugin.name}' failed ($detail). Check Settings \u2192 Plugins for diagnostics."
            )
        }
    }

    /** Suspend sibling of [invokeGuarded] for plugin actions that perform their own IO. */
    private suspend fun <T> invokeGuardedSuspend(plugin: NoteflowPlugin, capability: PluginCapability, action: suspend () -> T): PluginResult<T> {
        return try {
            val value = action()
            if (value == null) {
                record(plugin.id, capability.key, ok = false, summary = "Returned no result")
                PluginResult.Failure(
                    PluginFailureReason.PLUGIN_ERROR,
                    "Plugin '${plugin.name}' returned no result."
                )
            } else {
                record(plugin.id, capability.key, ok = true, summary = "Success")
                PluginResult.Success(value)
            }
        } catch (e: Throwable) {
            val detail = e::class.java.simpleName
            record(plugin.id, capability.key, ok = false, summary = "Threw $detail")
            logger.error(plugin.id, plugin.name, "invocation threw $detail")
            PluginResult.Failure(
                PluginFailureReason.PLUGIN_ERROR,
                "Plugin '${plugin.name}' failed ($detail). Check Settings \u2192 Plugins for diagnostics."
            )
        }
    }

    private fun unavailableFor(
        plugin: NoteflowPlugin,
        info: PluginStateInfo?,
        capability: PluginCapability
    ): PluginResult<Nothing> {
        val state = info?.state
        val reason: PluginFailureReason
        val message: String
        when (state) {
            PluginLifecycleState.UNAVAILABLE -> {
                val depends = info != null && !info.depsResolved
                reason = if (depends) PluginFailureReason.DEPENDENCY_UNMET else PluginFailureReason.DEVICE_UNAVAILABLE
                message = if (depends) {
                    "Plugin '${plugin.name}' has unmet requirements: ${info?.reason ?: "unknown"}"
                } else {
                    "Plugin '${plugin.name}' is unavailable on this device" +
                        (info?.reason?.let { ": $it" } ?: ".")
                }
            }
            PluginLifecycleState.DISABLED -> {
                reason = PluginFailureReason.CONFLICT
                message = "Plugin '${plugin.name}' is disabled: ${info?.reason ?: "conflict arbitration"}"
            }
            PluginLifecycleState.ENABLED -> {
                reason = PluginFailureReason.NOT_VERIFIED
                message = "Plugin '${plugin.name}' is enabled but its availability is not yet verified. Try again shortly."
            }
            else -> {
                reason = PluginFailureReason.DEVICE_UNAVAILABLE
                message = "No enabled plugin can serve '${capability.label}' right now."
            }
        }
        return PluginResult.Unavailable(reason, message)
    }

    /**
     * Record an invocation for BOTH diagnostic surfaces:
     * - the in-memory last-invocation record ([lastInvocation]);
     * - the persisted, bounded, scrubbed [PluginInvocationJournal] (phase-173
     *   feature 2). The journal entry's capability key is the fixed framework
     *   key (or "self-check"); summaries are fixed labels + exception class
     *   names — the journal policy scrubs/bounds them on both write and render.
     */
    private fun record(pluginId: String, capabilityKey: String, ok: Boolean, summary: String) {
        val now = System.currentTimeMillis()
        lastResults[pluginId] = PluginInvocationRecord(now, ok, summary)
        synchronized(journalLock) {
            journal.write(
                pluginId,
                PluginInvocationJournal.record(
                    journal.read(pluginId),
                    PluginInvocationJournal.Entry(now, capabilityKey, ok, summary)
                )
            )
        }
    }
}