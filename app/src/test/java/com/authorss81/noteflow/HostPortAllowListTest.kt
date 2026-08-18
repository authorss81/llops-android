package com.authorss81.noteflow

import com.authorss81.noteflow.services.HostPortAllowList
import com.authorss81.noteflow.services.WebDavHrefResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2-B1N-05 (phase-142): the plugin allow-lists are now `(scheme, host,
 * effective-port)` triple gates — a URL on the allowed host at a NON-default
 * port (`https://<allowed-host>:8443/...`) can no longer slip through the
 * host-only comparisons (`CompileTimePluginPinStore.isHostAllowListed`,
 * `HttpsManifestTransport`'s host gate, `PluginDownloader`'s gate).
 *
 * The OLD host-only entry form (a bare host name) is retained and documented:
 * it now pins to `https://<host>:443` — the TLS-only default port — so the
 * default-port target keeps working while every other port is refused.
 *
 * Pure JVM.
 */
class HostPortAllowListTest {

    private val host = "plugin-updates.inkflow.app"

    // ---- entry normalization -------------------------------------------------

    @Test
    fun `a bare host entry pins to https plus the default port`() {
        val origin = HostPortAllowList.normalizeEntry(host)!!
        assertEquals("https", origin.scheme)
        assertEquals(host, origin.host)
        assertEquals(443, origin.port)
    }

    @Test
    fun `a bare host with an explicit port keeps that port`() {
        val origin = HostPortAllowList.normalizeEntry("$host:8443")!!
        assertEquals("https", origin.scheme)
        assertEquals(host, origin.host)
        assertEquals(8443, origin.port)
    }

    @Test
    fun `a full URL entry uses its own scheme host and effective port`() {
        assertEquals(
            WebDavHrefResolver.Origin("https", host, 443),
            HostPortAllowList.normalizeEntry("https://$host")
        )
        assertEquals(
            WebDavHrefResolver.Origin("https", host, 8443),
            HostPortAllowList.normalizeEntry("HTTPS://$host:8443")
        )
        assertEquals(
            WebDavHrefResolver.Origin("http", host, 80),
            HostPortAllowList.normalizeEntry("http://$host")
        )
    }

    @Test
    fun `normalization is case-insensitive and folds trailing dots`() {
        val origin = HostPortAllowList.normalizeEntry("HTTPS://${host.uppercase()}./v1")!!
        assertEquals("https", origin.scheme)
        assertEquals(host, origin.host)
        assertEquals(443, origin.port)
    }

    @Test
    fun `unparseable entries normalize to null - fail closed`() {
        assertNull(HostPortAllowList.normalizeEntry(""))
        assertNull(HostPortAllowList.normalizeEntry("   "))
        assertNull(HostPortAllowList.normalizeEntry("not a url"))
        assertNull(HostPortAllowList.normalizeEntry("https:///no-host"))
        assertNull(HostPortAllowList.normalizeEntry("host:notaport"))
        assertNull(HostPortAllowList.normalizeEntry("host:99999"))
        assertNull(HostPortAllowList.normalizeEntry("host:-1"))
        assertNull(HostPortAllowList.normalizeEntry("ftp://$host:21"))
    }

    // ---- matching ------------------------------------------------------------

    @Test
    fun `the allowed host at its default port is accepted`() {
        assertTrue(HostPortAllowList.matches("https://$host/v1/manifest.json", setOf(host)))
        assertTrue(HostPortAllowList.matches("https://$host:443/ocr-1.0.0.apk", setOf(host)))
    }

    @Test
    fun `the allowed host at a non-default port is refused - R2-B1N-05`() {
        assertFalse(HostPortAllowList.matches("https://$host:8443/ocr-1.0.0.apk", setOf(host)))
        assertFalse(HostPortAllowList.matches("http://$host:8443/ocr-1.0.0.apk", setOf(host)))
    }

    @Test
    fun `an https gate never admits a cleartext http target`() {
        assertFalse(HostPortAllowList.matches("http://$host/ocr-1.0.0.apk", setOf(host)))
        // A full http:// entry admits only http targets — never an https one.
        assertTrue(HostPortAllowList.matches("http://$host/ocr-1.0.0.apk", setOf("http://$host")))
        assertFalse(HostPortAllowList.matches("https://$host/ocr-1.0.0.apk", setOf("http://$host")))
    }

    @Test
    fun `an explicitly ported entry admits only that port`() {
        val entries = setOf("$host:8443")
        assertTrue(HostPortAllowList.matches("https://$host:8443/ocr-1.0.0.apk", entries))
        assertFalse(HostPortAllowList.matches("https://$host/ocr-1.0.0.apk", entries))
        assertFalse(HostPortAllowList.matches("https://$host:8444/ocr-1.0.0.apk", entries))
    }

    @Test
    fun `foreign host same port is refused and unparseable targets never match`() {
        assertFalse(HostPortAllowList.matches("https://evil.example.org:443/ocr-1.0.0.apk", setOf(host)))
        assertFalse(HostPortAllowList.matches("not a url", setOf(host)))
        assertFalse(HostPortAllowList.matches("https:///no-host", setOf(host)))
        // A target on an allow-listed entry at default https:443 with a trailing
        // dot host still matches — origin normalization is symmetric.
        assertTrue(HostPortAllowList.matches("https://$host./v1/manifest.json", setOf(host)))
    }
}