package com.authorss81.noteflow.mlkit

import android.content.Context
import com.authorss81.noteflow.mlkit.engine.PluginPayloadLoader
import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.OcrOutcome
import com.authorss81.noteflow.plugins.OcrPlugin
import com.authorss81.noteflow.plugins.PluginAvailability
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginManifest
import com.authorss81.noteflow.plugins.PluginSettings
import com.authorss81.noteflow.plugins.SemanticVersion
import com.authorss81.noteflow.plugins.TranslationLanguage
import com.authorss81.noteflow.plugins.TranslationModelStatus
import com.authorss81.noteflow.plugins.TranslationOutcome
import com.authorss81.noteflow.plugins.TranslationPlugin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The downloadable ML Kit OCR + translation plugin (phase-175, R2-KS-21).
 *
 * This class is NOT compiled into the base APK — it lives in the `plugins/mlkit`
 * module and is delivered as a signature-verified downloadable artifact (see
 * `docs/plugin-architecture.md` § Downloadable runtime). The base app ships only
 * the honest absent-plugin route (OCR/Translation requests fail with
 * `NO_PLUGIN_INSTALLED` + the store hint until the artifact is installed); once
 * the user installs it from the store (consent → HTTPS → pinned-cert + SHA-256
 * verify → DexClassLoader load), THIS plugin is materialized and serves
 * [PluginCapability.OCR] + [PluginCapability.Translation].
 *
 * It implements the exact framework surface the app's classloader resolves
 * ([NoteflowPlugin] + [OcrPlugin] + [TranslationPlugin] from `plugin-sdk`),
 * companion to the Phase-29 LLM plugin that serves [PluginCapability.Assistant]
 * the same way. The ML Kit engines + native `.so` files + bundled Latin OCR
 * model files ride INSIDE the artifact; the HOST extracts them to app-private
 * files at install time (`PluginArtifactStorage.extractPayload`) so the ML Kit
 * native pipeline can bind them.
 *
 * Split like every phase-12/16 plugin:
 *  - [OcrInputValidator]/[OcrTextFormatter]/[OcrErrorMapper]/[TranslationCatalog]/
 *    [SourceProber] — pure JVM (unit-tested here with injected fake engines).
 *  - [MlKitOcrEngine]/[MlKitTranslatorEngine] — ML Kit glue (platform-only).
 *
 * @param ocrEngine injected OCR engine (fake in JVM tests). Null → the bundled
 *   ML Kit engine is created lazily on device.
 * @param translatorEngine injected translator engine (fake in JVM tests). Null →
 *   the bundled ML Kit translator is created lazily on device.
 */
class OnDeviceMlKitPlugin(
    private val ocrEngine: OcrEngine? = null,
    private val translatorEngine: TranslatorEngine? = null
) : NoteflowPlugin, OcrPlugin, TranslationPlugin {

    override val manifest = PluginManifest(
        id = PLUGIN_ID,
        name = "On-Device OCR & Translation",
        version = SemanticVersion(1, 0, 0),
        minSupportedApi = MIN_API,
        description = "Extracts text from images (ML Kit OCR) and translates note " +
            "text (ML Kit translate) — fully on-device, keyless, offline after a " +
            "one-time translation-model download.",
        capabilities = setOf(PluginCapability.OCR, PluginCapability.Translation)
    )

    override fun availability(context: Context?): PluginAvailability {
        if (context == null) return PluginAvailability.Unknown
        rememberContext(context)
        val missing = PluginPayloadLoader.ensureNativeLibraries(context)
        if (missing != null) {
            return PluginAvailability.Unavailable(missing.reinstallHint())
        }
        return PluginAvailability.Ok
    }

    override fun onEnable(context: Context?, settings: PluginSettings) {
        // Stage the artifact payloads eagerly so the context-free translation
        // serving methods (`translate`/`downloadModel`) don't need a live
        // Context later. Missing payloads leave the feature honestly off via the
        // typed out comes — never a fake result.
        if (context != null) {
            rememberContext(context)
            PluginPayloadLoader.ensureNativeLibraries(context)
            PluginPayloadLoader.ensureOcrModelAssets(context)
        }
    }

    override fun onDisable(context: Context?, settings: PluginSettings) {
        closeEngines()
    }

    override fun selfCheck(context: Context?): PluginAvailability {
        if (context == null) return PluginAvailability.Unknown
        if (ocrEngine != null || translatorEngine != null) return PluginAvailability.Ok
        val missing = PluginPayloadLoader.ensureNativeLibraries(context)
            ?: PluginPayloadLoader.ensureOcrModelAssets(context)
        return if (missing == null) PluginAvailability.Ok
        else PluginAvailability.Unavailable(missing.reinstallHint())
    }

    /** Delete the app-private payloads this plugin's artifact staged (store Delete
     *  = delete = gone). The host also clears them when uninstalling. */
    override fun deleteDownloadedAssets(context: Context?) {
        val ctx = context ?: return
        try {
            val root = PluginPayloadLoader.payloadRoot(ctx)
            if (root.isDirectory) root.deleteRecursively()
        } catch (_: Throwable) {
            // Never throw into the store; leftover bytes are harmless.
        }
    }

    // ---- OcrPlugin serving interface ----------------------------------------

    override suspend fun recognizeText(context: Context?, imagePath: String): OcrOutcome {
        OcrInputValidator.validateImagePath(imagePath)?.let { return OcrOutcome.Error(it) }
        rememberContext(context)
        return try {
            val eng = ocrEngineFor()
            val raw = withContext(Dispatchers.IO) { eng.recognize(appContext(), imagePath) }
            val text = OcrTextFormatter.format(raw)
            if (text.isBlank()) OcrOutcome.NoText(OcrErrorMapper.noTextMessage())
            else OcrOutcome.Success(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            OcrOutcome.Error(OcrErrorMapper.userMessage(e))
        }
    }

    // ---- TranslationPlugin serving interface ---------------------------------

    override fun supportedTargetLanguages(): List<TranslationLanguage> =
        TranslationCatalog.supportedTargetLanguages()

    override suspend fun isModelDownloaded(targetLanguage: String): Boolean {
        val eng = translatorEngineFor() ?: return false
        return try {
            eng.isModelDownloaded(TranslationCatalog.normalize(targetLanguage))
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun downloadModel(targetLanguage: String): TranslationModelStatus {
        val eng = translatorEngineFor()
            ?: return TranslationModelStatus.Error("Translation isn't ready — reinstall the plugin from the Plugin Store.")
        return eng.downloadModel(TranslationCatalog.normalize(targetLanguage))
    }

    override suspend fun translate(targetLanguage: String, text: String): TranslationOutcome {
        val eng = translatorEngineFor()
            ?: return TranslationOutcome.Error("Translation isn't ready — reinstall the plugin from the Plugin Store.")
        val target = TranslationCatalog.normalize(targetLanguage)
        if (target !in TranslationCatalog.TARGETS) {
            return TranslationOutcome.Error("'$targetLanguage' isn't a supported target language.")
        }
        val source = TranslationCatalog.detectSourceLanguage(text, fallback = "en")
        return eng.translate(source, target, text)
    }

    // ---- internals ---------------------------------------------------------

    @Volatile
    private var appContextRef: Context? = null

    @Volatile
    private var platformOcr: OcrEngine? = null

    @Volatile
    private var platformTranslator: TranslatorEngine? = null

    private fun rememberContext(context: Context?) {
        if (context != null && appContextRef == null) {
            appContextRef = context.applicationContext
        }
    }

    private fun appContext(): Context? = appContextRef

    private fun closeEngines() {
        (ocrEngine as? AutoCloseable)?.close()
        (platformOcr as? AutoCloseable)?.close()
        platformOcr = null
        (translatorEngine as? AutoCloseable)?.close()
        (platformTranslator as? AutoCloseable)?.close()
        platformTranslator = null
    }

    private fun ocrEngineFor(): OcrEngine {
        ocrEngine?.let { return it }
        platformOcr?.let { return it }
        val ctx = appContext() ?: throw IllegalStateException("OCR requires a device context")
        PluginPayloadLoader.ensureNativeLibraries(ctx)?.let { throw PayloadNotReady(it) }
        val engine = PlatformOcrEngines.createMlKit(ctx) { missing ->
            payloadMissing = missing
        }
        if (engine == null) {
            throw PayloadNotReady(
                payloadMissing ?: "The OCR engine isn't ready — reinstall the plugin from the Plugin Store."
            )
        }
        platformOcr = engine
        return engine
    }

    private fun translatorEngineFor(): TranslatorEngine? {
        translatorEngine?.let { return it }
        platformTranslator?.let { return it }
        val ctx = appContext() ?: return null
        PluginPayloadLoader.ensureNativeLibraries(ctx)?.let { return null }
        val engine = PlatformTranslationEngines.create(ctx) ?: return null
        platformTranslator = engine
        return engine
    }

    /** Marker exception so recognizer construction failures surface as a typed
     *  OcrOutcome with the payload message instead of a generic one. */
    private class PayloadNotReady(message: String) : IllegalStateException(message)

    @Volatile
    private var payloadMissing: String? = null

    private companion object {
        const val PLUGIN_ID = "com.authorss81.noteflow.plugins.mlkit"
        const val MIN_API = 26
    }
}

internal fun String.reinstallHint(): String =
    "$this The plugin payloads ship inside the downloaded artifact — open Home → ⋮ → Plugin Store and reinstall \"On-Device OCR & Translation\"."