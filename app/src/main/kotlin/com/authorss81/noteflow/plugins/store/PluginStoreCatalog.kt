package com.authorss81.noteflow.plugins.store

import com.authorss81.noteflow.plugins.AssistantPlugin
import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginPermission
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.runtime.PluginEntry
import com.authorss81.noteflow.plugins.runtime.PluginEntrySource
import com.authorss81.noteflow.plugins.runtime.PluginEntryStore
import com.authorss81.noteflow.plugins.runtime.PluginVersion

/**
 * One row the plugin store renders — the unified catalog [PluginEntry]
 * (Phase 22: bundled + downloadable covered by ONE type) plus the store's own
 * OPTIONAL flag.
 *
 * All familiar accessors delegate to [entry] so existing callers (UI, tests,
 * controller) keep working unchanged. `bundled`/`sourceLabel` mark the entry
 * as **bundled** (compiled into the APK) vs **remote** (downloadable,
 * signature-verified — Phase 23+).
 */
data class PluginStoreEntry(
    val entry: PluginEntry,
    val optional: Boolean
) {
    val pluginId: String get() = entry.id
    val name: String get() = entry.name
    val description: String get() = entry.description
    val version: PluginVersion get() = entry.version
    val capabilities: Set<PluginCapability> get() = entry.capabilities
    val category: String get() = entry.category
    val permissions: Set<PluginPermission> get() = entry.permissions
    val installSizeBytes: Long? get() = entry.installSizeBytes
    val bundled: Boolean get() = entry.isBundled
    val updateChannel: String get() = entry.updateChannel
    val downloadUrl: String? get() = entry.downloadUrl

    /** Store marker: "bundled" (compiled-in) vs "remote" (downloadable). */
    val sourceLabel: String get() = entry.source.label
}

/**
 * The plugin catalog (Phase 21, extended Phase 22).
 *
 * Bundled entries are built from the registry's COMPLETE compiled set — every
 * built-in plugin PLUS every optional store-only definition — so the store
 * degrades gracefully offline by construction and "Download" of a bundled entry
 * is an honest install of a compiled definition (see [PluginStoreController]).
 *
 * Phase 22: the catalog ALSO merges any persisted REMOTE (downloadable) entries
 * from the [PluginEntryStore]. Today no remote entries are seeded (Phase 23/24
 * add them), so every row is "bundled"; when a future phase saves a remote
 * entry the store automatically lists it with its downloadUrl/sha256/
 * pinnedCertHash and a "remote" marker — no catalog redesign needed.
 */
class PluginStoreCatalog(
    registry: PluginRegistry,
    private val entryStore: PluginEntryStore? = null
) {

    private val bundledEntries: List<PluginStoreEntry> = registry.compiledPlugins.map { p ->
        PluginStoreEntry(
            entry = PluginEntry(
                id = p.id,
                name = p.name,
                description = p.description,
                version = PluginVersion.from(p.version),
                capabilities = p.capabilities,
                category = categoryFor(p.capabilities),
                permissions = p.manifest.permissions,
                downloadUrl = null,
                installSizeBytes = sizeBytesFor(p),
                updateChannel = PluginEntry.DEFAULT_CHANNEL,
                sha256 = null,
                pinnedCertHash = null,
                source = PluginEntrySource.BUNDLED
            ),
            optional = !registry.isBuiltIn(p.id)
        )
    }

    private val bundledIds: Set<String> = bundledEntries.map { it.pluginId }.toSet()

    /** Every catalog entry — bundled definitions first, then persisted remote entries. */
    fun entries(): List<PluginStoreEntry> {
        val remotes = entryStore?.all().orEmpty()
            .filter { it.isDownloadable && it.id !in bundledIds }
            .map { PluginStoreEntry(entry = it, optional = false) }
        return bundledEntries + remotes
    }

    /** The catalog entry for [pluginId], or null when unknown. */
    fun entryFor(pluginId: String): PluginStoreEntry? =
        entries().firstOrNull { it.pluginId == pluginId }

    private fun sizeBytesFor(plugin: NoteflowPlugin): Long? =
        (plugin as? AssistantPlugin)?.expectedModelSizeBytes()

    private fun categoryFor(capabilities: Set<PluginCapability>): String {
        val primary = capabilities.firstOrNull()
        return when (primary) {
            PluginCapability.OCR, PluginCapability.ScreenshotNote -> "Vision"
            PluginCapability.TextTransform, PluginCapability.TextTools,
            PluginCapability.LanguageDetection,
            PluginCapability.OutlineGenerator, PluginCapability.UnitConversion -> "Text"
            PluginCapability.WebSearch, PluginCapability.WebCapture,
            PluginCapability.Weather, PluginCapability.CitationFormatter -> "Web"
            PluginCapability.Dictionary -> "Reference"
            PluginCapability.Export -> "Export"
            PluginCapability.ClipShare -> "Import"
            PluginCapability.Dictation, PluginCapability.ReadAloud -> "Voice"
            PluginCapability.Translation -> "Language"
            PluginCapability.Assistant -> "AI"
            PluginCapability.FileTransfer -> "Transfer"
            PluginCapability.ShapeFromInk -> "Canvas"
            else -> "Other"
        }
    }
}
