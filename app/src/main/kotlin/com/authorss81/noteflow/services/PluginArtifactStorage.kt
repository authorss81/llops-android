package com.authorss81.noteflow.services

import android.content.Context
import android.os.Build
import android.os.StatFs
import com.authorss81.noteflow.plugins.runtime.PluginArtifactResolver
import com.authorss81.noteflow.plugins.runtime.PluginDownloader
import com.authorss81.noteflow.plugins.runtime.PluginEntry
import java.io.File
import java.util.jar.JarFile

/**
 * App-private storage for downloaded plugin artifacts (Phase 23).
 *
 * Artifacts live under `filesDir/noteflow/plugins/` — **never** `cacheDir`
 * (they must survive cache clears) and **never** shared storage
 * (`noteflow/plugins/` matches the assistant model download convention).
 * Same file layout as [com.authorss81.noteflow.plugins.runtime.PluginDownloader]:
 * `<sanitized-id>-<version>.apk` (+ `.part` while a download is interrupted).
 */
class PluginArtifactStorage(context: Context) : PluginArtifactResolver {

    private val appContext = context.applicationContext

    /** The app-private directory holding every downloaded plugin artifact. */
    fun dir(): File =
        File(appContext.filesDir, "noteflow/plugins").apply { if (!exists()) mkdirs() }

    /** Where a plugin's artifact is expected on disk (may not exist yet). */
    fun artifactFile(entry: PluginEntry): File =
        File(dir(), PluginDownloader.artifactFileNameFor(entry))

    override fun artifactFor(entry: PluginEntry): File? =
        artifactFile(entry).takeIf { it.isFile }

    /** Delete the downloaded artifact (store Delete = gone). */
    fun delete(entry: PluginEntry) {
        artifactFile(entry).delete()
        File(dir(), "${PluginDownloader.artifactFileNameFor(entry)}.part").delete()
        deletePayloads(entry.id)
    }

    /** Directory DexClassLoader writes its optimized DEX into. */
    fun optimizedDir(): File =
        File(appContext.filesDir, "noteflow/plugins/optimized").apply { if (!exists()) mkdirs() }

    /** Free bytes in the artifact directory (StatFs), or -1 on failure. */
    fun freeBytes(): Long = try {
        StatFs(dir().absolutePath).availableBytes
    } catch (_: Throwable) {
        -1L
    }

    // ---- phase-175: plugin artifact payloads --------------------------------
    //
    // Downloadable engine plugins carry payloads the Android classloader cannot
    // serve at runtime: native `.so` libraries and raw model assets. The ML Kit
    // OCR/translation artifact (`plugins/mlkit`) bundles them as
    // `lib/<abi>/*.so` + `assets/mlkit-google-ocr-models/**` +
    // `assets/translate_models_metadata.json` + `assets/rapid_response_client_defaults.xml`;
    // at INSTALL time (only after the artifact passed the pinned-cert + SHA-256
    // + static-scan verification) they are extracted here, into app-private
    // files, so the plugin's own native/asset loader can bind them. Extract only
    // the CURRENT device ABI. Idempotent via a per-artifact marker.

    private val currentAbi: String = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    /** App-private directory holding a downloaded plugin's extracted payloads. */
    fun payloadDir(pluginId: String): File =
        File(appContext.filesDir, "noteflow/plugin-payloads/$pluginId")

    /** Reserved payload roots an artifact may carry (anything else is ignored). */
    private val RESERVED_PREFIXES = listOf(
        "assets/mlkit-google-ocr-models/",
        "assets/translate_models_metadata.json",
        "assets/rapid_response_client_defaults.xml"
    )

    private fun payloadMarker(pluginId: String, sha256: String?): File =
        File(payloadDir(pluginId), ".payload-${(sha256 ?: "unknown").take(16)}")

    /**
     * Extract a verified artifact's payloads (natives `${abi}` + reserved assets)
     * into app-private files. Returns null on success or a user-facing error.
     * Safely no-ops when the payloads are already present (marker matches) or the
     * artifact carries no payloads (e.g. the LLM plugin).
     */
    fun extractPayload(entry: PluginEntry, artifact: File): String? {
        if (!artifact.isFile) return "The plugin artifact is missing on disk."
        val markerFile = payloadMarker(entry.id, entry.sha256)
        val root = payloadDir(entry.id)
        if (markerFile.exists()) return null

        val abiPrefix = "lib/$currentAbi/"
        try {
            JarFile(artifact).use { jar ->
                var wrote = false
                jar.entries().asSequence().forEach { jarEntry ->
                    if (jarEntry.isDirectory) return@forEach
                    val name = jarEntry.name
                    val targetName = when {
                        name.startsWith(abiPrefix) -> "lib/$currentAbi/${name.substringAfterLast('/')}"
                        RESERVED_PREFIXES.any { name == it || name.startsWith(it) } -> "assets/" + name.removePrefix("assets/")
                        else -> null
                    } ?: return@forEach
                    if (!wrote) {
                        root.mkdirs()
                        wrote = true
                    }
                    val out = File(root, targetName)
                    if (out.exists() && out.length() == jarEntry.size.toLong()) return@forEach
                    out.parentFile.mkdirs()
                    jar.getInputStream(jarEntry).use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                // A marker records the extraction only for artifacts that carry
                // payloads at all; payload-less plugins (grep: none today) skip.
                if (wrote) markerFile.writeText(entry.id)
            }
            return null
        } catch (e: Throwable) {
            return "The plugin payloads couldn't be extracted (${e::class.java.simpleName})."
        }
    }

    /** Delete a plugin's extracted payloads (store Delete = gone). */
    fun deletePayloads(pluginId: String) {
        val root = payloadDir(pluginId)
        if (root.isDirectory) root.deleteRecursively()
    }
}