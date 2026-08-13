package com.authorss81.noteflow.plugins

import android.content.Context

/**
 * Diagnostics surface for the plugin settings screen — per-plugin derived state,
 * version, last invocation outcome, and a "test now" self-check. Never throws
 * and never exposes plugin content: summaries are state/success/failure labels
 * plus exception class names, nothing else.
 */
class PluginDiagnostics(
    private val registry: PluginRegistry,
    private val manager: PluginManager
) {

    /**
     * Per-plugin diagnostic entry combining the freshly-derived lifecycle state
     * with the most recent invocation outcome.
     */
    data class Entry(
        val plugin: NoteflowPlugin,
        val state: PluginStateInfo,
        val lastInvocation: PluginInvocationRecord?
    )

    /** Fresh snapshot for every installed plugin (including rejected ones). */
    fun snapshot(context: Context?): List<Entry> {
        val states = registry.resolve(context)
        return registry.allPlugins.map { p ->
            Entry(p, states[p.id] ?: fallbackState(p), manager.lastInvocation(p.id))
        }
    }

    /** "Test now": run the plugin's self-check under the routing guards. */
    fun testNow(pluginId: String, context: Context?): PluginCheckResult =
        manager.selfCheck(pluginId, context)

    private fun fallbackState(p: NoteflowPlugin): PluginStateInfo = PluginStateInfo(
        p.id, p.name, PluginLifecycleState.REJECTED,
        "not resolvable", p.version, enabled = false,
        availableOnDevice = false, depsResolved = false, conflictWinnerId = null
    )
}