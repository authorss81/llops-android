package com.authorss81.noteflow

import android.content.Context
import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.PluginAvailability
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginEnableStore
import com.authorss81.noteflow.plugins.PluginManifest
import com.authorss81.noteflow.plugins.PluginPermission
import com.authorss81.noteflow.plugins.PluginSettings
import com.authorss81.noteflow.plugins.PluginSettingsStore
import com.authorss81.noteflow.plugins.SemanticVersion
import com.authorss81.noteflow.plugins.TextTransformPlugin

/** In-memory [PluginEnableStore] for JVM tests (tracks ever-enabled). */
internal class InMemoryEnableStore : PluginEnableStore {
    val state = mutableMapOf<String, Boolean>()
    val ever = mutableSetOf<String>()

    override fun isEnabled(pluginId: String): Boolean = state[pluginId] == true

    override fun setEnabled(pluginId: String, enabled: Boolean) {
        state[pluginId] = enabled
        if (enabled) ever.add(pluginId)
    }

    override fun hasEverBeenEnabled(pluginId: String): Boolean = pluginId in ever

    /** Matches production [PluginEnableStore.wipe]: off AND ever-enabled reset. */
    override fun wipe(pluginId: String) {
        state[pluginId] = false
        ever.remove(pluginId)
    }

    /** Simulate a previous session that left the plugin enabled in the store. */
    fun forceEnabled(pluginId: String) {
        state[pluginId] = true
        ever.add(pluginId)
    }
}

/** A configurable test plugin driving availability from a mutable cell. */
internal class TestPlugin(
    id: String,
    name: String = "Test $id",
    version: SemanticVersion = SemanticVersion(1, 0, 0),
    capabilities: Set<PluginCapability> = setOf(PluginCapability.TextTransform),
    minSupportedApi: Int = 26,
    permissions: Set<PluginPermission> = emptySet(),
    dependencies: Set<String> = emptySet(),
    requiresCapabilities: Set<PluginCapability> = emptySet(),
    private val availabilityResult: (Context?) -> PluginAvailability = { PluginAvailability.Ok },
    private val selfCheckResult: (Context?) -> PluginAvailability = { PluginAvailability.Ok },
    private val onEnableBlock: (Context?, PluginSettings) -> Unit = { _, _ -> },
    private val onDisableBlock: (Context?, PluginSettings) -> Unit = { _, _ -> },
    private val onConfigChangedBlock: (Context?, PluginSettings) -> Unit = { _, _ -> },
    private val transformBlock: ((String) -> String)? = null
) : NoteflowPlugin, TextTransformPlugin {

    override val manifest = PluginManifest(
        id = id,
        name = name,
        version = version,
        minSupportedApi = minSupportedApi,
        description = "Test plugin $id",
        capabilities = capabilities,
        permissions = permissions,
        dependencies = dependencies,
        requiresCapabilities = requiresCapabilities
    )

    override fun availability(context: Context?): PluginAvailability = availabilityResult(context)
    override fun onEnable(context: Context?, settings: PluginSettings) = onEnableBlock(context, settings)
    override fun onDisable(context: Context?, settings: PluginSettings) = onDisableBlock(context, settings)
    override fun onConfigChanged(context: Context?, settings: PluginSettings) = onConfigChangedBlock(context, settings)
    override fun selfCheck(context: Context?): PluginAvailability = selfCheckResult(context)

    override fun transformText(text: String): String =
        transformBlock?.invoke(text) ?: throw IllegalStateException("$name does not transform")
}