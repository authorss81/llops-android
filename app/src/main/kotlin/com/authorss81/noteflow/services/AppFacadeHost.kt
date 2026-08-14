package com.authorss81.noteflow.services

import com.authorss81.noteflow.plugins.runtime.FacadeHost
import com.authorss81.noteflow.plugins.runtime.FacadeResult
import com.authorss81.noteflow.plugins.runtime.PluginContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Production [FacadeHost] for the capability facade (Phase 23).
 *
 * This is the ONLY place the downloaded plugin's granted calls can reach app
 * capabilities — a plugin never holds a reference to it and never receives the
 * DB, keystore, [EncryptionService] or decrypted-content handles.
 *
 * Honest seam note: the note-editor calls ([insertText], [showResult],
 * [readSelection]) and [requestModelDownload] require editor/model flows that
 * later phases wire (Phase 25 ink→shape uses `insertText`; Phase 24 hosts the
 * model-download consent flow). Until then they return [FacadeResult.Failed]
 * with a truthful message — the facade's GRANT/DENY logic is already real and
 * tested, and the host never pretends an operation happened.
 *
 * [httpGet] is REAL: a TLS-only GET with a response-size cap, so a granted
 * plugin can fetch (e.g. web-search results) today.
 */
class AppFacadeHost : FacadeHost {

    override fun insertText(text: String): FacadeResult<Unit> = FacadeResult.Failed(
        "Text insertion into the open note is not wired yet in this build (arrives with the ink-to-shape plugin phase)."
    )

    override fun showResult(title: String, body: String): FacadeResult<Unit> = FacadeResult.Failed(
        "Result display is not wired yet in this build."
    )

    /**
     * Real TLS-only GET with a size cap.
     *
     * The [FacadeHost] contract is synchronous, so this is a blocking call —
     * callers of the plugin (the store/registry execution path) already run off
     * the main thread. A cleartext URL is refused (never a downgrade).
     */
    override fun httpGet(url: String): FacadeResult<String> {
        try {
            if (!url.startsWith("https://")) {
                return FacadeResult.Failed("HTTP GET refused: only HTTPS (TLS) is allowed.")
            }
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = true
                val code = connection.responseCode
                if (code !in 200..299) {
                    return FacadeResult.Failed("HTTP GET failed (HTTP $code).")
                }
                val contentLength = connection.contentLengthLong
                if (contentLength > MAX_FACADE_GET_BYTES) {
                    return FacadeResult.Failed("HTTP GET response too large.")
                }
                val body = connection.inputStream.use { stream ->
                    val bytes = stream.readBytes()
                    if (bytes.size > MAX_FACADE_GET_BYTES) {
                        return FacadeResult.Failed("HTTP GET response too large.")
                    }
                    bytes.toString(Charsets.UTF_8)
                }
                return FacadeResult.Granted(body)
            } finally {
                connection.disconnect()
            }
        } catch (e: Throwable) {
            return FacadeResult.Failed("HTTP GET failed (${e::class.java.simpleName}).")
        }
    }

    override fun readSelection(): FacadeResult<String> = FacadeResult.Failed(
        "Reading the note selection is not wired yet in this build."
    )

    override fun requestModelDownload(sizeBytes: Long): FacadeResult<Unit> = FacadeResult.Failed(
        "Model downloads go through the host consent flow, which lands in a later phase."
    )

    private companion object {
        const val MAX_FACADE_GET_BYTES: Long = 10L * 1024 * 1024
    }
}