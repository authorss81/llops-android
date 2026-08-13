package com.authorss81.noteflow.plugins.websearch

import com.authorss81.noteflow.plugins.PluginAvailability

/**
 * Pure, JVM-testable availability gate for the Web Search plugin.
 *
 * The plugin's `availability(context)` reduces the Android checks (INTERNET
 * permission held, an active network with INTERNET capability) to this pure
 * function so the "offline — check connection" behaviour is unit-testable
 * without a Context.
 */
object WebSearchAvailability {

    /**
     * @param internetPermissionGranted whether INTERNET is granted (a normal
     *   permission, granted at install — checked so a revoked/denied one is
     *   never silently ignored).
     * @param networkAvailable whether an active network with INTERNET
     *   capability exists right now.
     */
    fun evaluate(
        internetPermissionGranted: Boolean,
        networkAvailable: Boolean
    ): PluginAvailability = when {
        !internetPermissionGranted ->
            PluginAvailability.Unavailable("Internet access is not granted — enable it in app permissions.")
        !networkAvailable ->
            PluginAvailability.Unavailable("Offline — check your connection.")
        else -> PluginAvailability.Ok
    }
}
