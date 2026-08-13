package com.authorss81.noteflow.plugins

import android.content.Context

/**
 * Outcome of a capability request. [Success] carries the plugin's value;
 * [Failure] carries a user-facing message so the UI can fail loudly instead of
 * crashing or silently degrading.
 */
sealed class PluginResult<out T> {
    data class Success<T>(val value: T) : PluginResult<T>()
    data class Failure(val message: String) : PluginResult<Nothing>()
}

/**
 * Routes capability requests to the right plugin.
 *
 * The routing rules (checked in order):
 * 1. NO installed plugin declares the capability → Failure.
 * 2. Declared, but none opted-in → Failure ("enable a plugin").
 * 3. Opted-in, but none [NoteflowPlugin.isAvailable] on this device → Failure.
 * 4. Otherwise, [action] is invoked with the winning plugin; a thrown exception
 *    is caught and turned into a Failure (with the plugin's name).
 *
 * The framework never hardcodes which concrete plugin serves a capability — it
 * resolves by capability + enable state and hands back the plugin instance. The
 * caller then uses the capability's serving interface (e.g. [TextTransformPlugin])
 * to do the actual work.
 */
class PluginManager(private val registry: PluginRegistry) {

    /**
     * Invoke [action] against the enabled, device-available plugin serving
     * [capability], returning a typed [PluginResult]. A failing request never
     * throws — it returns [PluginResult.Failure] with a clear message.
     */
    fun <T> withPlugin(
        capability: PluginCapability,
        context: Context?,
        action: (NoteflowPlugin) -> T
    ): PluginResult<T> {
        val declarers = registry.pluginsForCapability(capability)
        if (declarers.isEmpty()) {
            return PluginResult.Failure(
                "No plugin is installed for '${capability.label}'. " +
                    "Check Settings \u2192 Plugins for what's available."
            )
        }

        val optedIn = declarers.filter { registry.isEnabled(it.id) }
        if (optedIn.isEmpty()) {
            return PluginResult.Failure(
                "No plugin is enabled for '${capability.label}' — " +
                    "enable one in Settings \u2192 Plugins, then try again."
            )
        }

        val usable = optedIn.firstOrNull { it.isAvailable(context) }
        if (usable == null) {
            val name = optedIn.first().name
            return PluginResult.Failure(
                "Plugin '$name' is unavailable on this device."
            )
        }

        return try {
            PluginResult.Success(action(usable))
        } catch (e: Exception) {
            PluginResult.Failure("Plugin '${usable.name}' failed: ${e.message ?: "unknown error"}")
        }
    }
}
