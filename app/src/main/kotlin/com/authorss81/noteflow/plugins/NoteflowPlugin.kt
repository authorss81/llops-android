package com.authorss81.noteflow.plugins

import android.content.Context

/**
 * A single plugin installed into InkFlow.
 *
 * Plugins are registered **at compile time** via [PluginRegistry.defaultPlugins]
 * — there is deliberately NO dynamic (runtime-loaded APK) plugin loading. To add
 * a new plugin, implement this interface, register it in the registry, and the
 * UI/settings/manager wiring appears automatically.
 *
 * The lifecycle:
 * - [isAvailable] gates the plugin on the current device/context (e.g. a future
 *   GPU-dependent plugin checks AGSL support here). Checked at route time and
 *   surfaced as "Unavailable" in the plugin settings screen.
 * - [onEnable] is invoked once per process the first time the user flips the
 *   plugin on. It must be cheap and idempotent (warm caches, acquire handles).
 *   Plugins are opt-in: a freshly installed plugin is DISABLED until the user
 *   enables it in Settings → Plugins.
 *
 * To actually serve a capability, a plugin must ALSO implement the capability's
 * serving interface (e.g. [TextTransformPlugin]) so the framework can invoke it
 * without reflection. See docs/PLUGINS.md for the full integration guide.
 *
 * @param context nullable only to keep the framework JVM-unit-testable without
 *   Robolectric — production code always passes a real Context.
 */
interface NoteflowPlugin {
    /** Globally-unique plugin identifier (reverse-DNS). */
    val id: String

    /** User-facing name shown in Settings → Plugins. */
    val name: String

    /** One-line user-facing description of what the plugin does. */
    val description: String

    /** Semantic version of the plugin. */
    val version: String

    /** The capabilities this plugin can serve. */
    val capabilities: Set<PluginCapability>

    /** Whether this plugin can run on the current device/context. */
    fun isAvailable(context: Context?): Boolean

    /** Called once the first time the user enables this plugin. Cheap + idempotent. */
    fun onEnable(context: Context?)
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
