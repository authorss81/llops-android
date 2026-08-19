package com.authorss81.noteflow.services

import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.store.PluginStoreEntry

/**
 * Phase-157 (plugin ecosystem & store UX): the capability → plugin mapping
 * table. Pure JVM — the "What can plugins do?" view of the Plugin Store.
 *
 * For every known [PluginCapability] it lists the catalog plugins that declare
 * it, split into INSTALLED (downloaded — can be enabled now) vs AVAILABLE
 * (in the store but not downloaded yet), and classifies overall coverage:
 *
 * - [Coverage.INSTALLED] — at least one INSTALLED plugin serves this
 *   capability, so a request can genuinely be served today (after opt-in).
 * - [Coverage.AVAILABLE_ON_STORE] — no installed plugin, but the store offers
 *   one: the capability is a Download away and honestly labelled "available in
 *   the store", not silently absent.
 * - [Coverage.UNSERVED] — NO catalog plugin declares it (installed or
 *   available): requesting it fails loudly with "no plugin installed" — this
 *   table surfaces that BEFORE the request instead of after it.
 *
 * This is the honest visibility layer referenced by phase-157 feature 1: the
 * still-unserved capabilities (today: FileTransfer, and Assistant until the
 * downloadable LLM plugin is installed) become visible in the store instead of
 * only discovered through a runtime failure.
 *
 * Deterministic: rows follow [PluginCapability.ALL] order; plugin lists follow
 * catalog order (bundled entries first, then persisted remote entries).
 */
object PluginCapabilityDirectory {

    /** One plugin's entry in a capability row. */
    data class PluginRef(
        val pluginId: String,
        val name: String
    )

    /** Overall coverage of a capability across the store catalog. */
    enum class Coverage { INSTALLED, AVAILABLE_ON_STORE, UNSERVED }

    /** One row of the capability browser: capability + its serving plugins. */
    data class CapabilityRow(
        val capability: PluginCapability,
        val installedPlugins: List<PluginRef>,
        val availablePlugins: List<PluginRef>,
        val coverage: Coverage
    ) {
        /** "{label}" — the capability's fixed, user-facing name. */
        val label: String get() = capability.label

        /** Whether this capability is actually served right now (installed). */
        val isServed: Boolean get() = coverage == Coverage.INSTALLED
    }

    /**
     * Build the full capability table from the store catalog. [isInstalled]
     * decides whether a catalog entry is downloaded (the store row already
     * knows this); entries with a capability => they DECLARE it. Only
     * [PluginCapability.ALL] rows are produced — unknown "phantom" capabilities
     * never made it past registry validation and are not listed.
     */
    fun rows(
        entries: Collection<PluginStoreEntry>,
        isInstalled: (String) -> Boolean
    ): List<CapabilityRow> = PluginCapability.ALL.map { capability ->
        val installed = mutableListOf<PluginRef>()
        val available = mutableListOf<PluginRef>()
        entries.forEach { entry ->
            if (capability !in entry.capabilities) return@forEach
            val ref = PluginRef(entry.pluginId, if (entry.name.isNotBlank()) entry.name else entry.pluginId)
            if (isInstalled(entry.pluginId)) installed.add(ref) else available.add(ref)
        }
        val coverage = when {
            installed.isNotEmpty() -> Coverage.INSTALLED
            available.isNotEmpty() -> Coverage.AVAILABLE_ON_STORE
            else -> Coverage.UNSERVED
        }
        CapabilityRow(capability, installed, available, coverage)
    }

    /**
     * The distinct capabilities the store catalog actually offers, in
     * [PluginCapability.ALL] order. Used to build the compact per-capability
     * store filter chips (phase-157 feature 1 + the "Filters in the store by
     * capability" idea).
     */
    fun capabilitiesInStore(entries: Collection<PluginStoreEntry>): List<PluginCapability> =
        PluginCapability.ALL.filter { cap ->
            entries.any { cap in it.capabilities }
        }

    /** Human coverage chip of a row: compact, truthful, non-alarming. */
    fun coverageLabel(coverage: Coverage): String = when (coverage) {
        Coverage.INSTALLED -> "Installed"
        Coverage.AVAILABLE_ON_STORE -> "Available in store"
        Coverage.UNSERVED -> "No plugin yet"
    }

    /**
     * The bounded one-line summary of who serves this capability, or null when
     * nothing/nobody does (the UNSERVED row renders its own fixed copy). Names
     * come from plugin metadata (fixed at build time for bundled; manifest
     * validated before install for remote) and are bounded to [MAX_NAMES_PER_ROW]
     * with the count folded in so a hugely-populated row stays compact.
     */
    fun servingSummary(row: CapabilityRow): String? {
        val installed = row.installedPlugins
        val available = row.availablePlugins
        if (installed.isEmpty() && available.isEmpty()) return null
        return buildString {
            if (installed.isNotEmpty()) {
                append(summaryOf(installed, "installed"))
            }
            if (available.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(summaryOf(available, "download"))
            }
        }
    }

    private fun summaryOf(refs: List<PluginRef>, marker: String): String {
        val shown = refs.take(MAX_NAMES_PER_ROW).joinToString(", ") { it.name }
        val extra = if (refs.size > MAX_NAMES_PER_ROW) {
            " +${refs.size - MAX_NAMES_PER_ROW} more"
        } else {
            ""
        }
        return "$marker: $shown$extra"
    }

    /** Max plugin names shown per list before the "+N more" fold. */
    const val MAX_NAMES_PER_ROW = 3
}