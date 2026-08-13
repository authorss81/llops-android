package com.authorss81.noteflow.plugins.clipshare

import android.content.Context
import com.authorss81.noteflow.plugins.ClipParseOutcome
import com.authorss81.noteflow.plugins.ClipSharePlugin
import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.PluginAvailability
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginManifest
import com.authorss81.noteflow.plugins.PluginSettings
import com.authorss81.noteflow.plugins.SemanticVersion
import com.authorss81.noteflow.plugins.SharedInput

/**
 * "Clip to InkFlow" share target (Phase 15, capability `ClipShare`).
 *
 * Registers the app as an ACTION_SEND / ACTION_SEND_MULTIPLE receiver (the
 * intent-filter glue lives in AndroidManifest.xml + MainActivity) so text,
 * images and files can be clipped into a new (or existing) encrypted note.
 *
 * The plugin itself is the pure, testable gate: it classifies + validates the
 * incoming share ([SharedClipParser.parse]) BEFORE any content is copied or
 * stored — so a blank/oversized share is rejected loudly and nothing bypasses
 * the vault's encryption path (the ViewModel stores clipped content through the
 * same encrypted `NoteRepository.createPage` as any note).
 */
class ClipToInkFlowPlugin : NoteflowPlugin, ClipSharePlugin {

    override val manifest = PluginManifest(
        id = "com.authorss81.noteflow.plugins.clipshare",
        name = "Clip to InkFlow",
        version = SemanticVersion(1, 0, 0),
        minSupportedApi = 26,
        description = "Receives shared text, images and files from other apps and stores them in an encrypted note.",
        capabilities = setOf(PluginCapability.ClipShare)
    )

    override fun availability(context: Context?): PluginAvailability =
        PluginAvailability.Ok

    override fun onEnable(context: Context?, settings: PluginSettings) {
        // Stateless — parsing is pure; nothing to warm up.
    }

    override fun parse(input: SharedInput): ClipParseOutcome =
        SharedClipParser.parse(input)
}