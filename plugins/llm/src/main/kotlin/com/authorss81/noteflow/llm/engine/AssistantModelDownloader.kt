package com.authorss81.noteflow.llm.engine

import android.content.Context
import android.os.StatFs
import com.authorss81.noteflow.llm.policy.AssistantStoragePolicy
import com.authorss81.noteflow.llm.policy.ModelDownloadPolicy
import java.io.File
import java.security.MessageDigest

/**
 * Platform model downloader for the model plugin. Fetches the PINNED GGUF
 * (default URL + exact size + SHA-256, B2-DEPS-05) into app-private files
 * (`filesDir/noteflow/assistant/<name>`) with a free-space guard,
 * size-progress reporting and cancellation support. NEVER stores into cacheDir
 * (models must survive cache clears) and NEVER logs model bytes.
 *
 * The heavy lifting is delegated to the pure-JVM [AssistantModelDownloadRunner]
 * (manual redirect-validation loop + streaming SHA-256); this class only owns
 * the Android-specific pieces: the on-disk location, the free-space preflight
 * and the atomic rename. Both the download and the EXISTING-FILE fast path go
 * through [ModelDownloadPolicy.verifyDownload] — a file that does not exactly
 * match the published size + SHA-256 (partial, stale, poisoned…) is deleted
 * and re-downloaded, never served to the engine.
 *
 * @param modelName leaf filename the downloaded file gets.
 */
class AssistantModelDownloader(private val modelName: String) {

    class Result(val file: File?, val error: String?)

    fun dirFor(context: Context): File =
        File(context.filesDir, "noteflow/assistant").apply { if (!exists()) mkdirs() }

    fun fileFor(context: Context): File = File(dirFor(context), modelName)

    fun freeBytes(path: File): Long = try {
        StatFs(path.absolutePath).availableBytes
    } catch (_: Throwable) {
        -1L
    }

    /**
     * Download the pinned default model to app-private files. [onProgress]
     * receives 0f..1f as bytes stream in. Cancellation is honoured: an
     * exception (incl. a cancelled coroutine) aborts and deletes the
     * half-written temp file.
     */
    suspend fun download(
        context: Context,
        onProgress: (Float) -> Unit
    ): Result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val target = fileFor(context)
        // B2-DEPS-05: an existing file is trusted ONLY when it matches the
        // pinned identity. Anything else is discarded and re-downloaded —
        // a "plausible" (>1 MB) but wrong file can no longer be served.
        if (target.exists() && target.length() > 0L) {
            when (
                ModelDownloadPolicy.verifyDownload(target.length(), sha256Hex(target))
            ) {
                is ModelDownloadPolicy.DownloadVerdict.Match ->
                    return@withContext Result(target, null)
                else -> target.delete()
            }
        }
        val dir = dirFor(context)
        val free = freeBytes(dir)
        val space = AssistantStoragePolicy.checkSpace(
            free,
            AssistantStoragePolicy.DEFAULT_MODEL_SIZE_BYTES
        )
        if (space is AssistantStoragePolicy.SpaceCheck.Insufficient) {
            return@withContext Result(
                null,
                "Not enough storage — need ${mb(space.neededBytes)} free, only ${mb(space.availableBytes)}. " +
                    "Free some space and try again."
            )
        }
        val tmp = File(dir, "$modelName.part")
        val outcome = try {
            AssistantModelDownloadRunner().downloadTo(
                url = AssistantStoragePolicy.DEFAULT_MODEL_URL,
                tmp = tmp,
                onProgress = onProgress
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            tmp.delete()
            throw e
        }
        when (outcome) {
            is AssistantModelDownloadRunner.Outcome.Success -> {
                if (!tmp.renameTo(target)) {
                    target.delete()
                    Result(null, "Download failed.")
                } else {
                    onProgress(1f)
                    Result(target, null)
                }
            }
            is AssistantModelDownloadRunner.Outcome.Failure -> {
                tmp.delete()
                Result(null, outcome.message)
            }
        }
    }

    /** Streaming SHA-256 of [file] (bounded — never the whole model in heap). */
    private fun sha256Hex(file: File): String = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Throwable) {
        ""
    }

    private fun mb(bytes: Long): Int = (bytes / (1024 * 1024)).toInt()
}