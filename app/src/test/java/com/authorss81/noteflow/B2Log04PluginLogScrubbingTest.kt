package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginLogPolicy
import com.authorss81.noteflow.plugins.PluginLogger
import com.authorss81.noteflow.plugins.runtime.ArtifactSignatureVerifier
import com.authorss81.noteflow.plugins.runtime.ClassLoaderFactory
import com.authorss81.noteflow.plugins.runtime.CompileTimePluginPinStore
import com.authorss81.noteflow.plugins.runtime.DownloadRequest
import com.authorss81.noteflow.plugins.runtime.DownloadTransport
import com.authorss81.noteflow.plugins.runtime.DownloadTransportResult
import com.authorss81.noteflow.plugins.runtime.HostedPluginVersion
import com.authorss81.noteflow.plugins.runtime.InMemoryPluginEntryStore
import com.authorss81.noteflow.plugins.runtime.InMemoryPluginUpdateStore
import com.authorss81.noteflow.plugins.runtime.PinnedPluginRelease
import com.authorss81.noteflow.plugins.runtime.PluginArtifactResolver
import com.authorss81.noteflow.plugins.runtime.PluginContextFactory
import com.authorss81.noteflow.plugins.runtime.PluginDownloader
import com.authorss81.noteflow.plugins.runtime.PluginEntry
import com.authorss81.noteflow.plugins.runtime.PluginEntryCodec
import com.authorss81.noteflow.plugins.runtime.PluginEntrySource
import com.authorss81.noteflow.plugins.runtime.PluginManifestParser
import com.authorss81.noteflow.plugins.runtime.PluginUpdateEngine
import com.authorss81.noteflow.plugins.runtime.PluginVersion
import com.authorss81.noteflow.plugins.runtime.RuntimeOutcome
import com.authorss81.noteflow.plugins.runtime.RuntimePluginLoader
import java.io.File
import java.net.URLClassLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * B2-LOG-04 (phase-93): no plugin failure/log line may echo an attacker-
 * controllable `downloadUrl` or carry CR/LF (logcat line-forgery / exfil echo).
 *
 * Three mechanical layers are pinned here:
 *  1. [PluginLogPolicy] — the pure-JVM decision table (CR/LF detection/strip,
 *     URL-token swallow) used by the [com.authorss81.noteflow.plugins.AndroidPluginLogger]
 *     sink.
 *  2. Model validation — [PluginEntry.validationErrors] and
 *     [HostedPluginVersion.validationErrors] refuse CR/LF ids/names/downloadUrls
 *     so a line-forgery vehicle never enters the pipeline (manifests and
 *     persisted catalog blobs are rejected whole).
 *  3. Call sites — the download/install/update/store failure paths log FIXED
 *     tokens (reason codes / stages) instead of `reason.substringBefore('.')`,
 *     and the downloader's own guard refusals never reach the logger at all.
 */
class B2Log04PluginLogScrubbingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val remoteId = "com.authorss81.noteflow.plugins.remote.ocr"

    private fun remoteEntry(
        id: String = remoteId,
        name: String = "Remote OCR",
        downloadUrl: String = "https://plugins.example.com/ocr-1.0.0.apk",
        version: PluginVersion = PluginVersion(1, 0, 0)
    ) = PluginEntry(
        id = id,
        name = name,
        description = "Heavy downloadable OCR engine.",
        version = version,
        capabilities = setOf(PluginCapability.OCR),
        category = "Vision",
        downloadUrl = downloadUrl,
        sha256 = "ab12cd34ef56",
        pinnedCertHash = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        source = PluginEntrySource.REMOTE
    )

    /** Pure-JVM capture of the [PluginLogger] contract, injected as the seam. */
    private class RecordingPluginLogger : PluginLogger {
        val lines = mutableListOf<String>()
        override fun lifecycle(event: String, pluginId: String, pluginName: String) {
            lines += "lifecycle event=$event plugin=$pluginName id=$pluginId"
        }

        override fun error(pluginId: String, pluginName: String, detail: String) {
            lines += "error plugin=$pluginName id=$pluginId detail=$detail"
        }
    }

    // ---- 1. PluginLogPolicy decision table ---------------------------------

    @Test
    fun `hasLineBreak detects CR and LF in any position`() {
        assertTrue("LF alone", PluginLogPolicy.hasLineBreak("\n"))
        assertTrue("CR alone", PluginLogPolicy.hasLineBreak("\r"))
        assertTrue("CRLF pair", PluginLogPolicy.hasLineBreak("\r\n"))
        assertTrue("embedded LF", PluginLogPolicy.hasLineBreak("evil\nid"))
        assertTrue("embedded CR", PluginLogPolicy.hasLineBreak("evil\rid"))
        assertFalse(PluginLogPolicy.hasLineBreak("com.authorss81.noteflow.plugins.remote.ocr"))
        assertFalse(PluginLogPolicy.hasLineBreak(""))
    }

    @Test
    fun `stripLineBreaks removes every CR and LF`() {
        assertEquals("abcXY", PluginLogPolicy.stripLineBreaks("a\rb" + "c\nX" + "Y"))
        assertEquals("", PluginLogPolicy.stripLineBreaks("\r\n\r\n"))
        assertEquals("clean", PluginLogPolicy.stripLineBreaks("clean"))
    }

    @Test
    fun `safeLine swallows URL-shaped tokens and never forges a logcat line`() {
        val hostile = "update failed https://attacker.example/steal?note=secret&vault=v\nEVIL SECOND LOG LINE\r\r\n"
        val safe = PluginLogPolicy.safeLine(hostile)
        assertFalse("no CR/LF may survive", safe.indexOf('\n') >= 0 || safe.indexOf('\r') >= 0)
        assertFalse("hostile host must not be echoed", safe.contains("attacker.example"))
        assertFalse("query exfil must not be echoed", safe.contains("steal?note"))
        assertFalse("forged line never appears", safe.contains("EVIL SECOND LOG LINE"))
        assertTrue("URL is replaced with a marker", safe.contains("<url>"))
    }

    @Test
    fun `safeLine redacts URLs only up to the next space and keeps fixed text`() {
        val safe = PluginLogPolicy.safeLine("downloadUrl https://plugins.example.com/x.apk refused")
        assertTrue(safe.contains("downloadUrl"))
        assertTrue(safe.contains("<url>"))
        assertTrue(safe.contains("refused"))
        assertFalse(safe.contains("plugins.example.com"))
    }

    @Test
    fun `safeLine redacts uppercase and alternate url schemes`() {
        // Phase-93 review fix (FINDING #3): the token regex used to be
        // `https?://…` and case-sensitive — an uppercase HTTPS or a non-http
        // scheme URL slipped straight through to the "sink".
        assertEquals(
            "refused <url> please retry",
            PluginLogPolicy.safeLine("refused HTTPS://attacker.example/steal please retry")
        )
        assertTrue(PluginLogPolicy.safeLine("refused ftp://attacker.example/x.apk").contains("<url>"))
        assertFalse(PluginLogPolicy.safeLine("refused ftp://attacker.example/x.apk").contains("attacker.example"))
    }

    @Test
    fun `the production log line compositors run through safeLine`() {
        // Phase-93 review fix (FINDING #6): the exact composed lines the
        // AndroidPluginLogger logcat sink writes are built HERE (pure JVM) so a
        // regression in the sink's scrubbing is caught without an Android device.
        val hostileId = "evil\nid"
        val hostileName = "evil\rname"
        val hostileDetail = "verification failed for https://attacker.example/steal?note=secret\nFORGED LINE"
        val errorLine = PluginLogPolicy.errorLine(hostileId, hostileName, hostileDetail)
        assertTrue("no CR/LF may survive the composed error line", isLineBreakFree(errorLine))
        assertFalse(errorLine.contains("attacker.example"))
        assertFalse(errorLine.contains("steal?note"))
        assertFalse(errorLine.contains("FORGED LINE"))
        assertTrue(errorLine.contains("<url>"))

        val lifecycleLine = PluginLogPolicy.lifecycleLine("store-remote-download", hostileId, hostileName)
        assertTrue("no CR/LF may survive the composed lifecycle line", isLineBreakFree(lifecycleLine))
    }

    private fun isLineBreakFree(value: String): Boolean =
        value.indexOf('\n') < 0 && value.indexOf('\r') < 0

    // ---- 2. Model rejection of CR/LF security fields ------------------------

    @Test
    fun `PluginEntry refuses CR and LF in id and name`() {
        assertTrue(remoteEntry(id = "evil\nid").isValid().not())
        assertTrue(remoteEntry(id = "evil\nid").validationErrors().any { it.contains("line break") })
        assertTrue(remoteEntry(name = "evil\rname").validationErrors().any { it.contains("line break") })
        // Fixed text only — the hostile value is never echoed back into the error.
        assertFalse(remoteEntry(id = "evil\nid").validationErrors().any { it.contains("evil") })
        assertTrue(remoteEntry().isValid())
    }

    @Test
    fun `PluginEntry refuses CR and LF in downloadUrl`() {
        val withLf = remoteEntry(downloadUrl = "https://plugins.example.com/x\nEVIL.apk")
        assertTrue(withLf.validationErrors().any { it.contains("line break") })
        assertFalse(withLf.isValid())
        val withCr = remoteEntry(downloadUrl = "https://plugins.example.com\r/x.apk")
        assertTrue(withCr.validationErrors().any { it.contains("line break") })
    }

    @Test
    fun `PluginEntryCodec refuses a persisted blob whose id carries CR LF`() {
        val codec = PluginEntryCodec()
        val hostile = codec.encode(remoteEntry(id = "evil\nid"))
        // A CR/LF id makes the decoded entry invalid — refused on decode, so a
        // hostile hand-edited catalog blob can never reach the registry/logs.
        assertNull(codec.decode(hostile))
        // The clean round-trip still works.
        val roundTrip = codec.decode(codec.encode(remoteEntry()))
        assertTrue(roundTrip?.isValid() == true)
    }

    @Test
    fun `the manifest parser refuses a CR-LF id or downloadUrl whole`() {
        val parser = PluginManifestParser()
        val hostileId = parser.parse(
            """{"plugins":[{"id":"evil\nid","version":"1.0.0",""" +
                """"downloadUrl":"https://plugins.example.com/x.apk","sha256":"ab12cd34ef56",""" +
                """"pinnedCertHash":"sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="}]}"""
        )
        assertFalse(hostileId.isValid)
        assertTrue(hostileId.errors.any { it.contains("line break") })

        val hostileUrl = parser.parse(
            """{"plugins":[{"id":"com.ok.plugin","version":"1.0.0",""" +
                """"downloadUrl":"https://plugins.example.com/x\nEVIL.apk","sha256":"ab12cd34ef56",""" +
                """"pinnedCertHash":"sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="}]}"""
        )
        assertFalse(hostileUrl.isValid)
        assertTrue(hostileUrl.errors.any { it.contains("line break") })

        val clean = parser.parse(
            """{"plugins":[{"id":"com.ok.plugin","version":"1.0.0",""" +
                """"downloadUrl":"https://plugins.example.com/x.apk","sha256":"ab12cd34ef56",""" +
                """"pinnedCertHash":"sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="}]}"""
        )
        assertTrue(clean.isValid)
        assertEquals(1, (clean as com.authorss81.noteflow.plugins.runtime.ManifestParseResult.Valid).manifest.plugins.size)
    }

    @Test
    fun `HostedPluginVersion validation errors never echo a CR-LF id or url`() {
        // Phase-93 review fix (FINDING #4): the CR/LF-refuse checks are fixed-text,
        // but the SIBLING validation messages still cited the id/url verbatim
        // ("manifest entry for '<id>'…", "…(got '<url>')") — with a newline in the
        // field the CR/LF would travel inside those strings. All value-echoing
        // messages now redact through PluginLogPolicy.redactLineBreak.
        val hostile = HostedPluginVersion(
            id = "evil\nid",
            version = PluginVersion(1, 0, 0),
            downloadUrl = "http://bad\r.example/x.apk",
            sha256 = "ab12cd34ef56",
            pinnedCertHash = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        )
        val errors = hostile.validationErrors()
        assertTrue(errors.any { it.contains("line break") })
        assertTrue("validation errors must be CR/LF-free", errors.all { isLineBreakFree(it) })
        assertFalse("hostile id must never be echoed", errors.any { it.contains("evil") })
        assertFalse("hostile url must never be echoed", errors.any { it.contains("bad") })
    }

    @Test
    fun `manifest parse errors never echo a CR-LF id or url`() {
        // Phase-93 review fix (FINDING #4), parse level: the "invalid version" and
        // "listed more than once" messages embed the id directly — now redacted.
        val parser = PluginManifestParser()
        val version = parser.parse(
            """{"plugins":[{"id":"evil\nid","version":"not-semver","downloadUrl":"https://plugins.example.com/x.apk"}]}"""
        )
        assertFalse(version.isValid)
        assertTrue("invalid-version message must be CR/LF-free", version.errors.all { isLineBreakFree(it) })
        assertFalse("hostile id must never be echoed", version.errors.any { it.contains("evil") })

        val duplicate = parser.parse(
            """{"plugins":[""" +
                """{"id":"dup\nid","version":"1.0.0","downloadUrl":"https://plugins.example.com/x.apk",""" +
                """"sha256":"ab12cd34ef56","pinnedCertHash":"pin"},""" +
                """{"id":"dup\nid","version":"1.0.0","downloadUrl":"https://plugins.example.com/y.apk",""" +
                """"sha256":"ab12cd34ef56","pinnedCertHash":"pin"}]}"""
        )
        assertFalse(duplicate.isValid)
        assertTrue("duplicate-id message must be CR/LF-free", duplicate.errors.all { isLineBreakFree(it) })
        assertFalse("hostile id must never be echoed", duplicate.errors.any { it.contains("dup") || it.contains('\n') || it.contains('\r') })
    }

    // ---- 3. Call sites -------------------------------------------------------

    @Test
    fun `the downloader's guard refusals never reach the logger`() = runBlocking {
        val log = RecordingPluginLogger()
        val downloader = PluginDownloader(
            transport = DownloadTransport { DownloadTransportResult.Completed(1) },
            freeSpace = { Long.MAX_VALUE },
            // Empty allow-list ⇒ a hostile download host is refused outright.
            allowedDownloadHosts = emptySet(),
            logger = log
        )
        val outcome = downloader.download(
            entry = remoteEntry(downloadUrl = "https://attacker.example/steal.apk"),
            targetDir = tmp.root,
            userConsented = true,
            onProgress = {}
        )
        // Consented + HTTPS, but the host gate refuses BEFORE logging anything —
        // the hostile URL exists only in the user-facing outcome message.
        assertTrue(outcome is PluginDownloader.DownloadOutcome.Failed)
        assertEquals(0, log.lines.size)
    }

    @Test
    fun `a download-stage update failure logs a fixed stage code not the hostile transport message`() = runBlocking {
        val log = RecordingPluginLogger()
        val entry = remoteEntry(version = PluginVersion(1, 0, 0))
        val target = remoteEntry(version = PluginVersion(2, 0, 0))
        val entryStore = InMemoryPluginEntryStore()
        entryStore.save(entry)
        val storageDir = File(tmp.root, "plugins").apply { mkdirs() }
        val pins = CompileTimePluginPinStore(
            PinnedPluginRelease(
                target.id,
                target.version,
                target.sha256!!,
                target.pinnedCertHash!!
            ),
            allowedDownloadHosts = setOf("plugins.example.com")
        )
        val downloader = PluginDownloader(
            // The "server" answer embeds a hostile URL + CR/LF == the B2-LOG-04 shape.
            transport = HostileFailedTransport(
                "network failure https://attacker.example/exfil?note=secret\nFORGED-END"
            ),
            freeSpace = { Long.MAX_VALUE },
            allowedDownloadHosts = setOf("plugins.example.com"),
            logger = log
        )
        val loader = RuntimePluginLoader(
            classLoaderFactory = ClassLoaderFactory { artifactPath, parent ->
                URLClassLoader(arrayOf(File(artifactPath).toURI().toURL()), parent)
            },
            contextFactory = PluginContextFactory.DEFAULT,
            parentClassLoader = B2Log04PluginLogScrubbingTest::class.java.classLoader ?: javaClass.classLoader
        )
        val engine = PluginUpdateEngine(
            downloader = downloader,
            storageDir = storageDir,
            artifactResolver = PluginArtifactResolver { null },
            entryStore = entryStore,
            updateStore = InMemoryPluginUpdateStore(),
            verifier = ArtifactSignatureVerifier(),
            loader = loader,
            pins = pins,
            logger = log
        )

        val outcome = engine.update(entry, target, userApproved = true, onProgress = {})

        assertTrue(outcome is RuntimeOutcome.Failed)
        assertTrue((outcome as RuntimeOutcome.Failed).message.contains("still active"))
        // Exactly one error line, a FIXED token: no URL, no CR/LF, no forged text.
        val errorLines = log.lines.filter { it.startsWith("error") }
        assertEquals(1, errorLines.size)
        assertTrue(errorLines.single().contains("stage=download"))
        assertFalse(errorLines.single().contains("attacker.example"))
        assertFalse(errorLines.single().contains("exfil"))
        assertFalse(errorLines.single().contains("FORGED-END"))
        assertFalse(errorLines.single().contains("network failure"))
        assertFalse(errorLines.single().indexOf('\n') >= 0 || errorLines.single().indexOf('\r') >= 0)
    }

    private class HostileFailedTransport(private val message: String) : DownloadTransport {
        override suspend fun download(request: DownloadRequest): DownloadTransportResult =
            DownloadTransportResult.Failed(message)
    }
}
