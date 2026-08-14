package com.authorss81.noteflow.plugins.outline

import android.content.Context
import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.OutlineGeneratorPlugin
import com.authorss81.noteflow.plugins.OutlineOutcome
import com.authorss81.noteflow.plugins.OutlineStyle
import com.authorss81.noteflow.plugins.PluginAvailability
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginManifest
import com.authorss81.noteflow.plugins.PluginSettings
import com.authorss81.noteflow.plugins.SemanticVersion

/**
 * The Outline & Checklist plugin (Phase 26) — serves
 * [PluginCapability.OutlineGenerator].
 *
 * PURE Kotlin: from the selected text/note it produces a structured outline or
 * a checkbox checklist. The grouping/indent logic lives in
 * [OutlineGeneratorCore] and is unit-tested.
 *
 * - Serves the `OutlineGenerator` capability via [OutlineGeneratorPlugin].
 * - `availability()` is always `Ok` — no device capability, no network.
 * - Opt-in off by default; toggle in Settings → Plugins / the Plugin Store.
 * - Never mutates the source silently: the generated text is PREVIEWED and only
 *   inserted on explicit user confirmation (see the UI wiring).
 */
class OutlineGeneratorPluginImpl : NoteflowPlugin, OutlineGeneratorPlugin {

    override val manifest = PluginManifest(
        id = ID,
        name = "Outline & Checklist",
        version = SemanticVersion(1, 0, 0),
        minSupportedApi = MIN_API,
        description = "Generates a structured markdown outline or checkbox checklist from the selected text.",
        capabilities = setOf(PluginCapability.OutlineGenerator)
    )

    override fun availability(context: Context?): PluginAvailability = PluginAvailability.Ok

    override fun onEnable(context: Context?, settings: PluginSettings) {}

    override fun generateOutline(text: String, style: OutlineStyle): OutlineOutcome {
        val result = OutlineGeneratorCore.generate(text, style)
            ?: return OutlineOutcome.Error("There is no text to structure — type or select something first.")
        return OutlineOutcome.Success(result)
    }

    companion object {
        const val MIN_API = 26
        const val ID = "com.authorss81.noteflow.plugins.outline"
    }
}