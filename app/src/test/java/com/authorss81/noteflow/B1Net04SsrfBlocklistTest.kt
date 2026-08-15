package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.citation.CitationFormatterCore
import com.authorss81.noteflow.plugins.citation.HttpsTitleFetcher
import com.authorss81.noteflow.plugins.citation.TitleFetchException
import com.authorss81.noteflow.plugins.webcapture.WebPageFetchPolicy
import com.authorss81.noteflow.services.SsrfHostPolicy
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1-NET-04 (phase-51): the SSRF blocklist for Web Capture and Citation
 * title-fetch. Pure JVM — no network. Proves:
 *
 * 1. [SsrfHostPolicy] rejects loopback / RFC-1918 / link-local / cloud-metadata
 *    / CGNAT / IPv6 loopback-ULA-link-local / embedded-IPv4 / `.local` &
 *    `localhost` hosts, in every textual encoding a user can paste.
 * 2. [WebPageFetchPolicy.validateUrl] refuses those hosts at entry.
 * 3. [WebPageFetchPolicy.rejectHop] re-applies the SAME policy to a resolved
 *    redirect `Location` (a redirect escape-hatch can never be wider than the
 *    entry validation).
 * 4. [CitationFormatterCore.validateUrl] and [HttpsTitleFetcher] refuse the
 *    same set for the Citation path — the fetcher BEFORE any socket connects.
 */
class B1Net04SsrfBlocklistTest {

    // ---- SsrfHostPolicy: blocklist ------------------------------------------

    @Test
    fun `loopback ipv4 ranges are blocked`() {
        for (host in listOf("localhost", "127.0.0.1", "127.0.0.0", "127.255.255.255", "127.1", "2130706433", "0x7f000001")) {
            assertNotNull("expected $host blocked", SsrfHostPolicy.blockedReason(host))
        }
    }

    @Test
    fun `link-local and cloud metadata ipv4 are blocked`() {
        for (host in listOf("169.254.169.254", "169.254.0.1", "169.254.0.0", "169.254.255.255")) {
            assertNotNull("expected $host blocked", SsrfHostPolicy.blockedReason(host))
        }
    }

    @Test
    fun `private rfc1918 ranges are blocked`() {
        for (host in listOf("10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.255", "192.168.1.1", "192.168.255.255")) {
            assertNotNull("expected $host blocked", SsrfHostPolicy.blockedReason(host))
        }
    }

    @Test
    fun `cgnat and this-network ranges are blocked`() {
        for (host in listOf("100.64.0.1", "100.127.255.255", "0.0.0.0", "0.1.2.3")) {
            assertNotNull("expected $host blocked", SsrfHostPolicy.blockedReason(host))
        }
    }

    @Test
    fun `reserved mDNS local hostnames are blocked`() {
        for (host in listOf("localhost", "localhost.", "[localhost]", "foo.local", "printer.local", "router.localhost", "nas.local")) {
            assertNotNull("expected $host blocked", SsrfHostPolicy.blockedReason(host))
        }
    }

    @Test
    fun `ipv6 loopback unspecified link-local and ULA are blocked`() {
        for (host in listOf("::1", "[::1]", "::", "[::]", "fe80::1", "[fe80::1]", "fc00::1", "fd00::1", "fdff::1", "fd00:ec2::254")) {
            assertNotNull("expected $host blocked", SsrfHostPolicy.blockedReason(host))
        }
    }

    @Test
    fun `ipv6 addresses embedding a private ipv4 are blocked`() {
        for (host in listOf(
            "[::ffff:127.0.0.1]", "[::ffff:192.168.0.1]", "[::ffff:169.254.169.254]",
            "[::ffff:10.0.0.1]", "::ffff:192.168.1.1", "[::127.0.0.1]"
        )) {
            assertNotNull("expected $host blocked", SsrfHostPolicy.blockedReason(host))
        }
    }

    @Test
    fun `public hosts are allowed`() {
        for (host in listOf(
            "example.com", "example.com.", "sub.example.co.uk", "8.8.8.8", "1.2.3.4",
            "172.15.0.1", "172.32.0.1", "192.169.1.1", "11.0.0.1", "169.253.0.1",
            "100.63.0.1", "100.128.0.1", "2001:db8::1", "[2606:4700::1111]"
        )) {
            assertNull("expected $host allowed", SsrfHostPolicy.blockedReason(host))
        }
    }

    // ---- WebPageFetchPolicy.validateUrl (entry gate) --------------------------

    @Test
    fun `validateUrl refuses internal destinations`() {
        for (url in listOf(
            "http://127.0.0.1/", "http://localhost:8080/admin", "http://169.254.169.254/latest/meta-data/iam/security-credentials/",
            "http://192.168.1.1/status", "http://10.0.0.1/", "https://[::1]/", "http://foo.local/", "http://2130706433/"
        )) {
            val out = WebPageFetchPolicy.validateUrl(url)
            assertTrue("expected $url rejected, got $out", out is WebPageFetchPolicy.Either.Error)
        }
    }

    @Test
    fun `validateUrl still accepts public http and https destinations`() {
        for (url in listOf(
            "https://example.com/article", "http://example.com", "https://8.8.8.8/", "example.com/path", "http://1.2.3.4/"
        )) {
            assertTrue(
                "expected $url accepted",
                WebPageFetchPolicy.validateUrl(url) is WebPageFetchPolicy.Either.Valid
            )
        }
    }

    // ---- redirect hop re-validation -------------------------------------------

    private fun hop(location: String, base: String): String? =
        WebPageFetchPolicy.rejectHop(URI(base).resolve(location).toString())

    @Test
    fun `a redirect hop to a blocked host is refused`() {
        val base = "https://example.com/article"
        for (location in listOf(
            "http://127.0.0.1/x", "http://169.254.169.254/latest/meta-data/", "http://192.168.1.1/status",
            "http://10.0.0.1/x", "http://[::1]/x", "http://foo.local/x", "http://localhost/x",
            "//169.254.169.254/latest/meta-data/", "http://2130706433/x"
        )) {
            assertNotNull("expected redirect to $location refused", hop(location, base))
        }
    }

    @Test
    fun `a redirect hop to a public host is allowed`() {
        val base = "https://example.com/article"
        for (location in listOf(
            "https://other.example.org/", "http://8.8.8.8/", "/relative-path", "https://example.com/other"
        )) {
            assertNull("expected redirect to $location allowed", hop(location, base))
        }
    }

    @Test
    fun `a redirect hop to a non-http scheme is refused`() {
        val base = "https://example.com/article"
        val out = hop("ftp://example.com/x", base)
        assertNotNull("expected non-http redirect refused", out)
        assertTrue((out as String).contains("http"))
    }

    // ---- Citation path: entry validation + fetch refusal -----------------------

    @Test
    fun `citation validateUrl refuses internal destinations`() {
        for (url in listOf(
            "http://127.0.0.1/", "http://169.254.169.254/latest/meta-data/", "http://192.168.1.1/status",
            "http://10.0.0.1/", "http://[::ffff:192.168.1.1]/"
        )) {
            val out = CitationFormatterCore.validateUrl(url)
            assertTrue("expected $url rejected, got $out", out is CitationFormatterCore.UrlCheck.Invalid)
        }
    }

    @Test
    fun `citation validateUrl still accepts public destinations`() {
        for (url in listOf("https://example.com", "example.com", "http://example.com/a", "https://8.8.8.8/")) {
            assertTrue(
                "expected $url accepted",
                CitationFormatterCore.validateUrl(url) is CitationFormatterCore.UrlCheck.Valid
            )
        }
    }

    @Test
    fun `title fetcher refuses internal hosts before any connection`() {
        val fetcher = HttpsTitleFetcher()
        for (url in listOf(
            "https://127.0.0.1/x", "https://169.254.169.254/latest/meta-data/", "https://192.168.1.1/status",
            "https://[::1]/", "https://foo.local/x", "https://2130706433/"
        )) {
            try {
                fetcher.fetch(url)
                throw AssertionError("expected $url to be refused")
            } catch (e: TitleFetchException) {
                assertTrue("refusal for $url must explain why", e.message!!.isNotBlank())
            }
        }
    }

    @Test
    fun `title fetcher enforces https only under the default config`() {
        try {
            HttpsTitleFetcher().fetch("http://example.com/a")
            throw AssertionError("http must be refused when httpsOnly")
        } catch (e: TitleFetchException) {
            assertTrue(e.message!!.contains("HTTPS"))
        }
    }

    @Test
    fun `title fetcher still refuses private hosts when http is allowed`() {
        val fetcher = HttpsTitleFetcher(httpsOnly = false)
        try {
            fetcher.fetch("http://127.0.0.1/x")
            throw AssertionError("private host must be refused even with httpsOnly=false")
        } catch (e: TitleFetchException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun `title fetcher rejects malformed input without network`() {
        try {
            HttpsTitleFetcher().fetch("not a url")
            throw AssertionError("malformed URL must be refused")
        } catch (e: TitleFetchException) {
            assertEquals("That doesn't look like a valid URL.", e.message)
        }
    }
}