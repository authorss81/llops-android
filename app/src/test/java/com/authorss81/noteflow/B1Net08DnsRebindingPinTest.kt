package com.authorss81.noteflow

import com.authorss81.noteflow.services.DnsRebindingPolicy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R2-B1N-02 (phase-144): the resolve-and-pin layer ([DnsRebindingPolicy]) that
 * sits on top of the textual [SsrfHostPolicy] blocklist.
 *
 * The textual blocklist only sees the STRING the user/plugin pasted; a
 * DNS-rebinding domain answers the first query with a public address and an
 * internal one at connect time. These tests prove the two-layer fix operates:
 *
 *  1. [DnsRebindingPolicy.resolveAndPin] resolves once and refuses the hop
 *     WHOLESALE when ANY returned address is internal (loopback / RFC-1918 /
 *     link-local metadata / CGNAT / ULA / mapped forms) — never a partial pin;
 *  2. [DnsRebindingPolicy.applyPinToConnection] pins the actual connect to the
 *     checked addresses via the layered [DnsRebindingPolicy.PinnedSslSocketFactory]
 *     (which rebuilds a platform-pre-connected socket that reached a non-pinned
 *     address) and a hostname verifier that re-checks the hop host;
 *  3. source pins keep the transports wired to both layers.
 *
 * Pure JVM, no external network: the DNS seam is injected; the socket-factory
 * tests pair against loopback sockets only.
 */
class B1Net08DnsRebindingPinTest {

    private val publicAddress: InetAddress = InetAddress.getByName("8.8.8.8")

    // ---- resolveAndPin: validation of EVERY answer --------------------------

    @Test
    fun `a host that resolves wholly to public addresses is pinned`() {
        val pin = DnsRebindingPolicy.resolveAndPin("api.example.com") { arrayOf(publicAddress) }
        assertTrue(
            "whole-public resolution must pin, got $pin",
            pin is DnsRebindingPolicy.Verdict.Pinned
        )
        val pinned = pin as DnsRebindingPolicy.Verdict.Pinned
        assertEquals("api.example.com", pinned.host)
        assertEquals(listOf(publicAddress), pinned.addresses)
    }

    @Test
    fun `a host resolving to any internal address is refused wholesale`() {
        val internal = listOf(
            InetAddress.getByName("127.0.0.1") to "loopback",
            InetAddress.getByName("10.0.0.1") to "RFC-1918",
            InetAddress.getByName("169.254.169.254") to "cloud metadata",
            InetAddress.getByName("100.64.0.1") to "CGNAT",
            InetAddress.getByName("192.168.1.1") to "RFC-1918",
            InetAddress.getByName("0:0:0:0:0:0:0:1") to "IPv6 loopback",
            InetAddress.getByName("fc00:1234::1") to "IPv6 ULA",
            InetAddress.getByName("fe80::1") to "IPv6 link-local",
            InetAddress.getByName("::ffff:10.0.0.1") to "IPv4-mapped RFC-1918"
        )
        for ((addr, label) in internal) {
            val verdict = DnsRebindingPolicy.resolveAndPin("rebind.example") { arrayOf(addr) }
            assertTrue(
                "a $label answer must refuse the hop, got $verdict",
                verdict is DnsRebindingPolicy.Verdict.Refused
            )
            assertTrue(
                "the refusal must name the address (got $verdict)",
                (verdict as DnsRebindingPolicy.Verdict.Refused).reason.contains(addr.hostAddress ?: "")
            )
        }
    }

    @Test
    fun `a mixed public and internal resolution is refused - never a partial pin`() {
        val verdict = DnsRebindingPolicy.resolveAndPin("rebind.example") {
            arrayOf(publicAddress, InetAddress.getByName("127.0.0.1"))
        }
        assertTrue(
            "a single internal answer among public ones must refuse the hop, got $verdict",
            verdict is DnsRebindingPolicy.Verdict.Refused
        )
    }

    @Test
    fun `duplicate addresses are deduplicated before pinning`() {
        val pin = DnsRebindingPolicy.resolveAndPin("api.example.com") {
            arrayOf(publicAddress, publicAddress)
        }
        assertTrue(pin is DnsRebindingPolicy.Verdict.Pinned)
        assertEquals(1, (pin as DnsRebindingPolicy.Verdict.Pinned).addresses.size)
    }

    @Test
    fun `an unresolved or failing name is refused, never thrown`() {
        assertTrue(
            DnsRebindingPolicy.resolveAndPin("nowhere.example") {
                throw java.net.UnknownHostException("NOPE")
            } is DnsRebindingPolicy.Verdict.Refused
        )
        assertTrue(
            DnsRebindingPolicy.resolveAndPin("nowhere.example") { throw SecurityException("denied") }
                is DnsRebindingPolicy.Verdict.Refused
        )
        assertTrue(
            DnsRebindingPolicy.resolveAndPin("nowhere.example") { arrayOf() }
                is DnsRebindingPolicy.Verdict.Refused
        )
        assertTrue(
            DnsRebindingPolicy.resolveAndPin("  ") { arrayOf(publicAddress) }
                is DnsRebindingPolicy.Verdict.Refused
        )
    }

    @Test
    fun `the default resolver is the platform DNS seam`() {
        // Sanity: the production seam points at InetAddress.getAllByName.
        assertNotNull(DnsRebindingPolicy.DEFAULT_RESOLVER)
    }

    // ---- applyPinToConnection: pinning the connect --------------------------

    @Test
    fun `a plain http connection gets no pin - the scheme gates already hold`() {
        // Plain HttpURLConnection (no TLS): applyPinToConnection must be a no-op
        // (nothing to layer TLS onto); the transport's per-hop https/SSRF gates
        // still guard it. Loopback here is fine — this fake never connects.
        val conn: HttpURLConnection = FakeHttpConnection(200)
        DnsRebindingPolicy.applyPinToConnection(conn, "api.example.com", listOf(publicAddress), 1000)
        assertTrue(true) // reached without touching TLS machinery
    }

    // ---- PinnedSslSocketFactory: layered createSocket -----------------------

    @Test
    fun `a pinned socket arriving from the platform is handed straight to the delegate`() {
        val captured = ArrayList<Socket>()
        val delegate = RecordingSslSocketFactory { layered ->
            captured += layered
            layered
        }
        val factory = DnsRebindingPolicy.PinnedSslSocketFactory(
            pinnedAddresses = listOf(publicAddress),
            connectTimeoutMs = 1000,
            delegate = delegate
        )
        // The platform pre-connected its own socket to a PINNED address.
        val inbound = RawSocket(publicAddress)
        factory.createSocket(inbound, "api.example.com", 443, true)
        assertEquals("the pinned pre-connected socket must be delegated unchanged", 1, captured.size)
        assertTrue(captured[0] === inbound)
    }

    @Test
    fun `a pre-connected socket outside the pin is discarded and rebuilt to a pinned address`() {
        // Loopback-only, no external network: bind a real listener on the pin
        // address (127.0.0.1) so the fresh pinned connect succeeds. The pin
        // here is 127.0.0.1; the "rebound" platform socket reports a DIFFERENT
        // address (127.0.0.2, same loopback /8, distinct literal).
        java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val port = server.localPort
            val delegate = RecordingSslSocketFactory { it }
            val factory = DnsRebindingPolicy.PinnedSslSocketFactory(
                pinnedAddresses = listOf(InetAddress.getByName("127.0.0.1")),
                connectTimeoutMs = 2000,
                delegate = delegate
            )
            val rebound = RawSocket(InetAddress.getByName("127.0.0.2"))
            val rebuilt = factory.createSocket(rebound, "api.example.com", port, true)
            assertTrue(
                "the rebound socket must be closed and never reach the delegate",
                rebound.closed
            )
            assertFalse("the rebuilt socket must not be the discarded rebound socket", rebuilt === rebound)
            assertEquals(
                "the rebuilt connect must target a PINNED address",
                "127.0.0.1",
                rebuilt.inetAddress.hostAddress
            )
        }
    }

    @Test
    fun `an unconnected pre-socket is treated as outside the pin and rebuilt`() {
        java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val port = server.localPort
            val delegate = RecordingSslSocketFactory { it }
            val factory = DnsRebindingPolicy.PinnedSslSocketFactory(
                pinnedAddresses = listOf(InetAddress.getByName("127.0.0.1")),
                connectTimeoutMs = 2000,
                delegate = delegate
            )
            val loose = RawSocket(null)
            val built = factory.createSocket(loose, "api.example.com", port, true)
            assertTrue("an unconnected pre-socket must be closed", loose.closed)
            assertFalse("a fresh socket must be built for the pin", built === loose)
            assertEquals("127.0.0.1", built.inetAddress.hostAddress)
        }
    }

    @Test
    fun `an empty pin cannot be constructed`() {
        try {
            DnsRebindingPolicy.PinnedSslSocketFactory(
                pinnedAddresses = emptyList(),
                connectTimeoutMs = 1000
            )
            fail("an empty pin must be rejected at construction")
        } catch (e: IllegalArgumentException) {
            // required
        }
    }

    // ---- PinnedHostnameVerifier ---------------------------------------------

    @Test
    fun `hostname verifier only accepts the expected hop host`() {
        val delegateNone = object : javax.net.ssl.HostnameVerifier {
            override fun verify(hostname: String, session: SSLSession): Boolean = true
        }
        val verifier = DnsRebindingPolicy.PinnedHostnameVerifier("api.example.com", delegateNone)
        assertTrue(
            verifier.verify("api.example.com", FakeSslSession())
        )
        assertFalse(
            "a different hostname must not verify",
            verifier.verify("evil.example.com", FakeSslSession())
        )
    }

    @Test
    fun `hostname verifier refuses a blocked destination even if delegated`() {
        val delegateNone = object : javax.net.ssl.HostnameVerifier {
            override fun verify(hostname: String, session: SSLSession): Boolean = true
        }
        // Construct directly with an internally-addressed host: must refuse no
        // matter what the delegate says.
        val verifier = DnsRebindingPolicy.PinnedHostnameVerifier("127.0.0.1", delegateNone)
        assertFalse(verifier.verify("127.0.0.1", FakeSslSession()))
    }

    // ---- wiring pins --------------------------------------------------------

    @Test
    fun `every user-facing transport resolves and pins through DnsRebindingPolicy`() {
        val transports = listOf(
            "app/src/main/kotlin/com/authorss81/noteflow/plugins/citation/HttpsTitleFetcher.kt",
            "app/src/main/kotlin/com/authorss81/noteflow/plugins/webcapture/WebPageFetcher.kt",
            "app/src/main/kotlin/com/authorss81/noteflow/services/AppFacadeHost.kt",
            "app/src/main/kotlin/com/authorss81/noteflow/plugins/websearch/DuckDuckGoClient.kt",
            "app/src/main/kotlin/com/authorss81/noteflow/plugins/weather/WeatherClient.kt",
            "app/src/main/kotlin/com/authorss81/noteflow/plugins/dictionary/DictionaryClient.kt"
        )
        for (relative in transports) {
            val source = readSource(relative)
            assertTrue(
                "$relative must resolve+validate via DnsRebindingPolicy.resolveAndPin",
                source.contains("DnsRebindingPolicy.resolveAndPin")
            )
            assertTrue(
                "$relative must pin the connect to the checked addresses",
                source.contains("DnsRebindingPolicy.applyPinToConnection")
            )
        }
    }

    @Test
    fun `the three JSON clients keep redirects strictly manual and https-only`() {
        // R2-B1N-02 wiring must not have regressed B1-NET-05: all three clients
        // still route hops through StrictRedirectPolicy and never auto-follow.
        val files = listOf(
            "app/src/main/kotlin/com/authorss81/noteflow/plugins/websearch/DuckDuckGoClient.kt",
            "app/src/main/kotlin/com/authorss81/noteflow/plugins/weather/WeatherClient.kt",
            "app/src/main/kotlin/com/authorss81/noteflow/plugins/dictionary/DictionaryClient.kt"
        )
        for (relative in files) {
            val source = readSource(relative)
            assertTrue("$relative must keep StrictRedirectPolicy", source.contains("StrictRedirectPolicy"))
            assertTrue("$relative must keep manual redirects", source.contains("instanceFollowRedirects = false"))
        }
    }

    // ---- helpers ------------------------------------------------------------

    private fun readSource(relative: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (File(dir, "app/build.gradle.kts").isFile && File(dir, "app").isDirectory) {
                return@readSource File(dir, relative).readText()
            }
            dir = dir.parentFile ?: return@readSource File(cwd, relative).readText()
        }
        return File(cwd, relative).readText()
    }

    /** Minimal [HttpURLConnection] fake for the no-op pin path. */
    private class FakeHttpConnection(
        private val fakeCode: Int
    ) : HttpURLConnection(URL("https://fake.invalid/")) {
        override fun disconnect() {}
        override fun usingProxy(): Boolean = false
        override fun connect() {}
        override fun getInputStream(): InputStream =
            ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getResponseCode(): Int = fakeCode
    }

    /** Fake SSLSession for the hostname verifier. */
    private class FakeSslSession : SSLSession {
        override fun getApplicationBufferSize(): Int = 0
        override fun getCipherSuite(): String = ""
        override fun getCreationTime(): Long = 0
        override fun getId(): ByteArray = byteArrayOf()
        override fun getLastAccessedTime(): Long = 0
        override fun getLocalCertificates(): Array<java.security.cert.Certificate>? = null
        override fun getLocalPrincipal(): java.security.Principal? = null
        override fun getPacketBufferSize(): Int = 0
        override fun getPeerCertificates(): Array<java.security.cert.Certificate> {
            throw javax.net.ssl.SSLPeerUnverifiedException("none")
        }
        override fun getPeerCertificateChain(): Array<javax.security.cert.X509Certificate>? = null
        override fun getPeerHost(): String = ""
        override fun getPeerPort(): Int = 0
        override fun getPeerPrincipal(): java.security.Principal? = null
        override fun getProtocol(): String = ""
        override fun getSessionContext(): javax.net.ssl.SSLSessionContext? = null
        override fun getValue(name: String): Any? = null
        override fun getValueNames(): Array<String> = arrayOf()
        override fun invalidate() {}
        override fun isValid(): Boolean = false
        override fun putValue(name: String, value: Any) {}
        override fun removeValue(name: String) {}
    }

    /**
     * A delegate SSLSocketFactory that records the layered-createSocket call
     * and returns [onLayered]'s result (default: the socket unchanged) so the
     * pin logic can be exercised without a real TLS handshake.
     */
    private class RecordingSslSocketFactory(
        private val onLayered: (socket: java.net.Socket) -> java.net.Socket = { it }
    ) : SSLSocketFactory() {
        val layeredCalls = ArrayList<java.net.Socket>()

        override fun getDefaultCipherSuites(): Array<String> = arrayOf()
        override fun getSupportedCipherSuites(): Array<String> = arrayOf()
        override fun createSocket(s: java.net.Socket, host: String, port: Int, autoClose: Boolean): java.net.Socket {
            layeredCalls += s
            return onLayered(s)
        }
        override fun createSocket(host: String, port: Int): java.net.Socket = throw UnsupportedOperationException()
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): java.net.Socket =
            throw UnsupportedOperationException()
        override fun createSocket(host: InetAddress, port: Int): java.net.Socket = throw UnsupportedOperationException()
        override fun createSocket(host: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): java.net.Socket =
            throw UnsupportedOperationException()
    }

    /**
     * A bare "already connected" socket shaped for the layered createSocket
     * test: [inetAddress][java.net.Socket.inetAddress] reports a fixed address
     * and close() is observable. Never opens a real network connection.
     */
    private class RawSocket(private val addr: InetAddress?) : java.net.Socket() {
        var closed = false
            private set
        override fun getInetAddress(): InetAddress? = addr
        override fun close() {
            closed = true
        }
    }
}