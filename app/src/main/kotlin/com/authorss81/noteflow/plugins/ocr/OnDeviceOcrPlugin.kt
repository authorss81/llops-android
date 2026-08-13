package com.authorss81.noteflow.plugins.ocr

import android.content.Context
import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.OcrOutcome
import com.authorss81.noteflow.plugins.OcrPlugin
import com.authorss81.noteflow.plugins.PluginAvailability
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginManifest
import com.authorss81.noteflow.plugins.PluginSettings
import com.authorss81.noteflow.plugins.SemanticVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The real, on-device OCR plugin (Phase 12).
 *
 * Serves the [PluginCapability.OCR] capability via the framework [OcrPlugin]
 * serving interface. It extracts text from an image file already attached to a
 * note — fully offline via ML Kit (bundled model), no API key, no INTERNET.
 *
 * The plugin is deliberately split:
 * - [OcrPlugin]/[OcrInputValidator]/[OcrTextFormatter]/[OcrErrorMapper] — pure
 *   wrapper logic, covered by JVM unit tests (`OcrPluginWrapperTest`).
 * - [MlKitOcrEngine] — the model runner, platform-only (tested on-device).
 *
 * All model work runs on `Dispatchers.IO` and is cancelable end-to-end (a
 * cancelled coroutine cancels the ML Kit `Task`).
 *
 * @param engine an injected [OcrEngine] (tests / future engines). When null,
 *   the bundled ML Kit engine is created lazily on first use — production
 *   behaviour; unit tests never reach it because they inject a fake engine.
 */
class OnDeviceOcrPlugin(
    private val engine: OcrEngine? = null
) : NoteflowPlugin, OcrPlugin {

    override val manifest = PluginManifest(
        id = "com.authorss81.noteflow.plugins.ocr",
        name = "On-Device OCR",
        version = SemanticVersion(1, 0, 0),
        minSupportedApi = 26,
        description = "Extracts text from images on-device with ML Kit — offline, no API key.",
        capabilities = setOf(PluginCapability.OCR)
    )

    override fun availability(context: Context?): PluginAvailability =
        if (context == null) PluginAvailability.Unknown else PluginAvailability.Ok

    override fun onEnable(context: Context?, settings: PluginSettings) {
        // Documented settings-schema migration (see docs/PLUGIN_SDK.md § 4):
        // schema 0 → 1 records the default language scope choice.
        val schema = settings.getInt("settings_schema", default = 0)
        if (schema < 1) {
            settings.setString("language_scope", "latin")
            settings.setInt("settings_schema", 1)
        }
    }

    override fun onDisable(context: Context?, settings: PluginSettings) {
        platformEngine?.let { engine ->
            (engine as? AutoCloseable)?.close()
            platformEngine = null
        }
    }

    override suspend fun recognizeText(context: Context?, imagePath: String): OcrOutcome {
        val validation = OcrInputValidator.validateImagePath(imagePath)
        if (validation != null) return OcrOutcome.Error(validation)

        return try {
            val eng = engineFor(context)
            val raw = withContext(Dispatchers.IO) { eng.recognize(context, imagePath) }
            val text = OcrTextFormatter.format(raw)
            if (text.isBlank()) OcrOutcome.NoText(OcrErrorMapper.noTextMessage())
            else OcrOutcome.Success(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            OcrOutcome.Error(OcrErrorMapper.userMessage(e))
        }
    }

    override fun selfCheck(context: Context?): PluginAvailability {
        val ctx = context ?: return PluginAvailability.Unknown
        return try {
            engineFor(ctx)
            PluginAvailability.Ok
        } catch (e: Throwable) {
            PluginAvailability.Unavailable("OCR engine could not be initialized (${e::class.java.simpleName})")
        }
    }

    // ---- internals ---------------------------------------------------------

    /** Injected engine wins; otherwise create+cache the bundled ML Kit engine. */
    private fun engineFor(context: Context?): OcrEngine {
        engine?.let { return it }
        return platformEngine ?: PlatformOcrEngines.createMlKit(
            context ?: throw IllegalStateException("OCR requires a device context")
        ).also { platformEngine = it }
    }

    @Volatile
    private var platformEngine: OcrEngine? = null

    private companion object {
        const val MIN_API = 26
    }
}
