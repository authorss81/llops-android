package com.authorss81.noteflow.plugins.store

import com.authorss81.noteflow.plugins.AssistantPlugin
import com.authorss81.noteflow.plugins.CaseChangePlugin
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
    val permissions: Set<PluginPermission>,
    /** Non-null for optional plugins: creates the compiled definition on download. */
    internal val createInstance: (() -> NoteflowPlugin)? = null
)

/**
 * The bundled plugin catalog (Phase 21).
 *
 * Built from the registry's compiled set PLUS the optional store-only plugins
 * (definitions bundled in the APK that the user must explicitly download). A
 * real network catalog is intentionally NOT used: every definition ships in the
 * APK, so the store degrades gracefully offline by construction and "Download"
 * is an honest install of the bundled definition — see [PluginStoreController].
 */
class PluginStoreCatalog(
    registry: PluginRegistry
) {

    private val entries: List<PluginStoreEntry> = buildList {
        registry.compiledPlugins.forEach { p ->
            add(
                PluginStoreEntry(
                    pluginId = p.id,
                    name = p.name,
                    description = p.description,
                    version = p.version,
                    capabilities = p.capabilities,
                    category = categoryFor(p.capabilities),
                    bundled = true,
                    optional = false,
                    installSizeBytes = sizeBytesFor(p),
                    permissions = p.manifest.permissions,
                    createInstance = null
                )
            )
        }
        // Optional, store-only plugin: bundled definition, NOT registered by
        // default. Downloading installs it into the registry (see the DoD's
        // "install/uninstall plugin DEFINITIONS, not loaded bytecode").
        add(
            PluginStoreEntry(
                pluginId = CASE_CHANGE_ID,
                name = "Case Converter",
                description = "Converts note text to UPPERCASE, lowercase or Title Case.",
                version = SemanticVersion(1, 0, 0),
                capabilities = setOf(PluginCapability.TextTransform),
                category = "Text",
                bundled = true,
                optional = true,
                installSizeBytes = null,
                permissions = emptySet(),
                createInstance = { CaseChangePlugin() }
            )
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

    private companion object {
        const val CASE_CHANGE_ID = "com.authorss81.noteflow.plugins.casechange"
    }
}
