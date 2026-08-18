package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.runtime.DEFAULT_DOWNLOAD_HOSTS
import com.authorss81.noteflow.plugins.runtime.DEFAULT_MANIFEST_HOST
import com.authorss81.noteflow.plugins.runtime.DownloadRequest
import com.authorss81.noteflow.plugins.runtime.DownloadTransport
import com.authorss81.noteflow.plugins.runtime.DownloadTransportResult
import com.authorss81.noteflow.plugins.runtime.PluginDownloader
import com.authorss81.noteflow.plugins.runtime.PluginEntry
import com.authorss81.noteflow.plugins.runtime.PluginEntrySource
import com.authorss81.noteflow.plugins.runtime.PluginVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 23: the pure-JVM [PluginDownloader] guards — TLS-only, explicit user
 * consent, size/free-space caps, app-private confinement, `.part` resume and
 * cancel cleanup — tested against a fake [DownloadTransport].
 */
class PluginDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun remoteEntry(
        url: String = "https://plugins.example.com/ocr-1.0.0.apk",
        sizeBytes: Long? = 100L
    ) = PluginEntry(
        id = "com.authorss81.noteflow.plugins.test.remote",
        name = "Remote Test",
        description = "Test remote plugin.",
        version = PluginVersion(1, 0, 0),
        capabilities = setOf(PluginCapability.OCR),
        category = "Vision",
        downloadUrl = url,
        installSizeBytes = sizeBytes,
        sha256 = "ab12cd34ef56",
        pinnedCertHash = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        source = PluginEntrySource.REMOTE
    )

    /** A transport that writes the requested bytes into the target file. */
    private fun writingTransport(bytes: ByteArray = "artifact-bytes".toByteArray()) =
        DownloadTransport { request ->
            request.target.parentFile?.mkdirs()
            request.target.writeBytes(bytes)
            DownloadTransportResult.Completed(bytes.size.toLong())
        }

    private val testHosts = setOf("plugins.example.com")

    @Test
    fun `a consented HTTPS download writes the artifact into the app-private dir`() = runBlocking {
        val dir = tmp.newFolder("plugins")
        val downloader = PluginDownloader(writingTransport(), allowedDownloadHosts = testHosts)
        val progress = mutableListOf<Float>()

        val outcome = downloader.download(remoteEntry(), dir, userConsented = true, onProgress = { progress.add(it) })

        assertTrue(outcome is PluginDownloader.DownloadOutcome.Success)
        val file = (outcome as PluginDownloader.DownloadOutcome.Success).file
        assertTrue(file.isFile)
        assertTrue(file.parentFile?.canonicalPath == dir.canonicalPath)
        assertEquals("artifact-bytes", file.readText())
        assertEquals(1f, progress.last(), 0f)
    }

    @Test
    fun `a download without explicit user consent is refused before any bytes move`() = runBlocking {
        val dir = tmp.newFolder("plugins")
        var touched = false
        val downloader = PluginDownloader(transport = DownloadTransport { request ->
            touched = true
            request.target.writeBytes("nope".toByteArray())
            DownloadTransportResult.Completed(4)
        }, allowedDownloadHosts = testHosts)

        val outcome = downloader.download(remoteEntry(), dir, userConsented = false)

        assertTrue(outcome is PluginDownloader.DownloadOutcome.Failed)
        assertTrue((outcome as PluginDownloader.DownloadOutcome.Failed).message.contains("consent"))
        assertFalse(touched)
    }

    @Test
    fun `a non-HTTPS url is refused`() = runBlocking {
        val dir = tmp.newFolder("plugins")
        var touched = false
        val downloader = PluginDownloader(transport = DownloadTransport { request ->
            touched = true
            request.target.writeBytes("nope".toByteArray())
            DownloadTransportResult.Completed(4)
        }, allowedDownloadHosts = testHosts)

        val outcome = downloader.download(remoteEntry(url = "http://plugins.example.com/ocr.apk"), dir, userConsented = true)

        assertTrue(outcome is PluginDownloader.DownloadOutcome.Failed)
        assertTrue((outcome as PluginDownloader.DownloadOutcome.Failed).message.contains("HTTPS"))
        assertFalse(touched)
    }

    @Test
    fun `a bundled entry is never downloaded`() = runBlocking {
        val dir = tmp.newFolder("plugins")
        var touched = false
        val downloader = PluginDownloader(transport = DownloadTransport { request ->
            touched = true
            request.target.writeBytes("nope".toByteArray())
            DownloadTransportResult.Completed(4)
        }, allowedDownloadHosts = testHosts)
        val bundled = remoteEntry().copy(source = PluginEntrySource.BUNDLED, downloadUrl = null, sha256 = null, pinnedCertHash = null)

        val outcome = downloader.download(bundled, dir, userConsented = true)

        assertTrue(outcome is PluginDownloader.DownloadOutcome.Failed)
        assertTrue((outcome as PluginDownloader.DownloadOutcome.Failed).message.contains("bundled"))
        assertFalse(touched)
    }

    @Test
    fun `an artifact above the hard cap is refused`() = runBlocking {
        val dir = tmp.newFolder("plugins")
        var touched = false
        val downloader = PluginDownloader(transport = DownloadTransport { request ->
            touched = true
            request.target.writeBytes("nope".toByteArray())
            DownloadTransportResult.Completed(4)
        }, allowedDownloadHosts = testHosts)
        val huge = remoteEntry(sizeBytes = PluginDownloader.MAX_ARTIFACT_BYTES + 1)

        val outcome = downloader.download(huge, dir, userConsented = true)

        assertTrue(outcome is PluginDownloader.DownloadOutcome.Failed)
        assertTrue((outcome as PluginDownloader.DownloadOutcome.Failed).message.contains("cap"))
        assertFalse(touched)
    }

    @Test
    fun `insufficient free space is refused`() = runBlocking {
        val dir = tmp.newFolder("plugins")
        var touched = false
        val downloader = PluginDownloader(
            transport = DownloadTransport { request ->
                touched = true
                request.target.writeBytes("nope".toByteArray())
                DownloadTransportResult.Completed(4)
            },
            freeSpace = { 10L }, // far below the 100-byte entry
            allowedDownloadHosts = testHosts
        )

        val outcome = downloader.download(remoteEntry(), dir, userConsented = true)

        assertTrue(outcome is PluginDownloader.DownloadOutcome.Failed)
        assertTrue((outcome as PluginDownloader.DownloadOutcome.Failed).message.contains("free space"))
        assertFalse(touched)
    }

    @Test
    fun `a transport failure reports the failure and removes the partial file`() = runBlocking {
        val dir = tmp.newFolder("plugins")
        val downloader = PluginDownloader(transport = DownloadTransport { request ->
            request.target.writeBytes("partial".toByteArray())
            DownloadTransportResult.Failed("server 503")
        }, allowedDownloadHosts = testHosts)

        val outcome = downloader.download(remoteEntry(), dir, userConsented = true)

        assertTrue(outcome is PluginDownloader.DownloadOutcome.Failed)
        assertEquals("server 503", (outcome as PluginDownloader.DownloadOutcome.Failed).message)
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `a cancelled download removes the partial file and does not report success`() = runBlocking {
        val dir = tmp.newFolder("plugins")
        val downloader = PluginDownloader(transport = DownloadTransport { request ->
            request.target.writeBytes("partial".toByteArray())
            DownloadTransportResult.Completed(7)
        }, allowedDownloadHosts = testHosts)

        val outcome = downloader.download(remoteEntry(), dir, userConsented = true, isActive = { false })

        assertTrue(outcome is PluginDownloader.DownloadOutcome.Failed)
        assertTrue((outcome as PluginDownloader.DownloadOutcome.Failed).message.contains("cancelled"))
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `a coroutine cancellation rethrows and removes the partial file`() {
        val dir = tmp.newFolder("plugins")
        val downloader = PluginDownloader(transport = DownloadTransport { request ->
            request.target.writeBytes("partial".toByteArray())
            throw CancellationException("stopped")
        }, allowedDownloadHosts = testHosts)

        try {
            runBlocking { downloader.download(remoteEntry(), dir, userConsented = true) }
            fail("expected CancellationException")
        } catch (_: CancellationException) {
            // expected
        }
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `an interrupted download resumes from the partial file`() = runBlocking {
        val dir = tmp.newFolder("plugins")
        // Pre-existing partial file from an interrupted attempt.
        val partial = java.io.File(dir, "com.authorss81.noteflow.plugins.test.remote-1.0.0.apk.part")
        partial.writeBytes("already-downloaded-bytes".toByteArray())
        val expectedResume = partial.length()

        var resumedFrom = -1L
        val downloader = PluginDownloader(transport = DownloadTransport { request ->
            resumedFrom = request.resumeFromBytes
            request.target.appendText("more")
            DownloadTransportResult.Completed(request.resumeFromBytes + 4)
        }, allowedDownloadHosts = testHosts)

        val outcome = downloader.download(remoteEntry(), dir, userConsented = true)

        assertTrue(outcome is PluginDownloader.DownloadOutcome.Success)
        assertEquals(expectedResume, resumedFrom)
        assertTrue((outcome as PluginDownloader.DownloadOutcome.Success).file.isFile)
    }

    @Test
    fun `the artifact file name is deterministic per entry`() {
        val entry = remoteEntry()
        assertEquals(
            "com.authorss81.noteflow.plugins.test.remote-1.0.0.apk",
            PluginDownloader.artifactFileNameFor(entry)
        )
    }

    // ---- B1-NET-03 (Phase 42): artifacts come from allow-listed hosts only ----

    @Test
    fun `a download whose host is not on the allow-list is refused before any bytes move`() = runBlocking {
        val dir = tmp.newFolder("plugins")
        var touched = false
        val downloader = PluginDownloader(
            transport = DownloadTransport { request ->
                touched = true
                request.target.writeBytes("nope".toByteArray())
                DownloadTransportResult.Completed(4)
            },
            allowedDownloadHosts = testHosts
        )

        val outcome = downloader.download(
            remoteEntry(url = "https://attacker.example/ocr-1.0.0.apk"),
            dir,
            userConsented = true
        )

        assertTrue(outcome is PluginDownloader.DownloadOutcome.Failed)
        assertTrue((outcome as PluginDownloader.DownloadOutcome.Failed).message.contains("allow-listed"))
        assertFalse("the unallow-listed host must never be contacted", touched)
    }

    @Test
    fun `the allowed host at a non-default port is refused - R2-B1N-05`() = runBlocking {
        val dir = tmp.newFolder("plugins")
        var touched = false
        val downloader = PluginDownloader(
            transport = DownloadTransport { request ->
                touched = true
                request.target.writeBytes("nope".toByteArray())
                DownloadTransportResult.Completed(4)
            },
            allowedDownloadHosts = testHosts
        )

        // `plugins.example.com` IS allow-listed by name — but the gate is a
        // (scheme, host, effective-port) triple now, so the same host on a
        // non-default port can never slip through the host-only compare.
        val outcome = downloader.download(
            remoteEntry(url = "https://plugins.example.com:8443/ocr-1.0.0.apk"),
            dir,
            userConsented = true
        )

        assertTrue(outcome is PluginDownloader.DownloadOutcome.Failed)
        assertTrue((outcome as PluginDownloader.DownloadOutcome.Failed).message.contains("allow-listed"))
        assertFalse("the non-default-port artifact host must never be contacted", touched)
    }

    @Test
    fun `the production default download hosts include the manifest host and refuse everything else`() = runBlocking {
        val dir = tmp.newFolder("plugins")
        val downloader = PluginDownloader(
            DownloadTransport { request ->
                request.target.writeBytes("artifact".toByteArray())
                DownloadTransportResult.Completed(8)
            }
        )

        // The default allow-list hosts every artifact on the manifest host...
        assertTrue(DEFAULT_DOWNLOAD_HOSTS.contains(DEFAULT_MANIFEST_HOST))
        val allowed = downloader.download(
            remoteEntry(url = "https://$DEFAULT_MANIFEST_HOST/ocr-1.0.0.apk"),
            dir,
            userConsented = true
        )
        assertTrue("manifest-host artifact -> ${(allowed as? PluginDownloader.DownloadOutcome.Failed)?.message}", allowed is PluginDownloader.DownloadOutcome.Success)

        // ...and nothing else.
        val elsewhere = downloader.download(
            remoteEntry(url = "https://plugins.example.com/ocr-1.0.0.apk"),
            dir,
            userConsented = true
        )
        assertTrue(elsewhere is PluginDownloader.DownloadOutcome.Failed)
        assertTrue((elsewhere as PluginDownloader.DownloadOutcome.Failed).message.contains("allow-listed"))
    }
}
