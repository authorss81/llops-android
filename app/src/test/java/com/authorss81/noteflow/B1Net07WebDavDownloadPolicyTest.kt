package com.authorss81.noteflow

import com.authorss81.noteflow.services.WebDavRemoteListingPolicy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Arrays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * B1-NET-07 (phase-86): the WebDAV "Download & Restore" path previously
 * (1) chose the "latest" backup as `matches.last()` — the last href in XML
 * DOCUMENT order, not the newest by filename timestamp, so a malicious or
 * simply out-of-order server could silently roll the restore back to an OLDER
 * archive; (2) streamed the `.nfb` GET into the cache with NO size limit (a
 * malicious server could stream unbounded bytes → disk-exhaustion DoS on
 * `webdav_download_import.nfb`); and (3) interpolated `remoteFolderName` into
 * every URL path without percent-encoding (a folder name like `../../Other`
 * routed uploads/downloads at unintended server paths).
 *
 * These tests exercise the new pure-JVM decision table
 * [WebDavRemoteListingPolicy] (timestamp-based newest selection across BOTH
 * name generations, a mid-stream copy cap, RFC 3986 folder-segment
 * encode + traversal rejection) and source-pin the wiring into
 * `WebDavSyncService.kt`.
 */
class B1Net07WebDavDownloadPolicyTest {

    // --- newest-by-timestamp selection (never XML order) --------------------

    @Test
    fun `listing with out-of-order hrefs picks the newest day-stamp`() {
        val xml = """
            <d:multistatus>
              <d:response><d:href>/dav/Noteflow_Vault/noteflow_vault_backup_2026-08-13_Aa.nfb</d:href></d:response>
              <d:response><d:href>/dav/Noteflow_Vault/noteflow_vault_backup_2026-08-15_Bb.nfb</d:href></d:response>
              <d:response><d:href>/dav/Noteflow_Vault/noteflow_vault_backup_2026-08-14_Cc.nfb</d:href></d:response>
            </d:multistatus>
        """.trimIndent()
        val hrefs = WebDavRemoteListingPolicy.findBackupHrefs(xml)

        assertEquals(3, hrefs.size)
        // 2026-08-15 is the newest, even though it is NOT the last href in XML order.
        assertTrue(hrefs[1].endsWith("noteflow_vault_backup_2026-08-15_Bb.nfb"))
        assertTrue(WebDavRemoteListingPolicy.newestBackupHref(hrefs)!!.endsWith("noteflow_vault_backup_2026-08-15_Bb.nfb"))
    }

    @Test
    fun `legacy epoch-millis names are newer-able than older day names`() {
        val legacyMillis = dayMillis("2026-08-16") + 12L * 60 * 60 * 1000 // 2026-08-16 ~noon UTC
        val legacy = "/dav/F/noteflow_vault_backup_$legacyMillis.nfb"
        val oldDay = "/dav/F/noteflow_vault_backup_2026-08-14_AbC123.nfb"

        assertEquals(legacyMillis, WebDavRemoteListingPolicy.filenameTimestampMillis(legacy))
        assertEquals(dayMillis("2026-08-14"), WebDavRemoteListingPolicy.filenameTimestampMillis(oldDay))
        assertTrue(WebDavRemoteListingPolicy.newestBackupHref(listOf(oldDay, legacy)) == legacy)
    }

    @Test
    fun `same-day files break ties deterministically by href not xml position`() {
        val a = "/dav/F/noteflow_vault_backup_2026-08-16_aAa.nfb"
        val b = "/dav/F/noteflow_vault_backup_2026-08-16_bBb.nfb"

        // Identical timestamps → the choice must be stable regardless of input order.
        assertEquals(a, WebDavRemoteListingPolicy.newestBackupHref(listOf(a, b)))
        assertEquals(a, WebDavRemoteListingPolicy.newestBackupHref(listOf(b, a)))
    }

    @Test
    fun `unparseable names score lowest and only win when nothing else carries a timestamp`() {
        val bogus = "/dav/F/noteflow_vault_backup_something_odd.nfb"
        val real = "/dav/F/noteflow_vault_backup_2026-08-14_x.nfb"

        assertNull(WebDavRemoteListingPolicy.filenameTimestampMillis(bogus))
        assertEquals(real, WebDavRemoteListingPolicy.newestBackupHref(listOf(bogus, real)))
        assertEquals(bogus, WebDavRemoteListingPolicy.newestBackupHref(listOf(bogus)))
    }

    @Test
    fun `timestamp extraction understands both name generations and rejects junk`() {
        assertNull(WebDavRemoteListingPolicy.filenameTimestampMillis("/dav/F/someotherfile.nfb"))
        assertNull(WebDavRemoteListingPolicy.filenameTimestampMillis("/dav/F/noteflow_vault_backup_.nfb"))
        assertNull(WebDavRemoteListingPolicy.filenameTimestampMillis("/dav/F/noteflow_vault_backup_17553x.nfb")) // non-digit legacy junk
        assertNull(WebDavRemoteListingPolicy.filenameTimestampMillis("/dav/F/noteflow_vault_backup_2026-08-99_xx.nfb")) // invalid calendar date
        assertEquals(
            1755302400000L,
            WebDavRemoteListingPolicy.filenameTimestampMillis("/dav/F/noteflow_vault_backup_1755302400000.nfb")
        )
    }

    @Test
    fun `empty listing yields null newest`() {
        assertNull(WebDavRemoteListingPolicy.findBackupHrefs("<d:multistatus></d:multistatus>"))
        assertNull(WebDavRemoteListingPolicy.newestBackupHref(emptyList()))
    }

    @Test
    fun `non-matching hrefs are excluded from the listing`() {
        val xml = """
            <d:multistatus>
              <d:response><d:href>/dav/F/noteflow_vault_backup_2026-08-16_x.nfb</d:href></d:response>
              <d:response><d:href>/dav/F/noteflow_vault_backup_2026-08-16_x.bak</d:href></d:response>
            </d:multistatus>
        """.trimIndent()
        val hrefs = WebDavRemoteListingPolicy.findBackupHrefs(xml)

        assertEquals(1, hrefs.size)
        assertTrue(hrefs.single().endsWith(".nfb"))
    }

    // --- bounded download (abort at the cap, mid-stream) ---------------------

    @Test
    fun `oversized response aborts at the cap without ever draining the stream`() {
        // Behavior tests use a SMALL explicit cap so the JVM never needs to hold
        // the real 400 MB constant in heap; the cap value itself is source-pinned.
        val cap = 8L * 1024 * 1024
        val drip = DripInputStream(
            totalBytes = cap + (200L * 1024L),
            chunkBytes = 64 * 1024
        )
        val out = ByteArrayOutputStream()

        try {
            WebDavRemoteListingPolicy.copyBounded(drip, out, cap)
            fail("expected an over-cap download to abort")
        } catch (e: WebDavRemoteListingPolicy.DownloadTooLargeException) {
            assertTrue("message must name the size violation", e.message.orEmpty().contains("too large"))
        }

        assertTrue("the read must ABORT mid-stream (never drain the whole body)", drip.yielded < drip.totalBytes)
        assertTrue(
            "the abort must happen AT the budget boundary: at most one read buffer of over-read",
            drip.yielded <= cap + WebDavRemoteListingPolicy.COPY_BUFFER_BYTES
        )
        assertTrue(
            "bytes must actually have flowed (a pre-fix copyTo would have drained everything)",
            drip.yielded > cap
        )
        // The target file must never exceed the budget.
        assertTrue(out.size().toLong() <= cap)
    }

    @Test
    fun `response equal to the cap exactly is accepted`() {
        val cap = 8L * 1024 * 1024
        val drip = DripInputStream(
            totalBytes = cap,
            chunkBytes = 64 * 1024
        )
        val out = ByteArrayOutputStream()

        val written = WebDavRemoteListingPolicy.copyBounded(drip, out, cap)

        assertEquals(cap, written)
        assertEquals(drip.totalBytes, drip.yielded)
        assertEquals(cap, out.size().toLong())
    }

    @Test
    fun `small response copies through byte-for-byte`() {
        val payload = "encrypted-vault-bytes".toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()

        val written = WebDavRemoteListingPolicy.copyBounded(
            ByteArrayInputStream(payload),
            out
        )

        assertEquals(payload.size.toLong(), written)
        assertTrue(Arrays.equals(payload, out.toByteArray()))
    }

    @Test
    fun `a stalled stream fails loudly instead of busy-spinning`() {
        val stall = object : InputStream() {
            override fun read(): Int = -1
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0 // frozen: never progresses
        }
        try {
            WebDavRemoteListingPolicy.copyBounded(stall, ByteArrayOutputStream())
            fail("a contract-breaking zero-progress stream must fail loudly")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("no progress"))
        }
    }

    // --- remote folder segment: encode + reject traversal/control -------------

    @Test
    fun `folder segment round-trips a plain name unchanged`() {
        assertEquals("Noteflow_Vault", WebDavRemoteListingPolicy.encodedRemoteFolderSegment("Noteflow_Vault"))
        assertEquals("My%20Folder", WebDavRemoteListingPolicy.encodedRemoteFolderSegment("My Folder"))
        assertEquals("t%C3%A9st", WebDavRemoteListingPolicy.encodedRemoteFolderSegment("tést"))
    }

    @Test
    fun `traversal folder names are rejected before any URL is built`() {
        for (bad in listOf(".", "..", "../../Other", "a/../b", "a\\..\\b", "a/b", "back\\slash")) {
            try {
                WebDavRemoteListingPolicy.encodedRemoteFolderSegment(bad)
                fail("expected folder segment '$bad' to be rejected")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `control characters in folder names are rejected`() {
        for (bad in listOf("a\u0000b", "a\u001fb", "a\u007fb")) {
            try {
                WebDavRemoteListingPolicy.encodedRemoteFolderSegment(bad)
                fail("expected control-char folder name to be rejected")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `blank folder names are rejected`() {
        for (bad in listOf("", "   ", "\t\n")) {
            try {
                WebDavRemoteListingPolicy.encodedRemoteFolderSegment(bad)
                fail("expected blank folder name to be rejected")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    // --- source pins --------------------------------------------------------

    @Test
    fun `WebDavSyncService selects the newest by policy not xml order`() {
        val source = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/WebDavSyncService.kt")
            .readText()
        assertTrue("download selection must route through the policy", source.contains("newestBackupHref"))
        assertFalse("the XML-order matches.last() must be gone", source.contains("matches.last()"))
        assertTrue("the listing must be parsed by the policy regex", source.contains("findBackupHrefs"))
        assertFalse("the inline listing regex must be gone", source.contains("val zipRegex"))
    }

    @Test
    fun `WebDavSyncService streams downloads through the bounded copy`() {
        val source = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/WebDavSyncService.kt")
            .readText()
        assertTrue("the GET body must route through the bounded copy", source.contains("copyBounded(input, output)"))
        assertFalse("the unbounded input.copyTo(output) must be gone", source.contains("input.copyTo(output)"))
        assertTrue(
            "the too-large condition must be caught with a clean message",
            source.contains("DownloadTooLargeException")
        )
    }

    @Test
    fun `every folder URL in WebDavSyncService encodes the segment`() {
        val source = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/WebDavSyncService.kt")
            .readText()
        val encodedUses = Regex("encodedRemoteFolderSegment\\(config\\.remoteFolderName\\)").findAll(source).toList()
        assertTrue("each of the 3 folder-URL interpolations must encode the segment", encodedUses.size >= 3)
        // No raw (unencoded) remoteFolderName interpolation into a URL path remains.
        assertFalse("raw folder interpolation into a URL must be gone", source.contains("remoteFolderName}/"))
        assertFalse("raw folder interpolation into a file URL must be gone", source.contains("remoteFolderName}/\$remoteFileName"))
    }

    @Test
    fun `the policy enforces the cap during the streaming loop and aligns with the restore budget`() {
        val src = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/WebDavRemoteListingPolicy.kt")
            .readText()
        assertTrue("the running total must be compared inside the loop", src.contains("if (total > maxBytes)"))
        assertTrue("the copy must use a fixed buffer, never readBytes", src.contains("ByteArray(COPY_BUFFER_BYTES)"))
        assertTrue("the abort must be a typed, catchable exception", src.contains("DownloadTooLargeException"))
        // The download cap is deliberately aligned with the restore path budget (400 MB).
        assertTrue("MAX_DOWNLOAD_BYTES = 400 MB", src.contains("const val MAX_DOWNLOAD_BYTES: Long = 400L * 1024 * 1024"))

        val importSrc = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/ImportExportService.kt").readText()
        assertTrue(
            "the restore-side budget is the same 400 MB constant this cap mirrors",
            importSrc.contains("const val MAX_BACKUP_INPUT_BYTES = 400L * 1024 * 1024")
        )
    }

    // --- helpers --------------------------------------------------------------

    private fun dayMillis(isoDate: String): Long =
        LocalDate.parse(isoDate).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    /**
     * Synthetic "chunked" stream: hands out at most [chunkBytes] per read and
     * counts what it yielded, so a test can prove the bounded copy stopped
     * early instead of draining the whole body (which pre-fix `copyTo` did).
     */
    private class DripInputStream(
        val totalBytes: Long,
        private val chunkBytes: Int
    ) : InputStream() {
        private var pos = 0L
        var yielded: Long = 0L
            private set

        override fun read(): Int {
            if (pos >= totalBytes) return -1
            pos++
            yielded = pos
            return 0x41
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= totalBytes) return -1
            val n = minOf(len.toLong(), chunkBytes.toLong(), totalBytes - pos).toInt()
            Arrays.fill(b, off, off + n, 0x41.toByte())
            pos += n
            yielded = pos
            return n
        }
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