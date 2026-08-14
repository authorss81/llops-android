package com.authorss81.noteflow

import com.authorss81.noteflow.utils.HttpUserAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1-NET-09 (phase-110): every outbound transport must send a GENERIC,
 * VERSION-LESS User-Agent. A monitoring server must not be able to fingerprint
 * the exact app, app version, OS version or device model and then serve
 * version-specific payloads.
 *
 * These assertions pin the shared constant and forbid a regression back to the
 * old app-identifying values (`Noteflow-Android-WebDAV-Sync/2026`,
 * `Noteflow-Plugin-Runtime/2026`, `InkFlow/1.0`, `Build.MODEL`).
 */
class HttpUserAgentTest {

    @Test
    fun `shared user agent is non-blank`() {
        assertTrue(HttpUserAgent.GENERIC.isNotBlank())
    }

    @Test
    fun `shared user agent is generic and version-less`() {
        assertFalse(
            "UA must not name the app (Noteflow/InkFlow).",
            HttpUserAgent.GENERIC.contains("noteflow", ignoreCase = true)
        )
        assertFalse(
            "UA must not name the app (Noteflow/InkFlow).",
            HttpUserAgent.GENERIC.contains("inkflow", ignoreCase = true)
        )
        assertFalse(
            "UA must not name a subsystem.",
            HttpUserAgent.GENERIC.contains("webdav", ignoreCase = true)
        )
        assertFalse(
            "UA must not name a subsystem.",
            HttpUserAgent.GENERIC.contains("plugin", ignoreCase = true)
        )
        assertFalse(
            "UA must not carry an app version token (the old values carried /2026, /1.0).",
            HttpUserAgent.GENERIC.contains("/2026") || HttpUserAgent.GENERIC.contains("/1.0")
        )
        assertFalse(
            "UA must not leak the OS name/version.",
            HttpUserAgent.GENERIC.contains("android", ignoreCase = true)
        )
        assertTrue(
            "UA should look like the generic Mozilla-compatible client it intends to be.",
            HttpUserAgent.GENERIC.startsWith("Mozilla")
        )
    }

    @Test
    fun `generic user agent is a well-formed http header value`() {
        val ua = HttpUserAgent.GENERIC
        assertFalse("UA must not contain CR/LF (header injection).", ua.contains('\r') || ua.contains('\n'))
        assertEquals(0, ua.count { it == '\u0000' })
    }
}
