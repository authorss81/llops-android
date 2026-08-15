package com.authorss81.noteflow

import com.authorss81.noteflow.services.WebDavHrefResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the B1-NET-01 (phase-40) fix.
 *
 * A compromised/malicious WebDAV server can answer the PROPFIND listing with
 * `<d:href>https://attacker.example/…nfb</d:href>` (or an absolute
 * `http://169.254.169.254/…` when the user opted into insecure HTTP for a local
 * NAS). The resolver must:
 *   - reject any href whose resolved origin differs from the configured server
 *   - accept relative/root-relative hrefs only when they resolve under the
 *     configured origin
 *   - never let the Basic Authorization header be attached to a non-configured host
 */
class WebDavHrefResolverTest {

    private val server = "https://cloud.example.com/remote.php/dav"
    private val folder = "https://cloud.example.com/remote.php/dav/Noteflow_Vault/"
    private val backup = "noteflow_vault_backup_2026-08-14_AbC123xYz9.nfb"

    private fun expectRejected(serverBaseUrl: String, requestUrl: String, href: String): String {
        val e = assertThrows(IllegalArgumentException::class.java) {
            WebDavHrefResolver.resolveDownloadHref(serverBaseUrl, requestUrl, href)
        }
        return e.message ?: ""
    }

    @Test
    fun offOriginAbsoluteHttpsHrefIsRejected() {
        val msg = expectRejected(
            server, folder,
            "https://attacker.example/steal/$backup"
        )
        assertTrue("expected 'outside the configured server', got: $msg", msg.contains("outside the configured server"))
    }

    @Test
    fun privateIpHrefOnAnyHostIsRejectedEvenWithLocalHttpConfig() {
        // allowInsecureHttp-style local config: the guard permits http, but the
        // href must still point at the SAME configured host (no arbitrary LAN pr.
        // cloud-metadata IP).
        val localServer = "http://192.168.1.50:8080/dav"
        val localFolder = "http://192.168.1.50:8080/dav/Noteflow_Vault/"
        for (evilHref in listOf(
            "http://169.254.169.254/latest/meta-data/$backup",
            "http://192.168.1.51:8080/dav/Noteflow_Vault/$backup",
            "http://10.0.0.7/$backup"
        )) {
            val msg = expectRejected(localServer, localFolder, evilHref)
            assertTrue("expected rejection for $evilHref, got: $msg", msg.contains("outside the configured server"))
        }
    }

    @Test
    fun sameOriginAbsoluteHrefIsAcceptedVerbatim() {
        val url = "$folder$backup"
        assertEquals(url, WebDavHrefResolver.resolveDownloadHref(server, folder, url))
    }

    @Test
    fun rootRelativeHrefResolvesUnderConfiguredOrigin() {
        val expected = "$folder$backup"
        assertEquals(
            expected,
            WebDavHrefResolver.resolveDownloadHref(server, folder, "/remote.php/dav/Noteflow_Vault/$backup")
        )
    }

    @Test
    fun bareFileNameHrefResolvesAgainstRequestUrl() {
        // RFC 4918 hrefs may be relative to the PROPFIND request URL.
        assertEquals(folder + backup, WebDavHrefResolver.resolveDownloadHref(server, folder, backup))
    }

    @Test
    fun protocolRelativeHrefIsRejected() {
        val msg = expectRejected(server, folder, "//attacker.example/steal/$backup")
        assertTrue("expected network-path rejection, got: $msg", msg.contains("network-path reference"))
    }

    @Test
    fun portMismatchOnSameHostIsRejected() {
        expectRejected(server, folder, "https://cloud.example.com:8443/steal/$backup")
    }

    @Test
    fun defaultPortIsEquivalentToExplicit443() {
        val url = "https://cloud.example.com:443/remote.php/dav/Noteflow_Vault/$backup"
        assertEquals(url, WebDavHrefResolver.resolveDownloadHref(server, folder, url))
    }

    @Test
    fun schemeDowngradeOnSameHostIsRejected() {
        // Even with https configured, a server-supplied http href on the SAME
        // host must not be followed (cleartext downgrade of credentials).
        expectRejected(server, folder, "http://cloud.example.com/steal/$backup")
    }

    @Test
    fun upperCaseSchemeAndHostNormalizeToSameOrigin() {
        val href = "HTTPS://CLOUD.EXAMPLE.COM/remote.php/dav/Noteflow_Vault/$backup"
        assertEquals(href, WebDavHrefResolver.resolveDownloadHref(server, folder, href))
    }

    @Test
    fun hostTrailingDotAndCaseAreNormalized() {
        // Config host differs by case + trailing dot — still the same origin.
        val altServer = "https://Cloud.Example.COM./remote.php/dav"
        val url = "https://cloud.example.com/remote.php/dav/Noteflow_Vault/$backup"
        assertEquals(url, WebDavHrefResolver.resolveDownloadHref(altServer, folder, url))
    }

    @Test
    fun backslashPathEscapesAreRejected() {
        expectRejected(server, folder, "$folder$backup/../../..")
    }

    @Test
    fun emptyMalformedAndWhitespaceHrefsAreRejected() {
        expectRejected(server, folder, "   ")
        expectRejected(server, folder, "https://exa mple.com/steal/$backup")
    }

    @Test
    fun nonHttpSchemeHrefIsRejected() {
        expectRejected(server, folder, "ftp://cloud.example.com/$backup")
    }

    @Test
    fun requireConfiguredServerOriginRejectsAnyOtherHost() {
        val e = assertThrows(IllegalStateException::class.java) {
            WebDavHrefResolver.requireConfiguredServerOrigin(
                "https://attacker.example/steal/$backup",
                server
            )
        }
        assertTrue(
            "expected configured-server rejection, got: $e.message",
            (e.message ?: "").contains("not the configured server")
        )

        // Same host, wrong scheme, is also a different origin.
        assertThrows(IllegalStateException::class.java) {
            WebDavHrefResolver.requireConfiguredServerOrigin(
                "http://cloud.example.com/remote.php/dav/$backup",
                server
            )
        }
    }

    @Test
    fun requireConfiguredServerOriginAcceptsSameOriginCold() {
        // No exception = no Basic header to a wrong host.
        WebDavHrefResolver.requireConfiguredServerOrigin("$folder$backup", server)
        WebDavHrefResolver.requireConfiguredServerOrigin(server + "/", server)
    }

    @Test
    fun syncStyleXmlListingWithEvilHrefIsRejectedEndToEnd() {
        // Mirrors the PROPFIND response parsing in WebDavSyncService
        // (downloadLatestEncryptedVault): extract the hrefs with the same regex,
        // then the resolver must throw for the attacker entry.
        val xml = """
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>https://cloud.example.com/remote.php/dav/Noteflow_Vault/$backup</d:href></d:response>
              <d:response><d:href>https://attacker.example/steal/$backup</d:href></d:response>
            </d:multistatus>
        """.trimIndent()
        val regex = Regex("<d:href>([^<]+noteflow_vault_backup_[^<]+\\.nfb)</d:href>", RegexOption.IGNORE_CASE)
        val hrefs = regex.findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(2, hrefs.size)
        val e = assertThrows(IllegalArgumentException::class.java) {
            WebDavHrefResolver.resolveDownloadHref(server, folder, hrefs.last())
        }
        assertTrue(
            "expected 'outside the configured server', got: $e.message",
            (e.message ?: "").contains("outside the configured server")
        )
    }

    @Test
    fun syncStyleXmlListingWithOnlySafeHrefsResolves() {
        val xml = """
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/remote.php/dav/Noteflow_Vault/noteflow_vault_backup_2026-08-14_OtherTok2.nfb</d:href></d:response>
            </d:multistatus>
        """.trimIndent()
        val regex = Regex("<d:href>([^<]+noteflow_vault_backup_[^<]+\\.nfb)</d:href>", RegexOption.IGNORE_CASE)
        val hrefs = regex.findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(1, hrefs.size)
        val resolved = WebDavHrefResolver.resolveDownloadHref(server, folder, hrefs.first())
        assertEquals(
            "https://cloud.example.com/remote.php/dav/Noteflow_Vault/noteflow_vault_backup_2026-08-14_OtherTok2.nfb",
            resolved
        )
    }
}