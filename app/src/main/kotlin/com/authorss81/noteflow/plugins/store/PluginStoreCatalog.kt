package com.authorss81.noteflow.plugins.store

import com.authorss81.noteflow.plugins.AssistantPlugin
import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginPermission
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.SemanticVersion

/**
 * One entry in the plugin store's catalog — the user-facing definition of a
 * downloadable/installable plugin.
 *
 * Phase 21 honesty: EVERY entry is bundled in the APK (compile-time rule — the
 * store never loads runtime bytecode). `bundled = true` for all. `optional`
 * marks the definitions that are NOT installed by default: they ship in the
 * catalog as "Not downloaded" and only become part of the active registry after
 * the user taps Download.
 */
data class PluginStoreEntry(
    val pluginId: String,
    val name: String,
    val description: String,
    val version: SemanticVersion,
    val capabilities: Set<PluginCapability>,
    val category: String,
    val bundled: Boolean,
    val optional: Boolean,
    /** Size of heavy model assets installed on download (assistant), else null. */
    val installSizeBytes: Long?,
    val permissions: Set<PluginPermission>
)

/**
 * The bundled plugin catalog (Phase 21).
 *
 * Built from the registry's COMPLETE compiled set — every built-in plugin PLUS
 * every optional store-only definition (compiled in the APK via the registry's
 * `optionalPluginFactories`). A real network catalog is intentionally NOT used:
 * every definition ships in the APK, so the store degrades gracefully offline
 * by construction and "Download" is an honest install of the bundled definition
 * — see [PluginStoreController]. Because entries come from a single source
 * ([PluginRegistry.compiledPlugins], which de-duplicates by id), an optional
 * plugin is listed exactly once whether installed or not.
 */
class PluginStoreCatalog(
    registry: PluginRegistry
) {

    private val entries: List<PluginStoreEntry> = registry.compiledPlugins.map { p ->
        PluginStoreEntry(
            pluginId = p.id,
            name = p.name,
            description = p.description,
            version = p.version,
            capabilities = p.capabilities,
            category = categoryFor(p.capabilities),
            bundled = true,
            optional = !registry.isBuiltIn(p.id),
            installSizeBytes = sizeBytesFor(p),
            permissions = p.manifest.permissions
        )
    }

    /** Every catalog entry (bundled definitions, installed or not). */
    fun entries(): List<PluginStoreEntry> = entries

    /** The catalog entry for [pluginId], or null when unknown. */
    fun entryFor(pluginId: String): PluginStoreEntry? =
        entries.firstOrNull { it.pluginId == pluginId }

    private fun sizeBytesFor(plugin: NoteflowPlugin): Long? =
        (plugin as? AssistantPlugin)?.expectedModelSizeBytes()

    private fun categoryFor(capabilities: Set<PluginCapability>): String {
        val primary = capabilities.firstOrNull()
        return when (primary) {
            PluginCapability.OCR, PluginCapability.ScreenshotNote -> "Vision"
            PluginCapability.TextTransform, PluginCapability.TextTools,
            PluginCapability.LanguageDetection -> "Text"
            PluginCapability.WebSearch, PluginCapability.WebCapture -> "Web"
            PluginCapability.Export -> "Export"
            PluginCapability.ClipShare -> "Import"
            PluginCapability.Dictation, PluginCapability.ReadAloud -> "Voice"
            PluginCapability.Translation -> "Language"
            PluginCapability.Assistant -> "AI"
            PluginCapability.FileTransfer -> "Transfer"
            else -> "Other"
        }
    }
}
