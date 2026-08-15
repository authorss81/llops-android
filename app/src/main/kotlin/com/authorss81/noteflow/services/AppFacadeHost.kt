package com.authorss81.noteflow.services

import com.authorss81.noteflow.plugins.runtime.FacadeHost
import com.authorss81.noteflow.plugins.runtime.FacadeResult
import com.authorss81.noteflow.plugins.runtime.PluginContext
import com.authorss81.noteflow.utils.HttpUserAgent
import java.net.HttpURLConnection
import java.net.URI
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
class AppFacadeHost(
    private val connectionFactory: (String) -> HttpURLConnection = { url ->
        URL(url).openConnection() as HttpURLConnection
    }
) : FacadeHost {

    override fun insertText(text: String): FacadeResult<Unit> = FacadeResult.Failed(
        "Text insertion into the open note is not wired yet in this build (arrives with the ink-to-shape plugin phase)."
    )

    override fun showResult(title: String, body: String): FacadeResult<Unit> = FacadeResult.Failed(
        "Result display is not wired yet in this build."
    )

    /**
     * Real HTTPS-only GET with a size cap.
     *
     * The [FacadeHost] contract is synchronous, so this is a blocking call —
     * callers of the plugin (the store/registry execution path) already run off
     * the main thread. The entry URL AND every 3xx redirect hop is re-validated
     * as an `https` destination off the B1-NET-04 SSRF blocklist
     * ([StrictRedirectPolicy]) and redirects are followed manually with
     * `instanceFollowRedirects = false`, so a downgrading
     * `307 Location: http://…` is refused — never a cleartext fetch.
     */
    override fun httpGet(url: String): FacadeResult<String> {
        var cur = try {
            URI(url)
        } catch (e: Exception) {
            return FacadeResult.Failed("HTTP GET refused: that is not a valid URL.")
        }
        try {
            repeat(StrictRedirectPolicy.MAX_REDIRECTS + 1) { _ ->
                StrictRedirectPolicy.checkTlsHop(cur)
                val connection = connectionFactory(cur.toString())
                try {
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 30_000
                    // B1-NET-05: never auto-follow — 3xx is re-validated per hop.
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("User-Agent", HttpUserAgent.GENERIC)
                    val code = connection.responseCode
                    if (code in 300..399) {
                        val next = StrictRedirectPolicy.resolveNextTlsHop(
                            cur, connection.getHeaderField("Location")
                        ) ?: return FacadeResult.Failed(
                            "HTTP GET refused: the redirect has no usable target."
                        )
                        cur = next
                        return@repeat
                    }
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
            }
            return FacadeResult.Failed("HTTP GET refused: too many redirects.")
        } catch (e: StrictRedirectPolicy.RedirectRefusedException) {
            return FacadeResult.Failed("HTTP GET refused: ${e.message}")
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