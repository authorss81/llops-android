package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.runtime.DEFAULT_MANIFEST_HOST
import com.authorss81.noteflow.plugins.runtime.DEFAULT_PLUGIN_MANIFEST_URL
import com.authorss81.noteflow.plugins.runtime.HttpsManifestTransport
import com.authorss81.noteflow.plugins.runtime.ManifestFetchResult
import com.authorss81.noteflow.plugins.runtime.PinnedCertHash
import com.authorss81.noteflow.plugins.runtime.PLUGIN_MANIFEST_CERT_PIN
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * B1-CRYPTO-01 (Phase 39): the update-manifest transport must be AUTHENTICATED
 * by a COMPILE-TIME certificate pin and must NEVER follow redirects.
 *
 * These tests run the REAL production transport ([HttpsManifestTransport])
 * against a minimal local TLS server whose self-signed leaf is trusted ONLY
 * through the transport's unit-test trust-anchor override — proving the pin
 * gate and the redirect policy end-to-end with no external network and no
 * third-party HTTP server dependency.
 */
class HttpsManifestTransportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val servers = mutableListOf<TinyHttpsServer>()

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.stop() } }
        servers.clear()
    }

    private val validManifestJson = """
        {
          "plugins": [
            {
              "id": "com.authorss81.noteflow.plugins.remote.ocr",
              "version": "1.2.0",
              "downloadUrl": "https://plugins.example.com/ocr-1.2.0.apk",
              "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
              "pinnedCertHash": "sha256/AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
              "updateChannel": "stable"
            }
          ]
        }
    """.trimIndent()

    /** A fresh self-signed keypair + [SSLContext]; the public cert is returned. */
    private fun newSslContext(index: Int): Pair<SSLContext, X509Certificate> {
        val ksFile = File(tmp.root, "server-$index.p12")
        val password = "noteflow-test-pass"
        val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
        val cmd = listOf(
            keytool,
            "-genkeypair", "-alias", "server",
            "-keyalg", "RSA", "-keysize", "2048",
            "-sigalg", "SHA256withRSA",
            "-validity", "3650",
            "-dname", "CN=localhost",
            "-ext", "san=dns:localhost,ip:127.0.0.1",
            "-keystore", ksFile.absolutePath,
            "-storetype", "PKCS12",
            "-storepass", password,
            "-keypass", password,
            "-noprompt"
        )
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "keytool failed: $output" }

        val ks = KeyStore.getInstance("PKCS12")
        FileInputStream(ksFile).use { ks.load(it, password.toCharArray()) }
        val entry = ks.getEntry("server", KeyStore.PasswordProtection(password.toCharArray()))
            as KeyStore.PrivateKeyEntry
        val cert = entry.certificateChain.first() as X509Certificate

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, password.toCharArray())
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, null, null)
        return sslContext to cert
    }

    /** An X509TrustManager that trusts EXACTLY [cert] (the pin gate still runs
     *  on top; this only replaces the system trust store for the test). */
    private fun trustManagerFor(cert: X509Certificate): X509TrustManager {
        val trust = KeyStore.getInstance(KeyStore.getDefaultType())
        trust.load(null, null)
        trust.setCertificateEntry("test-server", cert)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trust)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun pinOf(cert: X509Certificate): String = "sha256/" + PinnedCertHash.base64Sha256(cert)

    private fun transportFor(
        cert: X509Certificate,
        pin: String = pinOf(cert),
        host: String = "127.0.0.1"
    ) = HttpsManifestTransport(
        expectedCertPin = pin,
        expectedHost = host,
        trustManagerOverride = trustManagerFor(cert)
    )

    /** Start a local TLS server and remember it (stopped in [tearDown]). */
    private fun startServer(
        index: Int,
        status: Int,
        headers: Map<String, String> = emptyMap(),
        body: String
    ): Pair<TinyHttpsServer, X509Certificate> {
        val (sslContext, cert) = newSslContext(index)
        val server = TinyHttpsServer(sslContext, status, headers, body.toByteArray(Charsets.UTF_8))
        servers.add(server)
        return server to cert
    }

    @Test
    fun `accepts an HTTPS manifest whose leaf matches the expected pin`() = runBlocking {
        val (server, cert) = startServer(0, 200, body = validManifestJson)

        val result = transportFor(cert).fetch("https://127.0.0.1:${server.port}/manifest.json")

        assertTrue("fetch -> ${(result as? ManifestFetchResult.Failed)?.message}", result is ManifestFetchResult.Loaded)
        val loaded = result as ManifestFetchResult.Loaded
        assertEquals(1, loaded.manifest.plugins.size)
        assertEquals("com.authorss81.noteflow.plugins.remote.ocr", loaded.manifest.plugins.first().id)
    }

    @Test
    fun `rejects an HTTPS manifest whose leaf does not match the expected pin`() = runBlocking {
        val (server, cert) = startServer(0, 200, body = validManifestJson)
        // A DIFFERENT, well-formed pin — the same server, the wrong expectation.
        val otherPin = "sha256/" + java.util.Base64.getEncoder()
            .encodeToString(ByteArray(32) { 0x11 })

        val result = transportFor(cert, pin = otherPin).fetch("https://127.0.0.1:${server.port}/manifest.json")

        assertTrue(result is ManifestFetchResult.Failed)
        val message = (result as ManifestFetchResult.Failed).message
        assertTrue("message=$message", message.contains("pinned hash"))
    }

    @Test
    fun `rejects a plain http url before any connection opens`() = runBlocking {
        val transport = HttpsManifestTransport(
            expectedCertPin = "sha256/AAAA",
            expectedHost = "127.0.0.1"
        )

        val result = transport.fetch("http://127.0.0.1/manifest.json")

        assertTrue(result is ManifestFetchResult.Failed)
        assertTrue((result as ManifestFetchResult.Failed).message.contains("TLS"))
    }

    @Test
    fun `rejects a fetch to any host outside the pinned allow-list before connecting`() = runBlocking {
        // The PRODUCTION default host is the ONLY allowed host; any other URL is
        // refused before a connection opens (the local server is never hit).
        val (server, cert) = startServer(0, 200, body = validManifestJson)
        val productionDefault = HttpsManifestTransport(
            expectedCertPin = pinOf(cert),
            expectedHost = DEFAULT_MANIFEST_HOST
        )

        val result = productionDefault.fetch("https://127.0.0.1:${server.port}/manifest.json")

        assertTrue(result is ManifestFetchResult.Failed)
        assertTrue((result as ManifestFetchResult.Failed).message.contains(DEFAULT_MANIFEST_HOST))
        assertTrue("the non-allow-listed host must be refused before any connection", server.requestTargets.isEmpty())
    }

    @Test
    fun `rejects a 302 redirect to a cross-host and never opens the redirect target`() = runBlocking {
        val (target, _) = startServer(0, 200, body = validManifestJson)
        val (redirector, cert) = startServer(
            1,
            302,
            headers = mapOf("Location" to "https://localhost:${target.port}/evil-manifest.json"),
            body = ""
        )

        val result = transportFor(cert).fetch("https://127.0.0.1:${redirector.port}/manifest.json")

        assertTrue(result is ManifestFetchResult.Failed)
        assertTrue((result as ManifestFetchResult.Failed).message.contains("redirect"))
        assertEquals("the cross-host redirect target must never be fetched", emptyList<Any>(), target.requestTargets)
    }

    @Test
    fun `rejects an https to http redirect downgrade and the plaintext target is never contacted`() = runBlocking {
        val (plain, _) = startServer(0, 200, body = validManifestJson)
        val (redirector, cert) = startServer(
            1,
            302,
            headers = mapOf("Location" to "http://127.0.0.1:${plain.port}/plain-manifest"),
            body = ""
        )

        val result = transportFor(cert).fetch("https://127.0.0.1:${redirector.port}/manifest.json")

        assertTrue(result is ManifestFetchResult.Failed)
        assertTrue((result as ManifestFetchResult.Failed).message.contains("redirect"))
        assertEquals("the downgraded plaintext target must never be contacted", emptyList<Any>(), plain.requestTargets)
    }

    @Test
    fun `fails closed when the compiled-in pin is malformed`() = runBlocking {
        val (server, cert) = startServer(0, 200, body = validManifestJson)
        val transport = HttpsManifestTransport(
            expectedCertPin = "sha256/AAAA", // 3 bytes — not a real pin
            expectedHost = "127.0.0.1",
            trustManagerOverride = trustManagerFor(cert)
        )

        val result = transport.fetch("https://127.0.0.1:${server.port}/manifest.json")

        assertTrue(result is ManifestFetchResult.Failed)
        assertTrue((result as ManifestFetchResult.Failed).message.contains("disabled"))
        assertTrue("the malformed-pin build must not contact the manifest host", server.requestTargets.isEmpty())
    }

    @Test
    fun `the compiled-in host and pin constants are well-formed`() {
        // The shipped trust-anchor constants must be self-consistent and the pin
        // must be a parseable 32-byte digest (a build carrying a malformed pin
        // disables update checks — never silently degrades to unpinned HTTPS).
        assertEquals(DEFAULT_MANIFEST_HOST, "plugin-updates.inkflow.app")
        assertTrue(DEFAULT_PLUGIN_MANIFEST_URL.startsWith("https://$DEFAULT_MANIFEST_HOST/"))
        val parsed = PinnedCertHash.parse(PLUGIN_MANIFEST_CERT_PIN)
        assertTrue("the default manifest pin must be a 32-byte digest", parsed != null && parsed.size == 32)
    }
}

/**
 * A minimal single-responsibility local HTTPS server used by the transport
 * tests. Speaks one HTTP/1.1 response per connection; TLS is provided by an
 * injected [SSLContext]. Only `java.*`/`javax.*` is used so it runs in the
 * pure-JVM unit test classpath without adding modules or dependencies.
 */
private class TinyHttpsServer(
    sslContext: SSLContext,
    private val status: Int,
    private val headers: Map<String, String>,
    private val body: ByteArray
) {
    val port: Int

    val requestTargets = java.util.concurrent.CopyOnWriteArrayList<String>()

    private val serverSocket: ServerSocket
    private val acceptThread: Thread

    init {
        val factory = sslContext.serverSocketFactory as SSLServerSocketFactory
        serverSocket = factory.createServerSocket(0)
        port = serverSocket.localPort
        acceptThread = Thread {
            while (true) {
                val client = try {
                    serverSocket.accept()
                } catch (_: java.io.IOException) {
                    return@Thread
                }
                Thread {
                    try {
                        handleClient(client)
                    } catch (_: Throwable) {
                        // TLS handshake rejection (pin mismatch) lands here.
                    } finally {
                        runCatching { client.close() }
                    }
                }.also { it.isDaemon = true }.start()
            }
        }
        acceptThread.isDaemon = true
        acceptThread.start()
    }

    fun stop() {
        runCatching { serverSocket.close() }
    }

    private fun handleClient(client: Socket) {
        val input = client.getInputStream()
        val output = client.getOutputStream()
        val requestHead = readRequestHead(input)
        val target = requestHead.lineSequence()
            .firstOrNull()
            ?.substringAfter("GET ", "")
            ?.substringBefore(" HTTP/")
            ?: "/"
        requestTargets.add(target)

        val reason = when (status) {
            200 -> "OK"
            302 -> "Found"
            else -> "Response"
        }
        val statusLine = "HTTP/1.1 $status $reason\r\n"
        val headerLines = buildString {
            append(statusLine)
            headers.forEach { (k, v) -> append("$k: $v\r\n") }
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(headerLines.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun readRequestHead(input: InputStream): String {
        val buffer = ByteArrayOutputStream()
        val window = IntArray(4) { -1 }
        while (buffer.size() < 64 * 1024) {
            val b = input.read()
            if (b == -1) break
            window[0] = window[1]; window[1] = window[2]; window[2] = window[3]; window[3] = b
            buffer.write(b)
            if (window[0] == 0x0D && window[1] == 0x0A && window[2] == 0x0D && window[3] == 0x0A) break
        }
        return buffer.toString(Charsets.US_ASCII.name())
    }
}