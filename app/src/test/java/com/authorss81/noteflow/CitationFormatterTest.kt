package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.CitationOutcome
import com.authorss81.noteflow.plugins.CitationPlugin
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginManager
import com.authorss81.noteflow.plugins.PluginPermission
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.PluginResult
import com.authorss81.noteflow.plugins.citation.CitationFormatterCore
import com.authorss81.noteflow.plugins.citation.CitationFormatterCore.UrlCheck
import com.authorss81.noteflow.plugins.citation.CitationFormatterPluginImpl
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 26 Citation Formatter plugin tests (pure JVM, no network).
 *
 * Covers the payload building ([title](url) links), URL validation + https
 * upgrade, host-derived fallback labels, plain-text `<title>` extraction from
 * HTML, and plugin behaviour with an injected title fetcher (fetched title,
 * offline fallback, supplied title, invalid URL).
 */
class CitationFormatterTest {

    // ---- payload building ---------------------------------------------------

    @Test
    fun `builds a clean markdown link from a url and title`() {
        assertEquals(
            "[Kotlin](https://kotlinlang.org)",
            CitationFormatterCore.buildCitation("https://kotlinlang.org", "Kotlin")
        )
    }

    @Test
    fun `falls back to a host-derived label when no title is given`() {
        assertEquals(
            "[kotlinlang.org](https://kotlinlang.org/docs/home.html)",
            CitationFormatterCore.buildCitation("https://kotlinlang.org/docs/home.html", null)
        )
        assertEquals(
            "[example.com](https://example.com)",
            CitationFormatterCore.buildCitation("https://example.com", "   ")
        )
    }

    @Test
    fun `title is trimmed and newlines flattened`() {
        assertEquals(
            "[My Page](https://x.test)",
            CitationFormatterCore.buildCitation("https://x.test", "  My\n  Page  ")
        )
    }

    // ---- URL validation -------------------------------------------------------

    @Test
    fun `bare hostnames are upgraded to https`() {
        val check = CitationFormatterCore.validateUrl("example.com")
        assertTrue(check is UrlCheck.Valid)
        assertEquals("https://example.com", (check as UrlCheck.Valid).url)
    }

    @Test
    fun `non-http schemes are rejected`() {
        assertTrue(CitationFormatterCore.validateUrl("ftp://example.com/x") is UrlCheck.Invalid)
        assertTrue(CitationFormatterCore.validateUrl("javascript:alert(1)") is UrlCheck.Invalid)
    }

    @Test
    fun `hostless and blank urls are rejected`() {
        assertTrue(CitationFormatterCore.validateUrl("") is UrlCheck.Invalid)
        assertTrue(CitationFormatterCore.validateUrl("https://") is UrlCheck.Invalid)
    }

    // ---- title extraction (plain-text HTML) ------------------------------------

    @Test
    fun `extracts and decodes a title from an html payload`() {
        val html = "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<title>  Hello &amp; World  </title></head><body></body></html>"
        assertEquals("Hello & World", CitationFormatterCore.extractHtmlTitle(html))
    }

    @Test
    fun `title extraction handles entities and numeric refs`() {
        assertEquals("A \"B\" <C>", CitationFormatterCore.extractHtmlTitle(
            "<title>A &quot;B&quot; &lt;C&gt;</title>"
        ))
        assertEquals("caf\u00e9", CitationFormatterCore.extractHtmlTitle("<title>caf&#233;</title>"))
    }

    @Test
    fun `title extraction returns null when absent or blank`() {
        assertNull(CitationFormatterCore.extractHtmlTitle(""))
        assertNull(CitationFormatterCore.extractHtmlTitle("<html><head></head></html>"))
        assertNull(CitationFormatterCore.extractHtmlTitle("<title>   </title>"))
    }

    // ---- plugin behaviour (injected title fetcher, no network) ------------------

    @Test
    fun `plugin uses a supplied title without any fetch`() = runBlocking {
        val plugin = CitationFormatterPluginImpl(
            titleFetcher = { throw AssertionError("fetcher must not be called") }
        )
        val outcome = plugin.formatCitation("https://kotlinlang.org", "Kotlin")
        assertTrue(outcome is CitationOutcome.Success)
        val success = (outcome as CitationOutcome.Success)
        assertEquals("[Kotlin](https://kotlinlang.org)", success.markdown)
        assertTrue(!success.titleFetched)
    }

    @Test
    fun `plugin fetches the title over https when none supplied`() = runBlocking {
        val plugin = CitationFormatterPluginImpl(
            titleFetcher = { "Fetched Title" }
        )
        val outcome = plugin.formatCitation("https://example.com/a", null)
        assertTrue(outcome is CitationOutcome.Success)
        val success = (outcome as CitationOutcome.Success)
        assertEquals("[Fetched Title](https://example.com/a)", success.markdown)
        assertTrue(success.titleFetched)
    }

    @Test
    fun `plugin honestly falls back to a host label when the fetch fails`() = runBlocking {
        val plugin = CitationFormatterPluginImpl(
            titleFetcher = { throw IOException("offline") }
        )
        val outcome = plugin.formatCitation("https://example.com/a", null)
        assertTrue(outcome is CitationOutcome.Success)
        val success = (outcome as CitationOutcome.Success)
        assertEquals("[example.com](https://example.com/a)", success.markdown)
        assertTrue(!success.titleFetched)
    }

    @Test
    fun `plugin rejects an unusable url before any fetch`() = runBlocking {
        val plugin = CitationFormatterPluginImpl(
            titleFetcher = { throw AssertionError("fetcher must not be called") }
        )
        val outcome = plugin.formatCitation("not a url", null)
        assertTrue(outcome is CitationOutcome.Error)
    }

    @Test
    fun `plugin routes through the manager with the citation capability`() = runBlocking {
        val plugin = CitationFormatterPluginImpl(titleFetcher = { "T" })
        val registry = PluginRegistry(
            InMemoryEnableStore(),
            plugins = listOf(plugin),
            currentApiLevel = 26
        )
        registry.setEnabled(plugin.id, enabled = true)
        val manager = PluginManager(registry)
        val result = manager.withPluginAsync(PluginCapability.CitationFormatter, null) { p ->
            (p as CitationPlugin).formatCitation("https://example.com", null)
        }
        assertTrue(result is PluginResult.Success)
    }

    // ---- manifest -----------------------------------------------------------

    @Test
    fun `manifest declares citation capability and internet permission`() {
        val plugin = CitationFormatterPluginImpl()
        assertTrue(PluginCapability.CitationFormatter in plugin.capabilities)
        assertTrue(PluginPermission.Internet in plugin.manifest.permissions)
        assertTrue(plugin.id.startsWith("com.authorss81.noteflow.plugins.citation"))
    }
}