package com.authorss81.noteflow.plugins.langdetect

import android.content.Context
import com.authorss81.noteflow.plugins.LanguageDetectionOutcome
import com.authorss81.noteflow.plugins.LanguageDetectionPlugin
import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.PluginAvailability
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginManifest
import com.authorss81.noteflow.plugins.PluginSettings
import com.authorss81.noteflow.plugins.SemanticVersion

/**
 * Language detection & auto-tagging plugin (Phase 15, capability
 * `LanguageDetection`), served through [LanguageDetectionPlugin].
 *
 * Detection and tag-merging are pure JVM ([LanguageDetectionCore] over Lingua);
 * the plugin additionally owns a per-plugin setting `lang_auto_tag` (default
 * on) which the UI reads to decide whether a note's language tag is refreshed
 * automatically on save.
 */
class LanguageDetectionEngine : NoteflowPlugin, LanguageDetectionPlugin {

    override val manifest = PluginManifest(
        id = "com.authorss81.noteflow.plugins.langdetect",
        name = "Language Detection",
        version = SemanticVersion(1, 0, 0),
        minSupportedApi = 26,
        description = "Detects a note's language (Lingua, offline) and auto-tags it as lang:<iso> on save.",
        capabilities = setOf(PluginCapability.LanguageDetection)
    )

    override fun availability(context: Context?): PluginAvailability =
        PluginAvailability.Ok

    override fun onEnable(context: Context?, settings: PluginSettings) {
        // Default the auto-tag behaviour ON when the plugin is first enabled.
        if (!settings.containsKey("lang_auto_tag")) {
            settings.setBoolean("lang_auto_tag", true)
        }
    }

    override fun detectLanguage(text: String): LanguageDetectionOutcome =
        LanguageDetectionCore.detectLanguage(text)

    override fun autoTagLanguage(text: String, existingTags: String): String =
        LanguageDetectionCore.autoTagLanguage(text, existingTags)

    override fun isLanguageTag(tag: String): Boolean =
        LanguageDetectionCore.isLanguageTag(tag)
}