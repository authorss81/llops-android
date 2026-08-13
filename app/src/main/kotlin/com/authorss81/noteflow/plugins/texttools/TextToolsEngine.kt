package com.authorss81.noteflow.plugins.texttools

import android.content.Context
import com.authorss81.noteflow.plugins.DiffHunk
import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.PluginAvailability
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginManifest
import com.authorss81.noteflow.plugins.PluginSettings
import com.authorss81.noteflow.plugins.SemanticVersion
import com.authorss81.noteflow.plugins.TextAnalysis
import com.authorss81.noteflow.plugins.TextToolsPlugin

/**
 * Text Tools plugin (Phase 15, capability `TextTools`).
 *
 * Serves structural statistics (words/characters/paragraphs/sentences/reading
 * time) + Flesch-Kincaid readability and a simple note-diff. Everything is pure
 * Kotlin ([TextToolsAnalyzer], [TextNoteDiff]) with zero dependencies — the
 * whole plugin is JVM-unit-testable.
 */
class TextToolsEngine : NoteflowPlugin, TextToolsPlugin {

    override val manifest = PluginManifest(
        id = "com.authorss81.noteflow.plugins.texttools",
        name = "Text Tools",
        version = SemanticVersion(1, 0, 0),
        minSupportedApi = 26,
        description = "Word/character/paragraph counts, reading time, Flesch-Kincaid readability and a note diff.",
        capabilities = setOf(PluginCapability.TextTools)
    )

    override fun availability(context: Context?): PluginAvailability =
        PluginAvailability.Ok

    override fun onEnable(context: Context?, settings: PluginSettings) {
        // Stateless — nothing to warm up.
    }

    override fun analyzeText(text: String): TextAnalysis =
        TextToolsAnalyzer.analyze(text)

    override fun diffTexts(oldText: String, newText: String): List<DiffHunk> =
        TextNoteDiff.diff(oldText, newText)
}