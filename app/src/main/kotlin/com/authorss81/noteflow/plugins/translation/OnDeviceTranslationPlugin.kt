package com.authorss81.noteflow.plugins.translation

import android.content.Context
import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.PluginAvailability
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginManifest
import com.authorss81.noteflow.plugins.PluginSettings
import com.authorss81.noteflow.plugins.SemanticVersion
import com.authorss81.noteflow.plugins.TranslationLanguage
import com.authorss81.noteflow.plugins.TranslationModelStatus
import com.authorss81.noteflow.plugins.TranslationOutcome
import com.authorss81.noteflow.plugins.TranslationPlugin

/**
 * The real, on-device translation plugin (Phase 16).
 *
 * Serves [PluginCapability.Translation] (exclusive — one translator engine at
 * a time) via [TranslationPlugin]. Keyless and offline-first: models download
 * once on explicit user action and then run fully on-device.
 *
 * Split like every Phase-15/16 plugin:
 *  - [TranslationCatalog] — PURE JVM catalogue + source auto-detection (unit-tested).
 *  - [MlKitTranslatorEngine] — ML Kit `translate` glue (platform-only).
 *
 * @param engine injected translator engine (fake in JVM tests). Null → the
 *   bundled ML Kit engine is created lazily on device (real behaviour); unit
 *   tests never reach it because they inject a fake engine.
 */
class OnDeviceTranslationPlugin(
    private val engine: TranslatorEngine? = null
) : NoteflowPlugin, TranslationPlugin {

    override val manifest = PluginManifest(
        id = "com.authorss81.noteflow.plugins.translation",
        name = "On-Device Translation",
        version = SemanticVersion(1, 0, 0),
        minSupportedApi = MIN_API,
        description = "Translates note text with ML Kit on-device — keyless, offline " +
            "after a one-time model download.",
        capabilities = setOf(PluginCapability.Translation)
    )

    override fun availability(context: Context?): PluginAvailability {
        val ctx = context
        if (ctx == null) return PluginAvailability.Unknown
        return if (android.os.Build.VERSION.SDK_INT < MIN_API) {
            PluginAvailability.Unavailable("Translation needs Android 8.0 (API 26) or newer.")
        } else {
            PluginAvailability.Ok
        }
    }

    override fun onEnable(context: Context?, settings: PluginSettings) {
        // No settings yet — a default-target-language option would migrate here.
    }

    override fun onDisable(context: Context?, settings: PluginSettings) {
        (engine as? AutoCloseable)?.close()
        (platformEngine as? AutoCloseable)?.close()
        platformEngine = null
    }

    override fun selfCheck(context: Context?): PluginAvailability = availability(context)

    // ---- TranslationPlugin serving interface --------------------------------

    override fun supportedTargetLanguages(): List<TranslationLanguage> =
        TranslationCatalog.supportedTargetLanguages()

    override suspend fun isModelDownloaded(targetLanguage: String): Boolean =
        engineFor().isModelDownloaded(TranslationCatalog.normalize(targetLanguage))

    override suspend fun downloadModel(targetLanguage: String): TranslationModelStatus =
        engineFor().downloadModel(TranslationCatalog.normalize(targetLanguage))

    override suspend fun translate(targetLanguage: String, text: String): TranslationOutcome {
        val target = TranslationCatalog.normalize(targetLanguage)
        if (target !in TranslationCatalog.TARGETS) {
            return TranslationOutcome.Error("'$targetLanguage' isn't a supported target language.")
        }
        val source = TranslationCatalog.detectSourceLanguage(text, fallback = "en")
        return engineFor().translate(source, target, text)
    }

    // ---- internals ---------------------------------------------------------

    private fun engineFor(): TranslatorEngine {
        engine?.let { return it }
        return platformEngine ?: PlatformTranslationEngines.create().also { platformEngine = it }
    }

    @Volatile
    private var platformEngine: TranslatorEngine? = null

    private companion object {
        const val MIN_API = 26
    }
}