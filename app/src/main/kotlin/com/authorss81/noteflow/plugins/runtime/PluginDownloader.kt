package com.authorss81.noteflow.plugins.runtime

import com.authorss81.noteflow.plugins.PluginLogger
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * One download request handed to a [DownloadTransport]. The transport is
 * responsible for streaming the artifact bytes to [target] (opening it in
 * APPEND mode when [resumeFromBytes] > 0, after sending a `Range` request) and
 * for reporting progress as `0f..1f`.
 *
 * @param url the HTTPS artifact URL ([DownloadTransport] must refuse anything
 *   else — TLS only, never a downgrade).
 * @param target the app-private file to write into. The transport appends to
 *   an existing partial file when [resumeFromBytes] > 0.
 * @param resumeFromBytes how many bytes are already in [target] from an
 *   interrupted earlier attempt (the transport resumes from there).
 * @param pinnedCertHash the compile-time pinned certificate hash — the
 *   transport MUST verify the TLS session's leaf certificate against it before
 *   trusting any byte.
 * @param onProgress progress callback `0f..1f` (transport-driven).
 * @param isActive cancellation token — the transport should abort soon when it
 *   returns false.
 */
data class DownloadRequest(
    val url: String,
    val target: File,
    val resumeFromBytes: Long,
    val pinnedCertHash: String,
    val onProgress: (Float) -> Unit,
    val isActive: () -> Boolean
)

/** Result of a [DownloadTransport] attempt. */
sealed class DownloadTransportResult {
    /** The artifact bytes were fully written to the target. */
    data class Completed(val totalBytes: Long) : DownloadTransportResult()

    /** The download failed; [message] is user-facing (never logs content). */
    data class Failed(val message: String) : DownloadTransportResult()
}

/**
 * The transport seam for [PluginDownloader] — keeps the downloader core PURE
 * JVM so the guard logic (TLS-only, size, free space, consent, cancel, resume,
 * temp-file hygiene) is unit-tested with a fake transport. The production
 * implementation ([com.authorss81.noteflow.plugins.runtime.HttpsPluginDownloadTransport])
 * performs a pinned-HTTPS fetch.
 */
fun interface DownloadTransport {
    suspend fun download(request: DownloadRequest): DownloadTransportResult
}

/**
 * Downloads a signed plugin artifact over TLS into app-private storage
 * (Phase 23). PURE JVM core — Android never appears here.
 *
 * Guards enforced BEFORE and DURING any download:
 *
 * - **User-initiated only.** [download] requires [userConsented] == true; the
 *   store refuses the first download without explicit consent.
 * - **TLS only.** The entry's [PluginEntry.downloadUrl] must be `https://`
 *   (re-checked here in addition to [PluginEntry.validationErrors]).
 * - **Never to shared storage.** The artifact is written inside [targetDir]
 *   only — the caller supplies the app-private plugins directory; the
 *   downloader refuses to write outside it.
 * - **Size guard.** [PluginEntry.installSizeBytes] (expected on-device cost)
 *   is compared against the available free space, and any artifact larger than
 *   [MAX_ARTIFACT_BYTES] is refused.
 * - **Resume.** An interrupted download leaves a `<name>.part` file; the next
 *   attempt resumes from it (the transport sends a `Range` request).
 * - **Cancel.** A cancelled coroutine (or an [DownloadRequest.isActive] that
 *   turns false) aborts and deletes the partial file.
 *
 * Progress is forwarded to the store's existing progress flow. Nothing is ever
 * logged about the artifact's contents.
 *
 * @param transport where bytes come from (pinned HTTPS in production).
 * @param freeSpace available-bytes probe for [targetDir] (production uses
 *   `StatFs`; tests inject a constant). Default: unknown ⇒ assume OK.
 * @param allowedDownloadHosts the ONLY hosts artifacts may be fetched from
 *   (B1-NET-03; default [DEFAULT_DOWNLOAD_HOSTS] = the manifest host). A
 *   `downloadUrl` naming any other host is refused before a connection opens.
 * @param logger ids/names + exception class names only.
 */
class PluginDownloader(
    private val transport: DownloadTransport,
    private val freeSpace: (File) -> Long = { Long.MAX_VALUE },
    private val allowedDownloadHosts: Set<String> = DEFAULT_DOWNLOAD_HOSTS,
    private val logger: PluginLogger = PluginLogger.NoOp
) {

    /** Outcome of a download attempt. */
    sealed class DownloadOutcome {
        /** The artifact is on disk at [file], still UNVERIFIED. */
        data class Success(val file: File) : DownloadOutcome()

        /** The download was refused or failed; [message] is user-facing. */
        data class Failed(val message: String) : DownloadOutcome()
    }

    /**
     * The on-disk artifact file name for [entry]: `<sanitized-id>-<version>.apk`.
     * Deterministic — the same entry always maps to the same file, so a
     * re-download overwrites a stale artifact and [PluginArtifactResolver]
     * (production) finds the artifact after a process restart.
     */
    fun artifactFileName(entry: PluginEntry): String =
        artifactFileNameFor(entry)

    /** Downloads [entry]'s artifact into [targetDir] (app-private). Returns the
     *  on-disk file (still UNVERIFIED — call the runtime's verify before load). */
    suspend fun download(
        entry: PluginEntry,
        targetDir: File,
        userConsented: Boolean,
        onProgress: (Float) -> Unit = {},
        isActive: () -> Boolean = { true }
    ): DownloadOutcome {
        if (entry.source != PluginEntrySource.REMOTE) {
            return DownloadOutcome.Failed(
                "plugin '${entry.id}' is bundled — bundled plugins are compiled in and never downloaded."
            )
        }
        if (!userConsented) {
            return DownloadOutcome.Failed(
                "downloading '${entry.id}' requires explicit user consent — consent was not granted."
            )
        }
        val validation = entry.validationErrors()
        if (validation.isNotEmpty()) {
            return DownloadOutcome.Failed(
                "refusing to download '${entry.id}': ${validation.joinToString("; ")}"
            )
        }
        val url = entry.downloadUrl.orEmpty()
        if (!url.startsWith("https://")) {
            return DownloadOutcome.Failed(
                "refusing to download '${entry.id}': only HTTPS (TLS) downloads are allowed (got '$url')."
            )
        }
        // B1-NET-03: artifacts only ever come from the allow-listed download
        // hosts (the manifest host). A re-pointed downloadUrl (compromised
        // manifest, or a catalog entry with a hostile URL) is refused here as
        // the final gate, before a connection opens.
        if (!isHostAllowListed(url, allowedDownloadHosts)) {
            return DownloadOutcome.Failed(
                "refusing to download '${entry.id}': the artifact host is not on the allow-listed plugin download hosts."
            )
        }
        val expectedBytes = entry.installSizeBytes
        if (expectedBytes != null) {
            if (expectedBytes > MAX_ARTIFACT_BYTES) {
                return DownloadOutcome.Failed(
                    "'${entry.id}' is ~${expectedBytes / (1024 * 1024)} MB — larger than the ${MAX_ARTIFACT_BYTES / (1024 * 1024)} MB artifact cap."
                )
            }
            val free = freeSpace(targetDir)
            if (free in 0 until expectedBytes) {
                return DownloadOutcome.Failed(
                    "not enough free space to download '${entry.id}' " +
                        "(need ~${expectedBytes / (1024 * 1024)} MB, ~${free / (1024 * 1024)} MB free)."
                )
            }
        }
        val fileName = artifactFileName(entry)
        // Never write outside the caller-supplied (app-private) directory.
        val target = File(targetDir, fileName)
        if (target.parentFile?.canonicalPath != targetDir.canonicalPath) {
            return DownloadOutcome.Failed("refusing to write outside the app-private plugin directory.")
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return DownloadOutcome.Failed("could not create the plugin storage directory.")
        }
        val tmp = File(targetDir, "$fileName.part")
        val resumeFrom = if (tmp.isFile) tmp.length() else 0L
        logger.lifecycle("remote-download", entry.id, entry.name)
        return try {
            val result = transport.download(
                DownloadRequest(
                    url = url,
                    target = tmp,
                    resumeFromBytes = resumeFrom,
                    pinnedCertHash = entry.pinnedCertHash.orEmpty(),
                    onProgress = onProgress,
                    isActive = isActive
                )
            )
            when (result) {
                is DownloadTransportResult.Completed -> {
                    if (!isActive()) {
                        tmp.delete()
                        DownloadOutcome.Failed("Download cancelled.")
                    } else {
                        val renamed = tmp.renameTo(target)
                        if (renamed) {
                            onProgress(1f)
                            DownloadOutcome.Success(target)
                        } else {
                            tmp.delete()
                            DownloadOutcome.Failed("Could not finalize the download (write failed).")
                        }
                    }
                }
                is DownloadTransportResult.Failed -> {
                    tmp.delete()
                    DownloadOutcome.Failed(result.message)
                }
            }
        } catch (e: CancellationException) {
            tmp.delete()
            throw e
        } catch (e: Throwable) {
            tmp.delete()
            logger.error(entry.id, entry.name, "download threw ${e::class.java.simpleName}")
            DownloadOutcome.Failed(
                "Download failed (${e::class.java.simpleName}). Check your connection and try again."
            )
        }
    }

    companion object {
        /** Hard cap on any single plugin artifact (defensive, in addition to the
         *  per-entry size guard). 500 MB is far above any planned heavy plugin
         *  (ML models download separately via `requestModelDownload`). */
        const val MAX_ARTIFACT_BYTES: Long = 500L * 1024 * 1024

        /** Deterministic artifact file name for [entry] (stateless — callable
         *  from storage/production code without constructing a downloader). */
        fun artifactFileNameFor(entry: PluginEntry): String =
            "${sanitizeId(entry.id)}-${entry.version}.apk"

        private fun sanitizeId(value: String): String {
            val sanitized = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
            return sanitized.ifBlank { "plugin" }
        }
    }
}
