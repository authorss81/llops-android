package com.authorss81.noteflow

import com.authorss81.noteflow.services.localsend.LocalSendDevice
import com.authorss81.noteflow.services.localsend.LocalSendHashing
import com.authorss81.noteflow.services.localsend.LocalSendMessages
import com.authorss81.noteflow.services.localsend.LocalSendProtocol
import com.authorss81.noteflow.services.localsend.guessMimeType
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-JVM tests for the LocalSend protocol layer. No network, no Android
 * framework, no devices: only JSON building/parsing, URL construction and
 * hashing against the LocalSend v2.2 spec shapes.
 */
class LocalSendProtocolTest {

    private val testInfo = LocalSendMessages.Info(
        alias = "InkFlow (Pixel)",
        version = "2.0",
        deviceModel = "Pixel 8",
        deviceType = "mobile",
        fingerprint = "inkflow-deadbeef",
        port = 53317,
        protocol = "http"
    )

    // ---- Announce ----

    @Test
    fun announceJsonHasProtocolShape() {
        val json = String(LocalSendMessages.buildAnnounce(testInfo), Charsets.UTF_8)
        val root = JsonParser.parseString(json).asJsonObject
        assertEquals("InkFlow (Pixel)", root.get("alias").asString)
        assertEquals("2.0", root.get("version").asString)
        assertEquals("Pixel 8", root.get("deviceModel").asString)
        assertEquals("mobile", root.get("deviceType").asString)
        assertEquals("inkflow-deadbeef", root.get("fingerprint").asString)
        assertEquals(53317, root.get("port").asInt)
        assertEquals("http", root.get("protocol").asString)
        assertEquals(true, root.get("announce").asBoolean)
        assertFalse(root.has("token")) // v2.2 no token; v2.0-only field must not appear
    }

    // ---- Sender identity (B1-NET-09 / phase-110): no device-model leak ----

    @Test
    fun senderIdentity_exposesNoDeviceModelOrVersion() {
        val info = LocalSendMessages.senderIdentity(fingerprint = "inkflow-abc123")
        assertEquals("InkFlow", info.alias)
        assertEquals(null, info.deviceModel)
        assertEquals("mobile", info.deviceType)
        assertFalse(info.alias.contains(BuildModelPlaceholder.PIXEL))
    }

    @Test
    fun senderIdentityAnnounceJson_doesNotLeakModel() {
        val info = LocalSendMessages.senderIdentity(fingerprint = "inkflow-abc123")
        val root = JsonParser.parseString(String(LocalSendMessages.buildAnnounce(info), Charsets.UTF_8)).asJsonObject
        assertEquals("InkFlow", root.get("alias").asString)
        assertEquals(true, root.get("announce").asBoolean)
        assertFalse(
            "Sender announce must not carry a device-model marker.",
            root.has("deviceModel")
        )
    }

    @Test
    fun senderIdentityRegisterBody_doesNotLeakModel() {
        val info = LocalSendMessages.senderIdentity(fingerprint = "inkflow-abc123")
        val root = JsonParser.parseString(LocalSendMessages.buildRegisterBody(info)).asJsonObject
        assertEquals("InkFlow", root.get("alias").asString)
        assertFalse(
            "Sender register body must not carry a device-model marker.",
            root.has("deviceModel")
        )
    }

    @Test
    fun senderIdentityPrepareUploadBody_doesNotLeakModel() {
        val info = LocalSendMessages.senderIdentity(fingerprint = "inkflow-abc123")
        val body = LocalSendMessages.buildPrepareUploadBody(
            info = info,
            fileId = "file-1",
            fileName = "note.md",
            sizeBytes = 1024,
            mimeType = "text/markdown",
            sha256Hex = null
        )
        val infoNode = JsonParser.parseString(body).asJsonObject.get("info").asJsonObject
        assertEquals("InkFlow", infoNode.get("alias").asString)
        assertFalse(infoNode.has("deviceModel"))
        assertFalse(body.contains(BuildModelPlaceholder.PIXEL))
    }

    private object BuildModelPlaceholder {
        const val PIXEL = "Pixel 8"
    }

    // ---- Discovery response parsing ----

    @Test
    fun parseUdpFallbackResponse_returnsDeviceWithSourceAddress() {
        val json = """
            {
              "alias": "Secret Banana",
              "version": "2.0",
              "deviceModel": "Windows",
              "deviceType": "desktop",
              "fingerprint": "ABC123",
              "port": 53317,
              "protocol": "https",
              "download": true,
              "announce": false
            }
        """.trimIndent()

        val device = LocalSendMessages.parseDiscoveryResponse(json, "192.168.1.42")
        assertNotNull(device)
        assertEquals("192.168.1.42", device!!.address)
        assertEquals(53317, device.port)
        assertEquals("https", device.protocol)
        assertEquals("Secret Banana", device.alias)
        assertEquals("ABC123", device.fingerprint)
        assertEquals(true, device.download)
        assertEquals("https://192.168.1.42:53317", device.baseUrl())
    }

    @Test
    fun parseDiscoveryResponse_withCustomPort_honorsWirePort() {
        val json = """{"alias":"Router","version":"2.0","port":12345,"protocol":"http","announce":false}"""
        val device = LocalSendMessages.parseDiscoveryResponse(json, "10.0.0.3")
        assertNotNull(device)
        assertEquals(12345, device!!.port)
        assertEquals("http://10.0.0.3:12345", device.baseUrl())
    }

    @Test
    fun parseDiscoveryResponse_garbage_isNull() {
        assertNull(LocalSendMessages.parseDiscoveryResponse("not json {", "10.0.0.9"))
        assertNull(LocalSendMessages.parseDiscoveryResponse("", "10.0.0.9"))
        assertNull(LocalSendMessages.parseDiscoveryResponse("{}", "10.0.0.9"))
        assertNull(LocalSendMessages.parseDiscoveryResponse("""{"alias":"","port":53317}""", "10.0.0.9"))
    }

    @Test
    fun parseDiscoveryResponse_ignoresUnrelatedPackets() {
        // A non-LocalSend UDP payload (e.g. mDNS or random noise) must be ignored.
        assertNull(LocalSendMessages.parseDiscoveryResponse("\u0000\u0001\u0000\u0001", "10.0.0.9"))
    }

    // ---- prepare-upload request body ----

    @Test
    fun prepareUploadBody_matchesSpecShape() {
        val body = LocalSendMessages.buildPrepareUploadBody(
            info = testInfo,
            fileId = "file-1",
            fileName = "note.md",
            sizeBytes = 1024,
            mimeType = "text/markdown",
            sha256Hex = "deadbeef0000"
        )
        val root = JsonParser.parseString(body).asJsonObject

        val info = root.get("info").asJsonObject
        assertEquals("InkFlow (Pixel)", info.get("alias").asString)
        assertEquals("Pixel 8", info.get("deviceModel").asString)

        val files = root.get("files").asJsonObject
        assertEquals(1, files.size())
        val meta = files.get("file-1").asJsonObject
        assertEquals("file-1", meta.get("id").asString)
        assertEquals("note.md", meta.get("fileName").asString)
        assertEquals(1024L, meta.get("size").asLong)
        assertEquals("text/markdown", meta.get("fileType").asString)
        assertEquals("deadbeef0000", meta.get("sha256").asString)
    }

    // ---- prepare-upload response parsing ----

    @Test
    fun parsePrepareUploadResponse_success() {
        val json = """{"sessionId":"sess_1","files":{"file-1":"tok_abc","other":"tok_xyz"}}"""
        val parsed = LocalSendMessages.parsePrepareUploadResponse(json)
        assertEquals("sess_1", parsed.sessionId)
        assertEquals("tok_abc", parsed.tokenFor("file-1"))
        assertEquals("tok_xyz", parsed.tokenFor("other"))
        assertNull(parsed.tokenFor("missing"))
    }

    @Test(expected = LocalSendMessages.LocalSendProtocolException::class)
    fun parsePrepareUploadResponse_missingSessionId_throws() {
        LocalSendMessages.parsePrepareUploadResponse("""{"files":{}}""")
    }

    @Test(expected = LocalSendMessages.LocalSendProtocolException::class)
    fun parsePrepareUploadResponse_garbage_throws() {
        LocalSendMessages.parsePrepareUploadResponse("garbage<nope>")
    }

    // ---- URL building ----

    @Test
    fun uploadUrl_carriesSessionFileToken() {
        val url = LocalSendMessages.buildUploadUrl(
            baseUrl = "http://192.168.1.42:53317",
            sessionId = "s/s/x",
            fileId = "f id",
            token = "t&k"
        )
        assertTrue(url.startsWith("http://192.168.1.42:53317/api/localsend/v2/upload?"))
        assertTrue(url.contains("sessionId=" + java.net.URLEncoder.encode("s/s/x", "UTF-8")))
        assertTrue(url.contains("fileId=" + java.net.URLEncoder.encode("f id", "UTF-8")))
        assertTrue(url.contains("token=" + java.net.URLEncoder.encode("t&k", "UTF-8")))
    }

    @Test
    fun cancelUrl_carriesSession() {
        val url = LocalSendMessages.buildCancelUrl("http://192.168.1.42:53317", "sess_1")
        assertEquals("http://192.168.1.42:53317/api/localsend/v2/cancel?sessionId=sess_1", url)
    }

    // ---- Hashing ----

    @Test
    fun sha256Hex_knownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            LocalSendHashing.sha256Hex("abc".toByteArray())
        )
    }

    @Test
    fun sha256HexOfFile_matchesInMemory() {
        val f = File.createTempFile("ls-test", ".bin")
        try {
            f.writeBytes("hello localsend".toByteArray())
            assertEquals(
                LocalSendHashing.sha256Hex("hello localsend".toByteArray()),
                LocalSendHashing.sha256HexOfFile(f)
            )
        } finally {
            f.delete()
        }
    }

    @Test
    fun fingerprintsMatch_normalizesCaseAndColons() {
        val announced = "AB:CD:EF01:2345"
        val cert = "abcdef012345"
        assertTrue(LocalSendHashing.fingerprintsMatch(announced, cert))
        assertFalse(LocalSendHashing.fingerprintsMatch("ab:00", "cd:11"))
        assertFalse(LocalSendHashing.fingerprintsMatch(null, "abc"))
        assertFalse(LocalSendHashing.fingerprintsMatch("abc", null))
    }

    @Test
    fun fingerprintsMatch_upperHex() {
        val upper = "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"
        val lower = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertTrue(LocalSendHashing.fingerprintsMatch(upper, lower))
    }

    @Test
    fun fingerprintsMatch_rejectsDifferencesBeyondTheFirstNibble() {
        // Phase 102 (B2-CRYPTO-01): the fingerprint compare must not short-
        // circuit on the first matching prefix — a flip deep in the hash is
        // still a mismatch (constant-time full-pair compare).
        val good = "AB:CD:EF01:2345:6789:ABCD:EF01:2345"
        val nearLast = "AB:CD:EF01:2345:6789:ABCD:EF01:2344"
        assertTrue(LocalSendHashing.fingerprintsMatch(good, good.replace(":", "").lowercase()))
        assertFalse(LocalSendHashing.fingerprintsMatch(good, nearLast))
    }

    // ---- Mime / base URL ----

    @Test
    fun guessMimeType_coversExports() {
        assertEquals("text/markdown", guessMimeType(File("note.md")))
        assertEquals("application/zip", guessMimeType(File("vault.zip")))
        assertEquals("application/octet-stream", guessMimeType(File("vault.nfb")))
        assertEquals("application/pdf", guessMimeType(File("note.pdf")))
        assertEquals("text/html", guessMimeType(File("note.HTML")))
        assertEquals("application/octet-stream", guessMimeType(File("weird.xyz")))
    }

    @Test
    fun baseUrl_bracketsIpv6() {
        val device = LocalSendDevice(
            address = "fe80::1", port = 53317, protocol = "http",
            alias = "v6", version = "2.0", deviceModel = null,
            deviceType = null, fingerprint = null
        )
        assertEquals("http://[fe80::1]:53317", device.baseUrl())
    }

    @Test
    fun constants_matchProtocolDefaults() {
        assertEquals(53317, LocalSendProtocol.DEFAULT_PORT)
        assertEquals("224.0.0.167", LocalSendProtocol.MULTICAST_ADDRESS)
        assertEquals("/api/localsend/v2/prepare-upload", LocalSendProtocol.PATH_PREPARE_UPLOAD)
        assertEquals("/api/localsend/v2/upload", LocalSendProtocol.PATH_UPLOAD)
        assertEquals("/api/localsend/v2/cancel", LocalSendProtocol.PATH_CANCEL)
        assertEquals("/api/localsend/v2/register", LocalSendProtocol.PATH_REGISTER)
    }
}