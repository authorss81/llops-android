package com.authorss81.noteflow.plugins

/**
 * Logging abstraction for the plugin framework.
 *
 * The framework must log lifecycle events and failures WITHOUT ever logging
 * plugin content, keys, or decrypted note data — so the log calls only ever
 * receive plugin ids, names, and exception class names. The abstraction keeps
 * the registry/manager pure JVM (unit-testable with [NoOp]).
 */
interface PluginLogger {

    /** A lifecycle event: enable, disable, config change (id + name only). */
    fun lifecycle(event: String, pluginId: String, pluginName: String)

    /**
     * A plugin failure. [detail] must never contain plugin content — callers
     * pass exception class names, never messages/stack traces that could embed
     * transformed note text.
     */
    fun error(pluginId: String, pluginName: String, detail: String)

    /** No-op logger used by default so JVM unit tests never touch android.util.Log. */
    object NoOp : PluginLogger {
        override fun lifecycle(event: String, pluginId: String, pluginName: String) {}
        override fun error(pluginId: String, pluginName: String, detail: String) {}
    }
}

/**
 * Production logger that writes to logcat under a single tag. Only ids, names
 * and exception class names are ever logged (see [PluginLogger] contract).
 */
class AndroidPluginLogger : PluginLogger {
    override fun lifecycle(event: String, pluginId: String, pluginName: String) {
        android.util.Log.d(TAG, "lifecycle event=$event plugin=$pluginName id=$pluginId")
    }

    override fun error(pluginId: String, pluginName: String, detail: String) {
        android.util.Log.e(TAG, "plugin failure plugin=$pluginName id=$pluginId detail=$detail")
    }

    private companion object {
        const val TAG = "InkFlowPlugins"
    }
}