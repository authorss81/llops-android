package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.dictionary.DictionaryClient
import com.authorss81.noteflow.plugins.dictionary.DictionaryPluginImpl
import com.authorss81.noteflow.plugins.dictionary.DictionaryServiceException
import com.authorss81.noteflow.plugins.dictionary.DictionarySource
import com.authorss81.noteflow.plugins.weather.OpenMeteoClient
import com.authorss81.noteflow.plugins.weather.OpenMeteoForecastParser
import com.authorss81.noteflow.plugins.weather.OpenMeteoGeocoderParser
import com.authorss81.noteflow.plugins.weather.WeatherServiceException
import com.authorss81.noteflow.plugins.websearch.DuckDuckGoClient
import com.authorss81.noteflow.plugins.websearch.DuckDuckGoSearchException
import com.authorss81.noteflow.plugins.runtime.FacadeResult
import com.authorss81.noteflow.services.AppFacadeHost
import com.authorss81.noteflow.services.StrictRedirectPolicy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * B1-NET-05 (phase-52): HTTPS→HTTP redirect downgrades are refused at EVERY
 * `HttpURLConnection` transport in the base app.
 *
 * Before this phase these transports left `instanceFollowRedirects` at its
 * `true` default, so an `https://` server answering `302/307 Location: http://…`
 * silently downgraded the request to cleartext — the scheme guard ran once on
 * the initial URL, never on the redirected connection. Now every transport
 * sets `instanceFollowRedirects = false` and follows redirects ONLY through
 * [StrictRedirectPolicy], which re-runs the `https`-scheme requirement and the
 * B1-NET-04 SSRF blocklist (via [SsrfHostPolicy]) on EVERY hop.
 *
 * Pure JVM — no network. The transports' connection factory is injected with a
 * fake [HttpURLConnection] that answers scripted 3xx/Location/200 responses.
 * Proven:
 *  1. [StrictRedirectPolicy] hop policy (downgrade / non-http scheme / blocked
 *     host / loop / malformed / blank).
 *  2. Each transport refuses an https→http redirect and never opens the next
 *     connection; same-scheme https redirects still proceed.
 *  3. Entry URLs that are not https are refused before any connection.
 *  4. The Dictionary plugin degrades gracefully offline when the refusal fires.
 *  5. Source pins hold `instanceFollowRedirects = false` at every base-app
 *     transport and forbid the `= true` assignment anywhere under `app/src/main`.
 */
class B1Net05RedirectDowngradeTest {

    // ---- StrictRedirectPolicy: hop policy ------------------------------------

    @Test
    fun `policy follows a same-scheme https redirect`() {
        val cur = URI("https://api.example.com/a")
        val next = StrictRedirectPolicy.resolveNextTlsHop(
            cur,
            "https://cdn.example.com/b?from=redirect"
        )
        assertEquals("https://cdn.example.com/b?from=redirect", next.toString())
        // relative Location resolves against the current hop
        assertEquals(
            "https://api.example.com/b",
            StrictRedirectPolicy.resolveNextTlsHop(cur, "/b").toString()
        )
    }

    @Test
    fun `policy refuses an https to http downgrade hop`() {
        val cur = URI("https://api.example.com/a")
        for (location in listOf(
            "http://api.example.com/b",
            "http://attacker.example/leak"
        )) {
            try {
                StrictRedirectPolicy.resolveNextTlsHop(cur, location)
                fail("downgrade hop must be refused: $location")
            } catch (e: StrictRedirectPolicy.RedirectRefusedException) {
                assertTrue("HTTPS" in e.message!!)
            }
        }
    }

    @Test
    fun `policy keeps a protocol-relative hop on the current https scheme`() {
        // RFC 3986 network-path reference: `//host/path` resolves with the base
        // scheme (https) — never a downgrade, so it MUST be followed (allowing it
        // also proves the hop check reads the resolved scheme, not the Location
        // text).
        val cur = URI("https://api.example.com/a")
        assertEquals(
            "https://cdn.example.com/b",
            StrictRedirectPolicy.resolveNextTlsHop(cur, "//cdn.example.com/b").toString()
        )
    }

    @Test
    fun `policy refuses a non-http scheme redirect`() {
        val cur = URI("https://api.example.com/a")
        for (location in listOf("ftp://example.com/x", "file:///etc/passwd", "javascript:alert(1)")) {
            try {
                StrictRedirectPolicy.resolveNextTlsHop(cur, location)
                fail("non-https scheme must be refused: $location")
            } catch (e: StrictRedirectPolicy.RedirectRefusedException) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
    }

    @Test
    fun `policy refuses a redirect to a blocked internal host`() {
        val cur = URI("https://api.example.com/a")
        for (location in listOf(
            "https://127.0.0.1/x",
            "https://localhost/x",
            "https://169.254.169.254/latest/meta-data/",
            "https://192.168.1.1/status",
            "https://[::1]/x",
            "https://10.0.0.1/x",
            "https://100.64.0.1/x"
        )) {
            try {
                StrictRedirectPolicy.resolveNextTlsHop(cur, location)
                fail("blocked host must be refused: $location")
            } catch (e: StrictRedirectPolicy.RedirectRefusedException) {
                assertTrue("refusal for $location must explain why", e.message!!.contains("blocked"))
            }
        }
    }

    @Test
    fun `policy refuses a redirect loop`() {
        val cur = URI("https://api.example.com/?q=x")
        try {
            StrictRedirectPolicy.resolveNextTlsHop(cur, "https://api.example.com/?q=x")
            fail("redirect loop must be refused")
        } catch (e: StrictRedirectPolicy.RedirectRefusedException) {
            assertTrue(e.message!!.contains("loop"))
        }
    }

    @Test
    fun `policy returns null for a blank location`() {
        val cur = URI("https://api.example.com/a")
        assertNull(StrictRedirectPolicy.resolveNextTlsHop(cur, null))
        assertNull(StrictRedirectPolicy.resolveNextTlsHop(cur, ""))
        assertNull(StrictRedirectPolicy.resolveNextTlsHop(cur, "   "))
    }

    @Test
    fun `policy refuses a malformed redirect target`() {
        val cur = URI("https://api.example.com/a")
        try {
            StrictRedirectPolicy.resolveNextTlsHop(cur, "https://[broken")
            fail("malformed target must be refused")
        } catch (e: StrictRedirectPolicy.RedirectRefusedException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun `checkTlsHop enforces https entry and passes the ssrf blocklist`() {
        // refused: non-https scheme
        for (u in listOf(
            URI("http://api.example.com/"),
            URI("ftp://api.example.com/"),
            URI("file:///etc/passwd")
        )) {
            try {
                StrictRedirectPolicy.checkTlsHop(u)
                fail("non-https entry must be refused: $u")
            } catch (e: StrictRedirectPolicy.RedirectRefusedException) {
                assertTrue("HTTPS" in e.message!!)
            }
        }
        // refused: internal hosts (even over https)
        for (u in listOf(
            URI("https://127.0.0.1/"),
            URI("https://localhost/"),
            URI("https://169.254.169.254/"),
            URI("https://192.168.1.1/"),
            URI("https://[::1]/"),
            URI("https://2130706433/")
        )) {
            try {
                StrictRedirectPolicy.checkTlsHop(u)
                fail("blocked host must be refused: $u")
            } catch (e: StrictRedirectPolicy.RedirectRefusedException) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
        // allowed: public https hosts (incl. public IPs)
        for (u in listOf(
            URI("https://api.duckduckgo.com/"),
            URI("https://api.open-meteo.com/v1/forecast?x=1"),
            URI("https://8.8.8.8/")
        )) {
            StrictRedirectPolicy.checkTlsHop(u) // must not throw
        }
    }

    // ---- DuckDuckGo client ---------------------------------------------------

    private val ddgJson = """
        {"AbstractText":"Kotlin is a language.",
          "AbstractURL":"https://en.wikipedia.org/wiki/Kotlin_(programming_language)"}
    """.trimIndent()

    @Test
    fun `duckduckgo refuses a 302 downgrade to http and never opens a second connection`() {
        var calls = 0
        val factory: (String) -> HttpURLConnection = { url ->
            calls++
            FakeConnection(302, "http://attacker.invalid/leak")
        }
        try {
            DuckDuckGoClient(connectionFactory = factory).search("Kotlin")
            fail("https->http 302 must be refused")
        } catch (e: DuckDuckGoSearchException) {
            assertTrue("refusal must mention HTTPS", "HTTPS" in e.message!!)
            assertEquals(1, calls)
        }
    }

    @Test
    fun `duckduckgo refuses a 307 downgrade to a blocked host`() {
        var calls = 0
        val factory: (String) -> HttpURLConnection = { url ->
            calls++
            FakeConnection(307, "https://169.254.169.254/latest/meta-data/iam/security-credentials/")
        }
        try {
            DuckDuckGoClient(connectionFactory = factory).search("Kotlin")
            fail("blocked-host 307 must be refused")
        } catch (e: DuckDuckGoSearchException) {
            assertTrue(e.message!!.contains("blocked"))
            assertEquals(1, calls)
        }
    }

    @Test
    fun `duckduckgo follows a same-scheme https redirect`() {
        var calls = 0
        val factory: (String) -> HttpURLConnection = { url ->
            calls++
            if (calls == 1) FakeConnection(302, "https://api.duckduckgo.com/?q=Kotlin&hop=2")
            else FakeConnection(200, body = ddgJson)
        }
        val results = DuckDuckGoClient(connectionFactory = factory).search("Kotlin")
        assertEquals(2, calls)
        assertEquals(1, results.size)
        assertTrue(results[0].url.startsWith("https://"))
    }

    @Test
    fun `duckduckgo refuses a redirect loop`() {
        var calls = 0
        val factory: (String) -> HttpURLConnection = { url ->
            calls++
            FakeConnection(302, url) // Location == current URL → loop
        }
        try {
            DuckDuckGoClient(connectionFactory = factory).search("Kotlin")
            fail("redirect loop must be refused")
        } catch (e: DuckDuckGoSearchException) {
            assertTrue(e.message!!.contains("loop"))
            assertEquals(1, calls)
        }
    }

    @Test
    fun `duckduckgo refuses a non-https entry url before opening a connection`() {
        val client = DuckDuckGoClient(
            urlBuilder = { "http://api.duckduckgo.com/?q=$it" },
            connectionFactory = { throw AssertionError("must not connect to a non-https entry") }
        )
        try {
            client.search("Kotlin")
            fail("http entry must be refused")
        } catch (e: DuckDuckGoSearchException) {
            assertTrue("HTTPS" in e.message!!)
        }
    }

    @Test
    fun `duckduckgo caps the manual redirect chain`() {
        var calls = 0
        val factory: (String) -> HttpURLConnection = { url ->
            calls++
            FakeConnection(302, "https://api.duckduckgo.com/r${calls}")
        }
        try {
            DuckDuckGoClient(connectionFactory = factory).search("Kotlin")
            fail("runaway redirects must be refused")
        } catch (e: DuckDuckGoSearchException) {
            assertTrue(e.message!!.contains("redirected too many times"))
            assertTrue("must not exceed the hop cap", calls <= StrictRedirectPolicy.MAX_REDIRECTS + 1)
        }
    }

    // ---- Open-Meteo client ---------------------------------------------------

    private val forecastJson = """
        {
          "latitude": 51.5,
          "longitude": -0.12,
          "daily": {
            "time": ["2026-08-14"],
            "weather_code": [2],
            "temperature_2m_max": [22.3],
            "temperature_2m_min": [13.1],
            "wind_speed_10m_max": [17.2]
          }
        }
    """.trimIndent()

    @Test
    fun `weather refuses a 301 downgrade to http`() {
        var calls = 0
        val factory: (String) -> HttpURLConnection = { url ->
            calls++
            FakeConnection(301, "http://evil.example/forecast")
        }
        val client = OpenMeteoClient(connectionFactory = factory)
        val place = OpenMeteoGeocoderParser.Place("London", 51.50853, -0.12574)
        try {
            client.forecast(place)
            fail("https->http 301 must be refused")
        } catch (e: WeatherServiceException) {
            assertTrue("HTTPS" in e.message!!)
            assertEquals(1, calls)
        }
    }

    @Test
    fun `weather follows a same-scheme https redirect`() {
        var calls = 0
        val factory: (String) -> HttpURLConnection = { url ->
            calls++
            if (calls == 1) FakeConnection(302, "https://api.open-meteo.com/v1/forecast/hop")
            else FakeConnection(200, body = forecastJson)
        }
        val client = OpenMeteoClient(connectionFactory = factory)
        val snapshot = client.forecast(OpenMeteoGeocoderParser.Place("London", 51.50853, -0.12574))
        assertEquals(2, calls)
        assertEquals(22.3, snapshot.tempMaxC, 0.0001)
    }

    // ---- Dictionary client ---------------------------------------------------

    private val dictJson = """
        [{"word":"serendipity","phonetic":"/s/",
          "meanings":[{"partOfSpeech":"noun",
            "definitions":[{"definition":"the occurrence of events by chance."}]}]}]
    """.trimIndent()

    @Test
    fun `dictionary refuses a 302 downgrade to http`() {
        var calls = 0
        val factory: (String) -> HttpURLConnection = { url ->
            calls++
            FakeConnection(302, "http://plaintext.dictionary.example/word")
        }
        try {
            DictionaryClient(connectionFactory = factory).lookup("serendipity")
            fail("https->http 302 must be refused")
        } catch (e: DictionaryServiceException) {
            assertTrue("HTTPS" in e.message!!)
            assertEquals(1, calls)
        }
    }

    @Test
    fun `dictionary follows a same-scheme https redirect`() {
        var calls = 0
        val factory: (String) -> HttpURLConnection = { url ->
            calls++
            if (calls == 1) FakeConnection(302, "https://api.dictionaryapi.dev/api/v2/entries/en/serendipity/hop")
            else FakeConnection(200, body = dictJson)
        }
        val lookup = DictionaryClient(connectionFactory = factory).lookup("serendipity")
        assertEquals(2, calls)
        assertEquals("serendipity", lookup!!.word)
    }

    @Test
    fun `dictionary degrades offline when a downgrade redirect is refused`() = runBlocking {
        val plugin = DictionaryPluginImpl(
            client = { word ->
                // The online backend answers https with a 307 to http: refused.
                DictionaryClient(connectionFactory = { FakeConnection(307, "http://evil.example/x") })
                    .lookup(word)
            }
        )
        val outcome = plugin.lookupWord("insight")
        assertTrue("offline fallback must still serve", outcome is com.authorss81.noteflow.plugins.DictionaryOutcome.Success)
        val lookup = (outcome as com.authorss81.noteflow.plugins.DictionaryOutcome.Success).lookup
        assertEquals(DictionarySource.OFFLINE, lookup.source)
    }

    // ---- App facade httpsGet (plugin facade) --------------------------------

    @Test
    fun `facade httpGet refuses a 302 downgrade to http`() {
        var calls = 0
        val host = AppFacadeHost(connectionFactory = { url ->
            calls++
            FakeConnection(302, "http://evil.example/data")
        })
        val result = host.httpGet("https://plugin.example/data.json")
        assertTrue(result is FacadeResult.Failed)
        assertTrue((result as FacadeResult.Failed).message.contains("HTTPS"))
        assertEquals(1, calls)
    }

    @Test
    fun `facade httpGet refuses a redirect to a blocked host`() {
        var calls = 0
        val host = AppFacadeHost(connectionFactory = { url ->
            calls++
            FakeConnection(302, "https://169.254.169.254/latest/meta-data/")
        })
        val result = host.httpGet("https://plugin.example/data.json")
        assertTrue(result is FacadeResult.Failed)
        assertTrue("blocked" in (result as FacadeResult.Failed).message)
        assertEquals(1, calls)
    }

    @Test
    fun `facade httpGet follows a same-scheme https redirect`() {
        var calls = 0
        val host = AppFacadeHost(connectionFactory = { url ->
            calls++
            if (calls == 1) FakeConnection(302, "https://plugin.example/real-data.json")
            else FakeConnection(200, body = "payload")
        })
        val result = host.httpGet("https://plugin.example/start")
        assertTrue(result is FacadeResult.Granted)
        assertEquals("payload", (result as FacadeResult.Granted).value)
        assertEquals(2, calls)
    }

    @Test
    fun `facade httpGet refuses a non-https entry url before connecting`() {
        val host = AppFacadeHost(connectionFactory = { throw AssertionError("must not connect") })
        val result = host.httpGet("http://plugin.example/data.json")
        assertTrue(result is FacadeResult.Failed)
        assertTrue("HTTPS" in (result as FacadeResult.Failed).message)
    }

    @Test
    fun `facade httpGet caps the manual redirect chain`() {
        var calls = 0
        val host = AppFacadeHost(connectionFactory = { url ->
            calls++
            FakeConnection(302, "https://plugin.example/hop${calls}")
        })
        val result = host.httpGet("https://plugin.example/start")
        assertTrue(result is FacadeResult.Failed)
        assertTrue("too many redirects" in (result as FacadeResult.Failed).message)
        assertTrue("must not exceed the hop cap", calls <= StrictRedirectPolicy.MAX_REDIRECTS + 1)
    }

    // ---- source pins ---------------------------------------------------------

    @Test
    fun `every base-app transport sets instanceFollowRedirects to false`() {
        val files = listOf(
            "app/src/main/kotlin/com/authorss81/noteflow/plugins/websearch/DuckDuckGoClient.kt",
            "app/src/main/kotlin/com/authorss81/noteflow/plugins/weather/WeatherClient.kt",
            "app/src/main/kotlin/com/authorss81/noteflow/plugins/dictionary/DictionaryClient.kt",
            "app/src/main/kotlin/com/authorss81/noteflow/services/AppFacadeHost.kt",
            "app/src/main/kotlin/com/authorss81/noteflow/services/localsend/LocalSendSender.kt",
            "app/src/main/kotlin/com/authorss81/noteflow/services/WebDavSyncService.kt",
            "app/src/main/kotlin/com/authorss81/noteflow/plugins/citation/HttpsTitleFetcher.kt",
            "app/src/main/kotlin/com/authorss81/noteflow/plugins/webcapture/WebPageFetcher.kt",
            "app/src/main/kotlin/com/authorss81/noteflow/plugins/runtime/PinnedTlsConnector.kt"
        )
        for (relative in files) {
            val file = File(repoRoot(), relative)
            assertTrue("$relative must exist", file.isFile)
            val source = file.readText()
            assertTrue(
                "$relative must disable auto-redirect following",
                source.contains("instanceFollowRedirects = false")
            )
        }
    }

    @Test
    fun `http clients use the shared strict redirect policy`() {
        val files = listOf(
            "app/src/main/kotlin/com/authorss81/noteflow/plugins/websearch/DuckDuckGoClient.kt",
            "app/src/main/kotlin/com/authorss81/noteflow/plugins/weather/WeatherClient.kt",
            "app/src/main/kotlin/com/authorss81/noteflow/plugins/dictionary/DictionaryClient.kt",
            "app/src/main/kotlin/com/authorss81/noteflow/services/AppFacadeHost.kt"
        )
        for (relative in files) {
            val source = File(repoRoot(), relative).readText()
            assertTrue(
                "$relative must route hops through StrictRedirectPolicy",
                source.contains("StrictRedirectPolicy")
            )
        }
    }

    @Test
    fun `no assignment enables auto redirect following under app src main`() {
        val assignment = Regex("""\binstanceFollowRedirects\s*=\s*true\b""")
        val mainDir = File(repoRoot(), "app/src/main")
        assertTrue("app/src/main must exist", mainDir.isDirectory)
        val offenders = mainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexed { i, line -> Triple(file, i + 1, line) }
            }
            .filter { (_, _, line) ->
                val trimmed = line.trim()
                assignment.containsMatchIn(line) &&
                    !trimmed.startsWith("//") &&
                    !trimmed.startsWith("*")
            }
            .map { (file, lineNo, line) -> "${file.name}:$lineNo: $line" }
        assertTrue(
            "instanceFollowRedirects = true must not appear in app/src/main (found: $offenders)",
            offenders.none()
        )
    }

    // ---- fake connection -----------------------------------------------------

    /** Minimal [HttpURLConnection] fake: scripted code/Location/body. */
    private class FakeConnection(
        private val fakeCode: Int,
        private val fakeLocation: String? = null,
        private val body: String = ""
    ) : HttpURLConnection(URL("https://fake.invalid/")) {

        override fun disconnect() {}
        override fun usingProxy(): Boolean = false
        override fun connect() {}
        override fun getInputStream(): InputStream =
            ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getResponseCode(): Int = fakeCode

        override fun getHeaderField(name: String?): String? =
            if (name != null && name.equals("Location", ignoreCase = true)) fakeLocation else null
    }

    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (File(dir, "gradle/libs.versions.toml").isFile && File(dir, "app").isDirectory) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}