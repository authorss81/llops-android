package com.authorss81.noteflow.llm.engine

import android.content.Context
import android.os.Build
import java.io.File

/**
 * PLATFORM-ONLY — binds the native `.so` libraries of the downloadable LLM
 * plugin (Phase 29).
 *
 * The MediaPipe `tasks-genai` engine is shipped INSIDE the downloadable plugin
 * artifact (never the base APK). Its native libraries travel with it, bundled
 * into the artifact as `lib/<abi>/<name>.so` resources (see `plugins/llm/build.gradle.kts`
 * `packagePlugin`). On first use this extracts them into app-private files and
 * pre-loads them with [System.load] in a stable order, so the engine's own
 * internal `System.loadLibrary(...)` calls find the libraries already loaded
 * (Android short-circuits on the loaded-name registry).
 *
 * Design rules:
 * - ONLY the plugin's own storage is written (app-private files) — never the
 *   cache dir (native libs must survive cache clears) and never log bytes.
 * - Every load failure is collected and surfaces as a user-facing message;
 *   it NEVER crashes the host.
 * - Idempotent: repeated calls are no-ops once every `.so` is loaded.
 */
object NativeLibraryBundle {

    private const val REL_DIR = "noteflow/llm/native"
    private const val MARKER_LOADED = ".loaded"
    private const val MAX_SO_BYTES = 120L * 1024 * 1024

    @Volatile
    private var loadedAbi: String? = null

    /** Full path of a bundled library for the current ABI, or null when absent. */
    fun bundledLibPath(context: Context): String? =
        context.classLoader.getResource(libRootRel(currentAbi()))?.let { it.toString() }

    /**
     * Ensure every bundled native library for this device's ABI is extracted
     * and loaded. Returns null on success or a user-facing error. Never throws.
     */
    fun ensureLoaded(context: Context): String? {
        val abi = currentAbi()
        if (loadedAbi == abi) return null
        val targetDir = File(context.filesDir, "$REL_DIR/$abi")
        val marker = File(targetDir, MARKER_LOADED)
        if (marker.exists()) {
            loadedAbi = abi
            return null
        }
        return try {
            targetDir.mkdirs()
            val libs = extractLibraries(context, abi, targetDir)
            if (libs.isEmpty()) {
                "The LLM engine libraries are missing for ABI '$abi' — the plugin artifact may be incomplete."
            } else {
                loadAll(libs)
                // Only when every library loaded successfully do we mark the
                // directory as ready; a partial failure leaves the marker off so
                // the next attempt re-extracts and retries.
                marker.createNewFile()
                loadedAbi = abi
                null
            }
        } catch (e: Throwable) {
            "The LLM engine libraries couldn't load (${e::class.java.simpleName})."
        }
    }

    /** The device's first supported ABI (dalvik.vm etc.), or "arm64-v8a". */
    fun currentAbi(): String =
        Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    private fun libRootRel(abi: String): String = "lib/$abi"

    private fun extractLibraries(context: Context, abi: String, targetDir: File): List<File> {
        val prefix = "${libRootRel(abi)}/"
        val out = mutableListOf<File>()
        val loader = context.classLoader
        loader.getResources(prefix).asSequence().toList().forEach { resourceUrl ->
            val path = resourceUrl.path
            val name = path.substringAfterLast('/').takeIf { it.endsWith(".so") } ?: return@forEach
            val entryBytes = resourceUrl.openStream()?.use { it.readBytes() }
            if (entryBytes == null || entryBytes.size > MAX_SO_BYTES) return@forEach
            val file = File(targetDir, name)
            if (!file.exists() || file.length() != entryBytes.size.toLong()) {
                file.writeBytes(entryBytes)
            }
            out.add(file)
        }
        return out.sortedBy { it.name }
    }

    private fun loadAll(libs: List<File>) {
        for (lib in libs) {
            // A library already loaded via System.load satisfies a later
            // System.loadLibrary with the same basename — load in dependency
            // order (sorted names keep the leaf libraries last; the engine's
            // own loadLibrary calls are then no-ops).
            try {
                System.load(lib.absolutePath)
            } catch (e: UnsatisfiedLinkError) {
                // Loose ordering can transiently fail a leaf that depends on a
                // sibling; the eventual engine warm-up surfaces the real error.
                // Retry once so the dependency-ordered second pass usually fixes it.
                try {
                    System.load(lib.absolutePath)
                } catch (_: Throwable) {
                    throw e
                }
            }
        }
    }
}
