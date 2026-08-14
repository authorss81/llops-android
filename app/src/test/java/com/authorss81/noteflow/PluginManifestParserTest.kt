package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.runtime.HostedPluginManifest
import com.authorss81.noteflow.plugins.runtime.ManifestParseResult
import com.authorss81.noteflow.plugins.runtime.PluginEntry
import com.authorss81.noteflow.plugins.runtime.PluginManifestParser
import com.authorss81.noteflow.plugins.runtime.PluginVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 24: the hosted version-manifest parser. Strictness rules:
 * a single malformed/tampered offer invalidates the WHOLE manifest (nothing is
 * applied), an empty list is valid ("no updates"), and every update-critical
 * field (HTTPS url, sha256, pinnedCertHash, parseable version, channel) is
 * validated before an offer can be trusted.
 */
class PluginManifestParserTest {

    private val parser = PluginManifestParser()

    private fun validOffer(id: String = "com.authorss81.noteflow.plugins.remote.ocr") = """
        {
          "id": "$id",
          "version": "1.2.0",
          "downloadUrl": "https://plugins.example.com/ocr-1.2.0.apk",
          "sha256": "0f9c2b1a",
          "pinnedCertHash": "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
          "installSizeBytes": 204800,
          "updateChannel": "stable",
          "updateNotes": "Faster on low-end devices"
        }
    """.trimIndent()

    private fun manifestJson(offers: List<String>): String = """
        { "plugins": [ ${offers.joinToString(", ")} ] }
    """.trimIndent()

    @Test
    fun `a well-formed manifest parses into a valid document`() {
        val result = parser.parse(manifestJson(listOf(validOffer())))

        assertTrue(result is ManifestParseResult.Valid)
        val manifest = (result as ManifestParseResult.Valid).manifest
        assertEquals(1, manifest.plugins.size)
        val offer = manifest.plugins.first()
        assertEquals("com.authorss81.noteflow.plugins.remote.ocr", offer.id)
        assertEquals(PluginVersion(1, 2, 0), offer.version)
        assertEquals(204800L, offer.installSizeBytes)
        assertEquals("Faster on low-end devices", offer.updateNotes)
        assertEquals("stable", offer.updateChannel)
    }

    @Test
    fun `missing optional fields default correctly`() {
        val minimal = """
            {
              "id": "x.plugin",
              "version": "2.0.0",
              "downloadUrl": "https://plugins.example.com/x-2.0.0.apk",
              "sha256": "abc",
              "pinnedCertHash": "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="
            }
        """.trimIndent()

        val result = parser.parse(manifestJson(listOf(minimal)))

        assertTrue(result is ManifestParseResult.Valid)
        val offer = (result as ManifestParseResult.Valid).manifest.plugins.first()
        assertEquals(PluginEntry.DEFAULT_CHANNEL, offer.updateChannel)
        assertEquals(null, offer.installSizeBytes)
        assertEquals(null, offer.updateNotes)
    }

    @Test
    fun `an empty plugin list is valid - no updates offered`() {
        val result = parser.parse("""{ "plugins": [] }""")

        assertTrue(result is ManifestParseResult.Valid)
        assertTrue((result as ManifestParseResult.Valid).manifest.plugins.isEmpty())
    }

    @Test
    fun `malformed JSON is refused wholesale`() {
        val result = parser.parse("""{ "plugins": [ { "id":  } ] }""")

        assertTrue(result is ManifestParseResult.Invalid)
    }

    @Test
    fun `a missing plugins list is refused`() {
        val result = parser.parse("""{ "notPlugins": [] }""")

        assertTrue(result is ManifestParseResult.Invalid)
        assertTrue((result as ManifestParseResult.Invalid).errors.any { it.contains("plugins") })
    }

    @Test
    fun `an unparseable version invalidates the whole manifest`() {
        val bad = validOffer().replace("\"1.2.0\"", "\"not-a-version\"")

        val result = parser.parse(manifestJson(listOf(validOffer("good.plugin"), bad)))

        assertTrue(result is ManifestParseResult.Invalid)
        assertTrue((result as ManifestParseResult.Invalid).errors.any { it.contains("version") })
    }

    @Test
    fun `a non-HTTPS downloadUrl invalidates the whole manifest`() {
        val bad = validOffer().replace("https://plugins.example.com", "http://plugins.example.com")

        val result = parser.parse(manifestJson(listOf(bad)))

        assertTrue(result is ManifestParseResult.Invalid)
        assertTrue((result as ManifestParseResult.Invalid).errors.any { it.contains("HTTPS") })
    }

    @Test
    fun `a missing sha256 invalidates the whole manifest`() {
        val bad = validOffer().replace("\"sha256\": \"0f9c2b1a\",", "")

        val result = parser.parse(manifestJson(listOf(bad)))

        assertTrue(result is ManifestParseResult.Invalid)
        assertTrue((result as ManifestParseResult.Invalid).errors.any { it.contains("sha256") })
    }

    @Test
    fun `a missing pinnedCertHash invalidates the whole manifest`() {
        val bad = validOffer().replace("\"pinnedCertHash\": \"sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\",", "")

        val result = parser.parse(manifestJson(listOf(bad)))

        assertTrue(result is ManifestParseResult.Invalid)
        assertTrue((result as ManifestParseResult.Invalid).errors.any { it.contains("pinnedCertHash") })
    }

    @Test
    fun `duplicate ids invalidate the whole manifest`() {
        val result = parser.parse(manifestJson(listOf(validOffer("dup.plugin"), validOffer("dup.plugin"))))

        assertTrue(result is ManifestParseResult.Invalid)
        assertTrue((result as ManifestParseResult.Invalid).errors.any { it.contains("more than once") })
    }

    @Test
    fun `a blank plugin id invalidates the whole manifest`() {
        val bad = validOffer().replace(""""id": "com.authorss81.noteflow.plugins.remote.ocr",""", "")

        val result = parser.parse(manifestJson(listOf(bad)))

        assertTrue(result is ManifestParseResult.Invalid)
        assertTrue((result as ManifestParseResult.Invalid).errors.any { it.contains("id") })
    }

    @Test
    fun `a negative installSizeBytes invalidates the manifest`() {
        val bad = validOffer().replace("204800", "-5")

        val result = parser.parse(manifestJson(listOf(bad)))

        assertTrue(result is ManifestParseResult.Invalid)
    }

    @Test
    fun `offerFor returns the matching offer or null`() {
        val manifest = HostedPluginManifest(
            listOf(parser.parse(manifestJson(listOf(validOffer()))).let { (it as ManifestParseResult.Valid).manifest.plugins.first() })
        )

        assertTrue(manifest.offerFor("com.authorss81.noteflow.plugins.remote.ocr") != null)
        assertTrue(manifest.offerFor("unknown.plugin") == null)
    }
}
