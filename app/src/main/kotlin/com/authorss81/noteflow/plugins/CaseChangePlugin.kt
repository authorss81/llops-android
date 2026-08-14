package com.authorss81.noteflow.plugins

import android.content.Context

/**
 * A real, working OPTIONAL text-transform plugin (Phase 21).
 *
 * This plugin is deliberately NOT part of [PluginRegistry.defaultPlugins] — it
 * is the "not yet downloaded" entry in the plugin store: its definition is
 * bundled in the APK (compile-time rule — no dynamic classloading), but it only
 * becomes part of the active registry once the user taps **Download** in the
 * Plugin Store. Downloading installs this compiled definition; deleting removes
 * it again.
 *
 * It converts note text to one of UPPERCASE / lowercase / Title Case, selected
 * by the namespaced `plugins.<id>.mode` setting (see [CaseMode]). Served through
 * the [PluginCapability.TextTransform] capability via [TextTransformPlugin],
 * exactly like [Rot13TransformPlugin].
 */
class CaseChangePlugin : NoteflowPlugin, TextTransformPlugin {

    enum class CaseMode(val key: String) {
        UPPER("upper"), LOWER("lower"), TITLE("title");

        companion object {
            fun fromKey(key: String?): CaseMode =
                entries.firstOrNull { it.key == key } ?: UPPER
        }
    }

    override val manifest = PluginManifest(
        id = "com.authorss81.noteflow.plugins.casechange",
        name = "Case Converter",
        version = SemanticVersion(1, 0, 0),
        minSupportedApi = MIN_API,
        description = "Converts note text to UPPERCASE, lowercase or Title Case.",
        capabilities = setOf(PluginCapability.TextTransform)
    )

    @Volatile
    private var settings: PluginSettings? = null

    override fun availability(context: Context?): PluginAvailability = PluginAvailability.Ok

    override fun onEnable(context: Context?, settings: PluginSettings) {
        this.settings = settings
    }

    override fun onDisable(context: Context?, settings: PluginSettings) {
        this.settings = null
    }

    override fun onConfigChanged(context: Context?, settings: PluginSettings) {
        this.settings = settings
    }

    override fun transformText(text: String): String {
        val mode = settings?.getString(SETTING_MODE)?.let { CaseMode.fromKey(it) } ?: CaseMode.UPPER
        return when (mode) {
            CaseMode.UPPER -> text.uppercase()
            CaseMode.LOWER -> text.lowercase()
            CaseMode.TITLE -> titleCase(text)
        }
    }

    private fun titleCase(text: String): String {
        val sb = StringBuilder(text.length)
        var newWord = true
        for (ch in text) {
            if (ch.isLetterOrDigit()) {
                sb.append(if (newWord) ch.uppercaseChar() else ch)
                newWord = false
            } else {
                sb.append(ch)
                newWord = true
            }
        }
        return sb.toString()
    }

    private companion object {
        const val MIN_API = 26
        const val SETTING_MODE = "mode"
    }
}
