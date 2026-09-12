package com.authorss81.noteflow.services

import com.authorss81.noteflow.plugins.CaseChangePlugin

/**
 * Phase 270: install defaults for the plugin store's install lifecycle.
 *
 * Built-in plugins are INSTALLED by default (absent `plugin_uninstalled_<id>`
 * key = installed — backward compatible, no migration for existing vaults).
 * OPTIONAL bundled store plugins (compiled in, "Not downloaded" until the user
 * taps Download) are NOT installed by default: on a fresh install there is no
 * persisted key, and the absence must read as "not downloaded" instead of
 * "Available — off".
 *
 * Once the user installs or deletes an optional plugin the explicit persisted
 * key exists, and the key — not this default — decides. Pure JVM (unit-tested
 * without Robolectric).
 */
object PluginInstallDefaults {

    /**
     * Optional bundled plugin ids that default to NOT installed. Built from the
     * plugins' own canonical ids (single source of truth) — a new optional
     * plugin must add its id here or it regresses to installed-by-default.
     */
    val OPTIONAL_NOT_INSTALLED_BY_DEFAULT: Set<String> = setOf(
        CaseChangePlugin.PLUGIN_ID
    )

    /** Default install state for [pluginId] when no persisted key exists. */
    fun defaultInstalled(
        pluginId: String,
        optionalIds: Set<String> = OPTIONAL_NOT_INSTALLED_BY_DEFAULT
    ): Boolean = pluginId !in optionalIds

    /**
     * Resolve the effective install state: an existing persisted key wins
     * ([storedUninstalled] is the `plugin_uninstalled_<id>` value); otherwise
     * the [defaultInstalled] default applies.
     */
    fun resolveInstalled(
        pluginId: String,
        keyExists: Boolean,
        storedUninstalled: Boolean,
        optionalIds: Set<String> = OPTIONAL_NOT_INSTALLED_BY_DEFAULT
    ): Boolean =
        if (!keyExists) defaultInstalled(pluginId, optionalIds) else !storedUninstalled
}
