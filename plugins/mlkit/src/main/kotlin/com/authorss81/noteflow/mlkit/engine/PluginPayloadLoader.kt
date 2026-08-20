package com.authorss81.noteflow.mlkit.engine

import android.content.Context
import android.os.Build
import java.io.File

/**
 * PLATFORM-ONLY — stage + bind the native payloads of the downloadable ML Kit
 * plugin (phase-175, R2-KS-21).
 *
 * The ML Kit engine ships INSIDE the downloadable plugin artifact (never the
 * base APK): the OCR pipeline / translate `.so` files under `lib/<abi>/` and
 * the bundled Latin OCR model files under `assets/mlkit-google-ocr-models/`.
 * The HOST extracts them, at install time, into app-private files at
 * `filesDir/noteflow/plugin-payloads/<pluginId>/` (see
 * `PluginArtifactStorage.extractPayload` — the SAME verified artifact that
 * passed the pinned-cert + SHA-256 + static-scan gates). The native engine's
 * asset resolver (`AndroidAssetUtil.nativeInitializeAssetManager`, seeded with
 * the cache dir) and library loader (`System.load`) read from there.
 *
 * Design rules (mirror of `plugins/llm` `NativeLibraryBundle`):
 * - ONLY app-private files are written — never shared storage, never log bytes.
 * - Every load failure is collected and surfaces as a user-facing message; it
 *   NEVER crashes the host. Missing payloads leave the feature honestly OFF
 *   (typed OcrOutcome.Error / TranslationOutcome.Error), never a fake result.
 * - Idempotent: repeated calls are no-ops once natives are loaded / models are
 *   staged.
 */
object PluginPayloadLoader {

    /** The app-private payload root the host extracts into at install time. */
    private const val REL_DIR = "noteflow/plugin-payloads/com.authorss81.noteflow.plugins.mlkit"
    private const val MARKER_NATIVES = ".nativelibs-loaded"
    private const val MARKER_MODELS = ".ocr-models-ready"
    private const val MAX_SO_BYTES = 40L * 1024 * 1024
    private const val MODEL_ASSET_REL = "mlkit-google-ocr-models"

    @Volatile
    private var loadedAbi: String? = null

    @Volatile
    private var modelsReady = false

    /** Absolute path of the app-private payload root (may not exist yet). */
    fun payloadRoot(context: Context): File = File(context.filesDir, REL_DIR)

    /** The device's first supported ABI (arm64-v8a etc.), or "arm64-v8a". */
    fun currentAbi(): String =
        Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    /** User-facing error when the native OCR/translate libraries can't load
     *  (null = ready). Also preloads them in a stable order. */
    fun ensureNativeLibraries(context: Context): String? {
        val abi = currentAbi()
        if (loadedAbi == abi) return null
        val libDir = File(payloadRoot(context), "lib/$abi")
        val marker = File(payloadRoot(context), MARKER_NATIVES)
        if (!libDir.isDirectory) {
            return "The ML Kit engine libraries are missing for ABI '$abi' — reinstall the plugin from the Plugin Store."
        }
        if (marker.exists()) {
            loadedAbi = abi
            return null
        }
        return try {
            val libs = libDir.listFiles { f -> f.name.endsWith(".so") }?.sortedBy { it.name } ?: emptyList()
            if (libs.isEmpty()) {
                "The ML Kit engine libraries are missing for ABI '$abi' — the plugin may be incomplete."
            } else {
                loadAll(libs)
                marker.createNewFile()
                loadedAbi = abi
                null
            }
        } catch (e: Throwable) {
            "The ML Kit engine libraries couldn't load (${e::class.java.simpleName})."
        }
    }

    /** User-facing error when the bundled Latin OCR model files are not staged
     *  (null = ready). The native pipeline opens them via the cache-dir-seeded
     *  asset resolver, so they must exist before the recognizer is created. */
    fun ensureOcrModelAssets(context: Context): String? {
        if (modelsReady) return null
        val assetsDir = File(payloadRoot(context), "assets/$MODEL_ASSET_REL")
        if (!assetsDir.isDirectory) {
            return "The OCR model files are missing — reinstall the plugin from the Plugin Store."
        }
        val fileCount = assetsDir.walkTopDown().filter { it.isFile }.count()
        if (fileCount <= 0) {
            return "The OCR model files are missing — reinstall the plugin from the Plugin Store."
        }
        if (File(payloadRoot(context), MARKER_MODELS).exists() || fileCount > 0) {
            modelsReady = true
        }
        return null
    }

    /** Read a bundled informational asset (e.g. `translate_models_metadata.json`),
     *  or null when absent. Never logs contents. */
    fun readBundledTextAsset(context: Context, name: String): String? {
        val file = File(payloadRoot(context), "assets/$name")
        return file.takeIf { it.isFile && it.length() < 256 * 1024 }?.readText()
    }

    private fun loadAll(libs: List<File>) {
        for (lib in libs) {
            if (lib.length() > MAX_SO_BYTES) continue
            try {
                System.load(lib.absolutePath)
            } catch (e: UnsatisfiedLinkError) {
                // Loose ordering can transiently fail a leaf that depends on a
                // sibling; retry once so the second pass usually fixes it.
                try {
                    System.load(lib.absolutePath)
                } catch (_: Throwable) {
                    throw e
                }
            }
        }
    }
}
