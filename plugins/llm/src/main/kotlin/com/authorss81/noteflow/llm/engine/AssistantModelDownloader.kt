package com.authorss81.noteflow.llm.engine

import android.content.Context
import android.os.StatFs
import com.authorss81.noteflow.llm.policy.AssistantStoragePolicy
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Platform model downloader for the model plugin. Fetches the GGUF into
 * app-private files (`filesDir/noteflow/assistant/<name>`) with a free-space
 * guard, size-progress reporting and cancellation support. NEVER stores into
 * cacheDir (models must survive cache clears) and NEVER logs model bytes.
 *
 * @param modelName leaf filename the downloaded file gets.
 */
class AssistantModelDownloader(private val modelName: String) {

    class Result(val file: File?, val error: String?)

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 40_000
    }

    fun dirFor(context: Context): File =
        File(context.filesDir, "noteflow/assistant").apply { if (!exists()) mkdirs() }

    fun fileFor(context: Context): File = File(dirFor(context), modelName)

    fun freeBytes(path: File): Long = try {
        StatFs(path.absolutePath).availableBytes
    } catch (_: Throwable) {
        -1L
    }

    /**
     * Download [url] to app-private files. [onProgress] receives 0f..1f as
     * bytes stream in. Cancellation is honoured: an exception (incl. a
     * cancelled coroutine) aborts and deletes the half-written temp file.
     */
    suspend fun download(
        context: Context,
        url: String,
        expectedSizeBytes: Long,
        onProgress: (Float) -> Unit
    ): Result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val target = fileFor(context)
        if (target.exists() && AssistantStoragePolicy.isPlausibleModelFile(target.length())) {
            return@withContext Result(target, null)
        }
        val dir = dirFor(context)
        val free = freeBytes(dir)
        val space = AssistantStoragePolicy.checkSpace(free, expectedSizeBytes)
        if (space is AssistantStoragePolicy.SpaceCheck.Insufficient) {
            return@withContext Result(
                null,
                "Not enough storage — need ${mb(space.neededBytes)} free, only ${mb(space.availableBytes)}. " +
                    "Free some space and try again."
            )
        }
        val tmp = File(dir, "$modelName.part")
        var connection: HttpURLConnection? = null
        var stream: InputStream? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/octet-stream")
            }
            val contentLength = connection.contentLengthLong
            val code = connection.responseCode
            if (code !in 200..299) {
                return@withContext Result(null, "Model download failed (HTTP $code). Maybe the URL is wrong.")
            }
            stream = connection.inputStream
            var total = 0L
            FileOutputStream(tmp).use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    total += read
                    if (contentLength > 0) {
                        onProgress((total.toDouble() / contentLength).toFloat().coerceIn(0f, 1f))
                    }
                    kotlinx.coroutines.yield()
                }
            }
            if (!AssistantStoragePolicy.isPlausibleModelFile(total)) {
                tmp.delete()
                return@withContext Result(null, "The downloaded file looks invalid (${mb(total)}). Try again.")
            }
            tmp.takeIf { it.exists() }?.renameTo(target) ?: run { target.delete(); return@withContext Result(null, "Download failed.") }
            // Empty file semantics: if the server reported nothing, bail.
            onProgress(1f)
            Result(target, null)
        } catch (e: kotlinx.coroutines.CancellationException) {
            tmp.delete()
            throw e
        } catch (e: Throwable) {
            tmp.delete()
            Result(null, "Model download failed (${e::class.java.simpleName}). Check your connection.")
        } finally {
            stream?.close()
            connection?.disconnect()
        }
    }

    private fun mb(bytes: Long): Int = (bytes / (1024 * 1024)).toInt()
}