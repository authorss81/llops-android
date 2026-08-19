package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.FileTransferKind
import com.authorss81.noteflow.plugins.FileTransferOutcome
import com.authorss81.noteflow.plugins.FileTransferPlugin
import com.authorss81.noteflow.plugins.FileTransferRequest
import com.authorss81.noteflow.plugins.PluginAvailability
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginManager
import com.authorss81.noteflow.plugins.PluginPermission
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.PluginResult
import com.authorss81.noteflow.plugins.filetransfer.LocalSendFileTransferPlugin
import com.authorss81.noteflow.services.localsend.FileTransferSender
import com.authorss81.noteflow.services.localsend.LocalSendDevice
import com.authorss81.noteflow.services.localsend.LocalSendSender
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

/**
 * Phase 173 feature 1: the LocalSend-backed FileTransfer plugin maps capability
 * calls to the EXISTING LocalSend sender without touching the network.
 *
 * The plugin never constructs LocalSend messages or endpoints itself — it
 * delegates to the [FileTransferSender] seam whose production implementation is
 * exactly [LocalSendSender] (the sender the HomeScreen dialog uses). These tests
 * inject a recording fake for that seam and prove:
 * - the request's device + file pass through UNCHANGED to one `sendFile` call;
 * - discovery delegates to the sender's UDP-only discovery (legacy /24 HTTP
 *   sweep never auto-enabled);
 * - every fail-closed path (no sender in context, empty/missing file, sender
 *   failure) maps to a typed outcome and never touches a network;
 * - capability routing through [PluginManager] requires opt-in and resolves the
 *   plugin after opt-in.
 */
class FileTransferPluginPolicyTest {

    private val device = LocalSendDevice(
        address = "192.168.1.5",
        port = 53317,
        protocol = "https",
        alias = "Pixel 8a",
        version = "2.0",
        deviceModel = null,
        deviceType = null,
        fingerprint = "AABBCCDD"
    )

    /** Records every sender interaction; returns the configured [LocalSendSender.SendResult]. */
    private class RecordingSender(
        var sendResult: LocalSendSender.SendResult = LocalSendSender.SendResult(true, "Sent to Pixel 8a", 42L),
        val discovered: List<LocalSendDevice> = emptyList()
    ) : FileTransferSender {
        var sendCalls = 0
        var discoverCalls = 0
        var lastDevice: LocalSendDevice? = null
        var lastFile: File? = null
        var lastTimeoutMs = -1L
        var lastLegacyHttpScan: Boolean? = null

        override suspend fun discoverDevices(
            discoveryTimeoutMs: Long,
            includeLegacyHttpScan: Boolean
        ): List<LocalSendDevice> {
            discoverCalls++
            lastTimeoutMs = discoveryTimeoutMs
            lastLegacyHttpScan = includeLegacyHttpScan
            return discovered
        }

        override suspend fun sendFile(
            device: LocalSendDevice,
            file: File,
            onProgress: (sentBytes: Long, totalBytes: Long) -> Unit
        ): LocalSendSender.SendResult {
            sendCalls++
            lastDevice = device
            lastFile = file
            return sendResult
        }
    }

    private fun tempFile(name: String = "exported-note.html", bytes: ByteArray = "hello".toByteArray()): File {
        val f = File.createTempFile(name, ".tmp")
        f.writeBytes(bytes)
        f.deleteOnExit()
        return f
    }

    private fun request(file: File) = FileTransferRequest(
        kind = FileTransferKind.NOTE_HTML,
        file = file,
        device = device
    )

    @Test
    fun `manifest declares the FileTransfer capability and Internet permission only`() {
        val plugin = LocalSendFileTransferPlugin()
        assertEquals("plugins.filetransfer", plugin.id)
        assertEquals(setOf(PluginCapability.FileTransfer), plugin.capabilities)
        assertEquals(setOf(PluginPermission.Internet), plugin.manifest.permissions)
        assertEquals(26, plugin.manifest.minSupportedApi)
        // Base-APK rule: the plugin is compile-time (not a native/ML dependency).
        assertTrue(plugin.capabilities.contains(PluginCapability.FileTransfer))
    }

    @Test
    fun `availability is Ok because the real consent gates live inside the sender`() {
        val plugin = LocalSendFileTransferPlugin()
        assertEquals(PluginAvailability.Ok, plugin.availability(context = null))
    }

    @Test
    fun `sendFile passes the request device and file through to the sender unchanged`() = runBlocking {
        val sender = RecordingSender()
        val plugin = LocalSendFileTransferPlugin(senderFactory = { sender })
        val file = tempFile()

        val outcome = plugin.sendFile(context = null, request = request(file))

        assertEquals(1, sender.sendCalls)
        assertEquals(device, sender.lastDevice)
        assertEquals(file, sender.lastFile)
        assertTrue(outcome is FileTransferOutcome.Sent)
        outcome as FileTransferOutcome.Sent
        assertEquals(42L, outcome.bytesSent)
        assertEquals("Sent to Pixel 8a", outcome.description)
    }

    @Test
    fun `sender failure maps to a scrubbed, fail-closed Rejected outcome`() = runBlocking {
        val sender = RecordingSender(
            sendResult = LocalSendSender.SendResult(
                success = false,
                description = "Refusing to send: the receiving device is not paired yet. /home/runner/private/vault key."
            )
        )
        val plugin = LocalSendFileTransferPlugin(senderFactory = { sender })
        val file = tempFile()

        val outcome = plugin.sendFile(context = null, request = request(file))

        assertTrue(outcome is FileTransferOutcome.Rejected)
        val message = (outcome as FileTransferOutcome.Rejected).message
        assertTrue(message.contains("not paired"))
        // R2-b2b3-LOG-03: the raw app-private path never reaches the user.
        assertNotEquals("Refusing to send: the receiving device is not paired yet. /home/runner/private/vault key.", message)
        assertTrue("private/vault key".encodeToByteArray().size > 0 && !message.contains("/home/runner/private"))
    }

    @Test
    fun `no sender in this context fails closed without a network call`() = runBlocking {
        val plugin = LocalSendFileTransferPlugin(senderFactory = { null })
        val file = tempFile()

        val outcome = plugin.sendFile(context = null, request = request(file))

        assertTrue(outcome is FileTransferOutcome.Error)
    }

    @Test
    fun `empty or missing file fails closed before the sender is ever touched`() = runBlocking {
        val sender = RecordingSender()
        val plugin = LocalSendFileTransferPlugin(senderFactory = { sender })
        val missing = File("/nonexistent/export.html")

        val outcome = plugin.sendFile(context = null, request = request(missing))

        assertTrue(outcome is FileTransferOutcome.Error)
        assertEquals(0, sender.sendCalls)
    }

    @Test
    fun `discover delegates to the sender with UDP-only semantics`() = runBlocking {
        val discovery = listOf(device)
        val sender = RecordingSender(discovered = discovery)
        val plugin = LocalSendFileTransferPlugin(senderFactory = { sender })

        val found = plugin.discover(context = null, timeoutMillis = 1500L)

        assertEquals(discovery, found)
        assertEquals(1, sender.discoverCalls)
        assertEquals(1500L, sender.lastTimeoutMs)
        // B1-NET-06: the legacy /24 HTTP register sweep is never auto-enabled.
        assertEquals(false, sender.lastLegacyHttpScan)
    }

    @Test
    fun `discover returns null when no sender is available`() = runBlocking {
        val plugin = LocalSendFileTransferPlugin(senderFactory = { null })
        assertEquals(null, plugin.discover(context = null, timeoutMillis = 100L))
    }

    @Test
    fun `capability routing fails loudly until opt-in and resolves after`() = runBlocking {
        val enable = InMemoryEnableStore()
        val plugin = LocalSendFileTransferPlugin(senderFactory = { null })
        val registry = PluginRegistry(enable, plugins = listOf(plugin), currentApiLevel = 26)
        val manager = PluginManager(registry)

        val before = manager.withPluginAsync(PluginCapability.FileTransfer, null) {
            (it as FileTransferPlugin).sendFile(null, request(tempFile()))
        }
        assertTrue(before is PluginResult.Failure)
        assertTrue((before as PluginResult.Failure).message.contains("enable"))

        registry.setEnabled(plugin.id, true)
        val after = manager.withPluginAsync(PluginCapability.FileTransfer, null) {
            (it as FileTransferPlugin).sendFile(null, request(tempFile()))
        }
        // The route reached the plugin; with no real Android context it fails
        // CLOSED with a typed Error (never a silent no-op).
        assertTrue(after is PluginResult.Success)
        assertTrue((after as PluginResult.Success).value is FileTransferOutcome.Error)
    }
}