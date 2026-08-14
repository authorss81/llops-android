package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.DictionaryOutcome
import com.authorss81.noteflow.plugins.DictionaryPlugin
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginManager
import com.authorss81.noteflow.plugins.PluginPermission
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.PluginResult
import com.authorss81.noteflow.plugins.dictionary.DictionaryQueryUrl
import com.authorss81.noteflow.plugins.dictionary.DictionaryResponseParser
import com.authorss81.noteflow.plugins.dictionary.DictionaryServiceException
import com.authorss81.noteflow.plugins.dictionary.DictionarySource
import com.authorss81.noteflow.plugins.dictionary.OfflineWordList
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 26 Dictionary plugin tests (pure JVM, no network).
 *
 * Covers the real JSON parsing path (dictionaryapi.dev sample payload), the
 * bundled OFFLINE fallback word list, honest word-not-found, the query URL
 * builder, and plugin routing/error mapping with an injected backend.
 */
class DictionaryPluginTest {

    private val sampleJson = """
        [
          {
            "word": "serendipity",
            "phonetic": "/ˌserənˈdɪpɪti/",
            "phonetics": [{"text": "/ˌserənˈdɪpɪti/", "audio": ""}],
            "meanings": [
              {
                "partOfSpeech": "noun",
                "definitions": [
                  {
                    "definition": "the occurrence and development of events by chance in a happy or beneficial way.",
                    "example": "a fortunate stroke of serendipity"
                  }
                ]
              },
              {
                "partOfSpeech": "noun",
                "definitions": [
                  {"definition": "an aptitude for making desirable discoveries by accident."}
                ]
              }
            ]
          }
        ]
    """.trimIndent()

    // ---- JSON parse -------------------------------------------------------

    @Test
    fun `parser extracts word phonetic and definitions from a sample payload`() {
        val lookup = DictionaryResponseParser.parse(sampleJson, "serendipity")
        assertNotNull(lookup)
        lookup!!
        assertEquals("serendipity", lookup.word)
        assertEquals("/ˌserənˈdɪpɪti/", lookup.phonetic)
        assertEquals(2, lookup.definitions.size)
        assertEquals("noun", lookup.definitions[0].partOfSpeech)
        assertTrue(lookup.definitions[0].definition.contains("beneficial"))
        assertEquals(DictionarySource.ONLINE, lookup.source)
    }

    @Test
    fun `parser handles an empty array as no result`() {
        assertNull(DictionaryResponseParser.parse("[]", "word"))
        assertNull(DictionaryResponseParser.parse("", "word"))
        assertNull(DictionaryResponseParser.parse("   ", "word"))
    }

    @Test
    fun `parser converts malformed json into a typed user-facing error`() {
        try {
            DictionaryResponseParser.parse("this is { not json", "word")
            fail("malformed JSON must fail loudly")
        } catch (e: DictionaryServiceException) {
            assertTrue(e.message.orEmpty().isNotBlank())
        }
    }

    @Test
    fun `parser drops definitions with blank text`() {
        val json = """
            [{"word":"x","meanings":[{"partOfSpeech":null,"definitions":[{"definition":""}]}]}]
        """.trimIndent()
        assertNull(DictionaryResponseParser.parse(json, "x"))
    }

    @Test
    fun `query url builder lowercases and word-encodes`() {
        val url = DictionaryQueryUrl.build("Hello World")
        assertTrue(url.startsWith(DictionaryQueryUrl.DEFAULT_BASE.trimEnd('/')))
        assertTrue(url.contains("/hello+world"))
    }

    // ---- offline fallback ---------------------------------------------------

    @Test
    fun `offline word list serves bundled words with offline source`() {
        val lookup = OfflineWordList.lookup("serendipity")
        assertNull(lookup)
        val defined = OfflineWordList.lookup("Insight")
        assertNotNull(defined)
        defined!!
        assertEquals("insight", defined.word)
        assertEquals(DictionarySource.OFFLINE, defined.source)
        assertTrue(defined.definitions.isNotEmpty())
    }

    @Test
    fun `offline word list lookup is case-insensitive and trims`() {
        val lookup = OfflineWordList.lookup("  ANALYZE ")
        assertNotNull(lookup)
        assertEquals("analyze", lookup!!.word)
    }

    // ---- plugin behaviour (injected backend, no network) -------------------

    @Test
    fun `plugin uses the online result when the backend returns one`() = runBlocking {
        val plugin = com.authorss81.noteflow.plugins.dictionary.DictionaryPluginImpl(
            client = { DictionaryResponseParser.parse(sampleJson, it) }
        )
        val outcome = plugin.lookupWord("serendipity")
        assertTrue(outcome is DictionaryOutcome.Success)
        val success = (outcome as DictionaryOutcome.Success).lookup
        assertEquals("serendipity", success.word)
        assertEquals(DictionarySource.ONLINE, success.source)
    }

    @Test
    fun `plugin falls back to the bundled list when the backend is unreachable`() = runBlocking {
        val plugin = com.authorss81.noteflow.plugins.dictionary.DictionaryPluginImpl(
            client = { throw IOException("no network") }
        )
        val outcome = plugin.lookupWord("goal")
        assertTrue(outcome is DictionaryOutcome.Success)
        assertEquals(DictionarySource.OFFLINE, (outcome as DictionaryOutcome.Success).lookup.source)
    }

    @Test
    fun `plugin falls back to the bundled list when the backend has no result`() = runBlocking {
        val plugin = com.authorss81.noteflow.plugins.dictionary.DictionaryPluginImpl(
            client = { null }
        )
        val outcome = plugin.lookupWord("unique")
        assertTrue(outcome is DictionaryOutcome.Success)
        assertEquals("unique", (outcome as DictionaryOutcome.Success).lookup.word)
    }

    @Test
    fun `plugin honestly reports a word not found online or offline`() = runBlocking {
        val plugin = com.authorss81.noteflow.plugins.dictionary.DictionaryPluginImpl(
            client = { null }
        )
        val outcome = plugin.lookupWord("flibbertigibbet")
        assertTrue(outcome is DictionaryOutcome.NotFound)
        assertTrue((outcome as DictionaryOutcome.NotFound).message.contains("flibbertigibbet"))
    }

    @Test
    fun `blank word returns an error without calling the backend`() = runBlocking {
        val plugin = com.authorss81.noteflow.plugins.dictionary.DictionaryPluginImpl(
            client = { throw AssertionError("backend must not be called") }
        )
        assertTrue(plugin.lookupWord("   \n ") is DictionaryOutcome.Error)
    }

    @Test
    fun `plugin routes through the manager with the dictionary capability`() = runBlocking {
        val plugin = com.authorss81.noteflow.plugins.dictionary.DictionaryPluginImpl(
            client = { com.authorss81.noteflow.plugins.DictionaryLookup("x", null, emptyList(), DictionarySource.OFFLINE) }
        )
        val registry = PluginRegistry(
            InMemoryEnableStore(),
            plugins = listOf(plugin),
            currentApiLevel = 26
        )
        registry.setEnabled(plugin.id, enabled = true)
        val manager = PluginManager(registry)
        val result = manager.withPluginAsync(PluginCapability.Dictionary, null) { p ->
            (p as DictionaryPlugin).lookupWord("x")
        }
        assertTrue(result is PluginResult.Success)
    }

    // ---- manifest ----------------------------------------------------------

    @Test
    fun `manifest declares dictionary capability and internet permission`() {
        val plugin = com.authorss81.noteflow.plugins.dictionary.DictionaryPluginImpl()
        assertTrue(PluginCapability.Dictionary in plugin.capabilities)
        assertTrue(PluginPermission.Internet in plugin.manifest.permissions)
        assertTrue(plugin.id.startsWith("com.authorss81.noteflow.plugins.dictionary"))
    }
}