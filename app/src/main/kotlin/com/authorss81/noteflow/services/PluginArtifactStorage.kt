package com.authorss81.noteflow.services

import android.content.Context
import android.os.StatFs
import com.authorss81.noteflow.plugins.runtime.PluginArtifactResolver
import com.authorss81.noteflow.plugins.runtime.PluginDownloader
import com.authorss81.noteflow.plugins.runtime.PluginEntry
import java.io.File

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
}