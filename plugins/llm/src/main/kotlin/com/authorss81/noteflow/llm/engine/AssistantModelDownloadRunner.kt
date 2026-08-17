package com.authorss81.noteflow.llm.engine

import com.authorss81.noteflow.llm.policy.AssistantStoragePolicy
import com.authorss81.noteflow.llm.policy.ModelDownloadPolicy
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.yield

/**
 * PURE JVM — B2-DEPS-05 (phase-77): the pinned, redirect-safe model downloader.
 *
 * Runs a MANUAL redirect loop over `instanceFollowRedirects = false`
 * connections (the B1-NET-05 pattern): the entry URL must be the pinned
 * default (https + huggingface.co), every 3xx `Location` is re-validated by
 * [ModelDownloadPolicy] (https only, no credentials, host inside the
 * HuggingFace family) before the next connection opens, and at most
 * [ModelDownloadPolicy.MAX_REDIRECTS] hops are followed. The body is streamed
 * into [tmp] while being hashed with SHA-256; the download succeeds ONLY when
 * the exact byte count AND the SHA-256 equal the published pin. A stub
 * response is deleted, an off-family/downgrading redirect is refused, a
 * mismatching file is discarded.
 *
 * The connection factory is injectable, so the whole flow is unit-tested with
 * scripted [HttpURLConnection] fakes — no network, no Android.
 *
 * @param expectedSizeBytes expected model bytes (default = the pinned real size).
 * @param expectedSha256 the pinned SHA-256 of the model (default = the
 *   published digest).
 */
class AssistantModelDownloadRunner(
    private val connectionFactory: (String) -> HttpURLConnection = { url ->
        URL(url).openConnection() as HttpURLConnection
    },
    private val expectedSizeBytes: Long = AssistantStoragePolicy.DEFAULT_MODEL_SIZE_BYTES,
    private val expectedSha256: String = AssistantStoragePolicy.DEFAULT_MODEL_SHA256
) {

    /** Outcome of a download attempt. */
    sealed class Outcome {
        data class Success(val bytes: Long) : Outcome()
        data class Failure(val message: String) : Outcome()
    }

    /**
     * Stream [url] (the pinned default URL) into [tmp]. On success [tmp] holds
     * the verified bytes and stays in place (the caller atomically renames it
     * into place). On any failure [tmp] is deleted and a [Outcome.Failure]
     * returned. A coroutine [kotlinx.coroutines.CancellationException] is
     * rethrown (the caller owns its tmp cleanup).
     */
    suspend fun downloadTo(url: String, tmp: File, onProgress: (Float) -> Unit): Outcome {
        var cur: URI = try {
            URI(url)
        } catch (e: Exception) {
            return fail(tmp, "The model URL is invalid.")
        }
        when (val verdict = ModelDownloadPolicy.validateEntry(cur.toString())) {
            is ModelDownloadPolicy.HopVerdict.Refused -> return fail(tmp, verdict.reason)
            is ModelDownloadPolicy.HopVerdict.Ok -> {}
        }
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            repeat(ModelDownloadPolicy.MAX_REDIRECTS + 1) { _ ->
                val conn = connectionFactory(cur.toString())
                try {
                    conn.requestMethod = "GET"
                    conn.connectTimeout = ModelDownloadPolicy.CONNECT_TIMEOUT_MS
                    conn.readTimeout = ModelDownloadPolicy.READ_TIMEOUT_MS
                    // Never auto-follow redirects: every 3xx target is resolved
                    // + re-validated manually below (B2-DEPS-05 / B1-NET-05).
                    conn.instanceFollowRedirects = false
                    conn.setRequestProperty("Accept", "application/octet-stream")
                    val code = conn.responseCode
                    if (code in 300..399) {
                        val next = ModelDownloadPolicy.resolveNextHop(cur, conn.getHeaderField("Location"))
                            ?: return fail(tmp, "The model server redirected without a usable target.")
                        cur = next
                        return@repeat
                    }
                    if (code !in 200..299) {
                        return fail(
                            tmp,
                            "Model download failed (HTTP $code). Maybe the URL is wrong."
                        )
                    }
                    val contentLength = conn.contentLengthLong
                    var total = 0L
                    conn.inputStream.use { stream ->
                        FileOutputStream(tmp).use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = stream.read(buffer)
                                if (read <= 0) break
                                digest.update(buffer, 0, read)
                                out.write(buffer, 0, read)
                                total += read
                                if (contentLength > 0 && total > 0) {
                                    onProgress(
                                        (total.toDouble() / contentLength).toFloat().coerceIn(0f, 1f)
                                    )
                                }
                                yield()
                            }
                        }
                    }
                    val hex = digest.digest().joinToString("") { "%02x".format(it) }
                    return when (
                        ModelDownloadPolicy.verifyDownload(total, hex, expectedSizeBytes, expectedSha256)
                    ) {
                        is ModelDownloadPolicy.DownloadVerdict.Match -> Outcome.Success(total)
                        is ModelDownloadPolicy.DownloadVerdict.SizeMismatch -> fail(
                            tmp,
                            "The downloaded model is the wrong size " +
                                "(${mb(total)} MB received, ${mb(expectedSizeBytes)} MB expected). " +
                                "It may be incomplete or tampered — nothing was saved."
                        )
                        is ModelDownloadPolicy.DownloadVerdict.HashMismatch -> fail(
                            tmp,
                            "Checksum verification failed — the downloaded model does not match its " +
                                "published SHA-256, so it was discarded. Try again."
                        )
                    }
                } finally {
                    conn.disconnect()
                }
            }
            return fail(tmp, "The model server redirected too many times.")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ModelDownloadPolicy.HopRefusedException) {
            return fail(
                tmp,
                e.message ?: "The model host refused an insecure redirect."
            )
        } catch (e: Throwable) {
            return fail(
                tmp,
                "Model download failed (${e::class.java.simpleName}). Check your connection."
            )
        }
    }

    private fun fail(tmp: File, message: String): Outcome {
        tmp.delete()
        return Outcome.Failure(message)
    }

    private fun mb(bytes: Long): Int = (bytes / (1024 * 1024)).toInt()
}