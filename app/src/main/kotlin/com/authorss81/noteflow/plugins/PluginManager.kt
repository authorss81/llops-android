package com.authorss81.noteflow.plugins

import android.content.Context

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
    private val logger: PluginLogger = PluginLogger.NoOp
) {

    private val lastResults = java.util.concurrent.ConcurrentHashMap<String, PluginInvocationRecord>()

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
    ): PluginResult<T> {
        val declarers = registry.pluginsForCapability(capability)
        if (declarers.isEmpty()) {
            return PluginResult.Failure(
                PluginFailureReason.NO_PLUGIN_INSTALLED,
                "No plugin is installed for '${capability.label}'. " +
                    "Check Settings \u2192 Plugins for what's available."
            )
        }
        val states = registry.resolve(context)
        val optedIn = declarers.filter { states[it.id]?.enabled == true }
        if (optedIn.isEmpty()) {
            return PluginResult.Failure(
                PluginFailureReason.NONE_ENABLED,
                "No plugin is enabled for '${capability.label}' — " +
                    "enable one in Settings \u2192 Plugins, then try again."
            )
        }
        val winner = optedIn.firstOrNull { states[it.id]?.state == PluginLifecycleState.AVAILABLE }
        if (winner == null) {
            val first = optedIn.first()
            val info = states[first.id]
            return unavailableFor(first, info, capability)
        }
        return invokeGuarded(winner) { action(winner) }
    }

    /**
     * [withPlugin] variant that runs the plugin work on [kotlinx.coroutines.Dispatchers.Default]
     * so a slow/hung plugin can never block the main thread. The plugin action is
     * non-suspend, so a genuinely hung plugin still ties up ONE background worker
     * until it returns — but the UI stays responsive and the caller's coroutine is
     * simply suspended. A throwing plugin is contained exactly like [withPlugin].
     */
    suspend fun <T> withPluginAsync(
        capability: PluginCapability,
        context: Context?,
        action: (NoteflowPlugin) -> T
    ): PluginResult<T> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        withPlugin(capability, context, action)
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
                    record(pluginId, ok = true, summary = "Self-check passed")
                    PluginCheckResult.Success(pluginId)
                }
                is PluginAvailability.Unavailable -> {
                    record(pluginId, ok = false, summary = "Self-check failed: ${availability.reason}")
                    PluginCheckResult.Failure(pluginId, availability.reason)
                }
                PluginAvailability.Unknown -> {
                    record(pluginId, ok = false, summary = "Self-check inconclusive (no context)")
                    PluginCheckResult.Failure(pluginId, "Self-check inconclusive — try again with a device context.")
                }
            }
        } catch (e: Throwable) {
            val detail = e::class.java.simpleName
            record(plugin.id, ok = false, summary = "Self-check threw $detail")
            logger.error(pluginId, plugin.name, "selfCheck threw $detail")
            PluginCheckResult.Failure(pluginId, "Self-check threw $detail. See plugin diagnostics.")
        }
    }

    // ---- internals ---------------------------------------------------------

    private fun <T> invokeGuarded(plugin: NoteflowPlugin, action: () -> T): PluginResult<T> {
        return try {
            val value = action()
            if (value == null) {
                record(plugin.id, ok = false, summary = "Returned no result")
                PluginResult.Failure(
                    PluginFailureReason.PLUGIN_ERROR,
                    "Plugin '${plugin.name}' returned no result."
                )
            } else {
                record(plugin.id, ok = true, summary = "Success")
                PluginResult.Success(value)
            }
        } catch (e: Throwable) {
            val detail = e::class.java.simpleName
            record(plugin.id, ok = false, summary = "Threw $detail")
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

    private fun record(pluginId: String, ok: Boolean, summary: String) {
        lastResults[pluginId] = PluginInvocationRecord(System.currentTimeMillis(), ok, summary)
    }
}