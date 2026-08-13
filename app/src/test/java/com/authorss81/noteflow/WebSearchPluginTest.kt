package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.PluginAvailability
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginManager
import com.authorss81.noteflow.plugins.PluginPermission
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.PluginResult
import com.authorss81.noteflow.plugins.WebSearchOutcome
import com.authorss81.noteflow.plugins.WebSearchPlugin
import com.authorss81.noteflow.plugins.WebSearchResult
import com.authorss81.noteflow.plugins.websearch.DuckDuckGoQueryUrl
import com.authorss81.noteflow.plugins.websearch.DuckDuckGoResponseParser
import com.authorss81.noteflow.plugins.websearch.DuckDuckGoSearchException
import com.authorss81.noteflow.plugins.websearch.DuckDuckGoWebSearchPlugin
import com.authorss81.noteflow.plugins.websearch.WebSearchAvailability
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 12 Web Search plugin tests (pure JVM, no network).
 *
 * The DuckDuckGo HTTP client is deliberately NOT exercised here (no network in
 * unit tests). Covered instead: the real parsing path over sample JSON payloads,
 * empty/error response handling, query-URL building, the offline availability
 * gate, and plugin routing/error mapping with an injected search backend.
 */
class WebSearchPluginTest {

    private val sampleJson = """
        {
          "Heading": "Kotlin",
          "AbstractText": "Kotlin is a cross-platform, statically typed programming language.",
          "AbstractURL": "https://en.wikipedia.org/wiki/Kotlin_(programming_language)",
          "Abstract": "Kotlin is a cross-platform...",
          "Type": "D",
          "RelatedTopics": [
            {
              "Text": "Kotlin (programming language) - Wikipedia",
              "FirstURL": "https://en.wikipedia.org/wiki/Kotlin_(programming_language)",
              "Icon": {"Height": "16", "URL": "https://icons.duckduckgo.com/ip3/en.wikipedia.org.ico"}
            },
            {
              "Text": "Kotlin (footballer) - Wikipedia",
              "FirstURL": "https://en.wikipedia.org/wiki/Kotlin_(footballer)",
              "Icon": {"Height": "16"}
            },
            {
              "Name": "Software",
              "Topics": [
                {
                  "Text": "Kotlin Multiplatform - Wikipedia",
                  "FirstURL": "https://en.wikipedia.org/wiki/Kotlin_Multiplatform"
                }
              ]
            }
          ]
        }
    """.trimIndent()

    // ---- parser: real JSON handling ---------------------------------------

    @Test
    fun `parser extracts abstract and related results with dedupe by url`() {
        val results = DuckDuckGoResponseParser.parse(sampleJson)
        // Abstract + footballer + nested Kotlin Multiplatform = 3 unique URLs.
        // The abstract's URL == related topic 1's URL, so that pair dedupes to one.
        assertEquals(3, results.size)

        val byUrl = results.associateBy { it.url }
        assertEquals("Kotlin", byUrl["https://en.wikipedia.org/wiki/Kotlin_(programming_language)"]?.title)
        assertEquals("Kotlin (footballer)", byUrl["https://en.wikipedia.org/wiki/Kotlin_(footballer)"]?.title)
        assertEquals(
            "Kotlin Multiplatform",
            byUrl["https://en.wikipedia.org/wiki/Kotlin_Multiplatform"]?.title
        )
        assertTrue(results.all { it.title.isNotBlank() && it.url.startsWith("https://") })
    }

    @Test
    fun `parser handles an empty response object as no results`() {
        assertTrue(DuckDuckGoResponseParser.parse("{}").isEmpty())
        assertTrue(DuckDuckGoResponseParser.parse("""{"RelatedTopics":[]}""").isEmpty())
    }

    @Test
    fun `parser handles blank input as no results`() {
        assertTrue(DuckDuckGoResponseParser.parse("").isEmpty())
        assertTrue(DuckDuckGoResponseParser.parse("   ").isEmpty())
    }

    @Test
    fun `parser converts malformed json into a typed user-facing error`() {
        try {
            DuckDuckGoResponseParser.parse("this is { not json")
            fail("malformed JSON must fail loudly, not return bogus results")
        } catch (e: DuckDuckGoSearchException) {
            assertTrue(e.message.orEmpty().isNotBlank())
        }
    }

    @Test
    fun `parser ignores entries without a real url`() {
        val json = """
            {"RelatedTopics":[
              {"Text":"no url at all"},
              {"Text":"has url","FirstURL":"not-an-url"},
              {"Text":"ok","FirstURL":"https://example.com/x"}
            ]}
        """.trimIndent()
        val results = DuckDuckGoResponseParser.parse(json)
        assertEquals(1, results.size)
        assertEquals("https://example.com/x", results[0].url)
    }

    // ---- query URL building -------------------------------------------------

    @Test
    fun `query url builder encodes and sets the ddg params`() {
        val url = DuckDuckGoQueryUrl.build("Kotlin coroutines")
        // build() trims the base's trailing slash (robust to callers passing
        // with/without one), so compare against the normalized base.
        assertTrue(url.startsWith(DuckDuckGoQueryUrl.DEFAULT_BASE.trimEnd('/')))
        assertTrue(url.contains("q=Kotlin+coroutines"))
        assertTrue(url.contains("format=json"))
        assertTrue(url.contains("no_html=1"))
        assertTrue(url.contains("no_redirect=1"))
        assertTrue(url.contains("d=1"))
    }

    // ---- availability gate (pure slice of the INTERNET check) --------------

    @Test
    fun `availability gate ok when permission granted and network present`() {
        assertEquals(PluginAvailability.Ok, WebSearchAvailability.evaluate(true, true))
    }

    @Test
    fun `availability gate reports offline when no network`() {
        val offline = WebSearchAvailability.evaluate(true, false)
        assertTrue(offline is PluginAvailability.Unavailable)
        assertTrue((offline as PluginAvailability.Unavailable).reason.contains("Offline"))
    }

    @Test
    fun `availability gate reports missing internet permission`() {
        val denied = WebSearchAvailability.evaluate(false, true)
        assertTrue(denied is PluginAvailability.Unavailable)
        assertTrue((denied as PluginAvailability.Unavailable).reason.contains("Internet"))
    }

    // ---- plugin behavior (injected backend, no network) --------------------

    @Test
    fun `plugin routes a real result list through the manager`() = runBlocking {
        val plugin = DuckDuckGoWebSearchPlugin(
            searchImpl = { query ->
                listOf(WebSearchResult("Kotlin", "https://kotlinlang.org", "Official site"))
            }
        )
        val registry = PluginRegistry(
            InMemoryEnableStore(),
            plugins = listOf(plugin),
            currentApiLevel = 26
        )
        registry.setEnabled(plugin.id, enabled = true)
        val manager = PluginManager(registry)

        val result = manager.withPluginAsync(PluginCapability.WebSearch, null) { p ->
            (p as WebSearchPlugin).searchWeb("Kotlin")
        }

        assertTrue(result is PluginResult.Success)
        val outcome = (result as PluginResult.Success).value as WebSearchOutcome.Success
        assertEquals("Kotlin", outcome.results[0].title)
        assertEquals("https://kotlinlang.org", outcome.results[0].url)
    }

    @Test
    fun `plugin maps a network failure to a clear offline error`() = runBlocking {
        val plugin = DuckDuckGoWebSearchPlugin(
            searchImpl = { throw IOException("no network") }
        )
        val outcome = plugin.searchWeb("Kotlin")
        assertTrue(outcome is WebSearchOutcome.Error)
        assertTrue((outcome as WebSearchOutcome.Error).message.contains("connection"))
    }

    @Test
    fun `blank query returns an error without calling the backend`() = runBlocking {
        val plugin = DuckDuckGoWebSearchPlugin(
            searchImpl = { throw AssertionError("backend must not be called") }
        )
        val outcome = plugin.searchWeb("   \n ")
        assertTrue(outcome is WebSearchOutcome.Error)
    }

    @Test
    fun `plugin propagates cancellation instead of swallowing it`() = runBlocking {
        val plugin = DuckDuckGoWebSearchPlugin(
            searchImpl = { throw kotlinx.coroutines.CancellationException("cancelled") }
        )
        try {
            plugin.searchWeb("Kotlin")
            fail("CancellationException must propagate")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // expected
        }
    }

    // ---- manifest -----------------------------------------------------------

    @Test
    fun `plugin manifest declares web search capability and internet permission`() {
        val plugin = DuckDuckGoWebSearchPlugin()
        assertTrue(PluginCapability.WebSearch in plugin.capabilities)
        assertTrue(PluginPermission.Internet in plugin.manifest.permissions)
        assertTrue(plugin.id.startsWith("com.authorss81.noteflow.plugins.websearch"))
        // Off by default — user opt-in.
        val registry = PluginRegistry(
            InMemoryEnableStore(),
            plugins = listOf(plugin),
            currentApiLevel = 26
        )
        assertTrue(!registry.isEnabled(plugin.id))
    }
}
