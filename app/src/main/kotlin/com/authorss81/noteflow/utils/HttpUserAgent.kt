package com.authorss81.noteflow.utils

/**
 * Single shared HTTP User-Agent for every outbound transport (WebDAV, plugin
 * manifest/artifact downloads, web capture, LocalSend).
 *
 * B1-NET-09 (phase-110): a monitoring server must not be able to fingerprint
 * the exact app, app version, OS version or device model and then serve
 * version-specific payloads. The string is deliberately generic AND version-
 * less so no transport identifies the client beyond "Mozilla-compatible".
 */
object HttpUserAgent {
    const val GENERIC = "Mozilla/5.0"
}
