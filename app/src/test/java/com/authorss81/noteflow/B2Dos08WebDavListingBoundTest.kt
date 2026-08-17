package com.authorss81.noteflow

import com.authorss81.noteflow.services.WebDavFailurePolicy
import com.authorss81.noteflow.services.WebDavRemoteListingPolicy
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Arrays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * B2-DOS-08 (phase-98): `WebDavSyncService.kt` previously read the Depth-1
 * PROPFIND listing body with `listConn.inputStream.bufferedReader().use { it.readText() }`
 * — the WHOLE response buffered in one String with no boundary before the href
 * regex. A malicious/misconfigured server answering with a multi-GB XML document
 * (or an endless drip — readText() waits for EOF) made the app OOM on sync.
 *
 * The fix is [WebDavRemoteListingPolicy.scanBackupHrefs]: a pure-JVM scan that
 * reads the SAME body under a hard cap ([WebDavRemoteListingPolicy.MAX_LISTING_BYTES]),
 * commits each href as its `</d:href>` closing tag arrives (never the whole
 * document in memory), and ABORTS mid-stream with [WebDavRemoteListingPolicy.ListingTooLargeException]
 * on the first chunk that crosses the cap. The extraction rules mirror
 * [WebDavRemoteListingPolicy.findBackupHrefs] byte-for-byte, so old and new
 * listings match identically.
 */
class B2Dos08WebDavListingBoundTest {

    // --- the cap is enforced DURING the read, never after a full buffer --------

    @Test
    fun `over-cap listing fails with ListingTooLargeException without full buffering`() {
        // Behavior tests use a SMALL explicit cap so the JVM never holds the
        // real 4 MB constant; the cap value itself is source-pinned below.
        val cap = 4096L
        val drip = DripInputStream(
            totalBytes = cap + (200L * 1024L),
            chunkBytes = 64 * 1024
        )

        try {
            WebDavRemoteListingPolicy.scanBackupHrefs(drip, cap)
            fail("expected an over-cap listing to abort")
        } catch (e: WebDavRemoteListingPolicy.ListingTooLargeException) {
            assertTrue("message must name the size violation", e.message.orEmpty().contains("too large"))
        }

        assertTrue("the scan must ABORT mid-stream (never drain the whole body)", drip.yielded < drip.totalBytes)
        assertTrue(
            "the abort must happen AT the budget boundary: at most one read buffer of over-read",
            drip.yielded <= cap + WebDavRemoteListingPolicy.LISTING_CHUNK_BYTES
        )
        assertTrue(
            "bytes must actually have flowed (a pre-fix readText would have drained everything)",
            drip.yielded > cap
        )
    }

    @Test
    fun `listing equal to the cap exactly is accepted`() {
        val xml = listingXml(
            "/dav/F/noteflow_vault_backup_2026-08-16_Aa.nfb",
            "/dav/F/noteflow_vault_backup_2026-08-15_Bb.nfb"
        )
        val bytes = xml.toByteArray(Charsets.UTF_8)

        val hrefs = WebDavRemoteListingPolicy.scanBackupHrefs(
            ByteArrayInputStream(bytes),
            maxBytes = bytes.size.toLong()
        )

        assertEquals(2, hrefs.size)
        assertTrue(hrefs[0].endsWith("noteflow_vault_backup_2026-08-16_Aa.nfb"))
    }

    @Test
    fun `a listing below the cap with no hrefs yields empty`() {
        assertTrue(
            WebDavRemoteListingPolicy.scanBackupHrefs(
                ByteArrayInputStream("<d:multistatus></d:multistatus>".toByteArray())
            ).isEmpty()
        )
    }

    @Test
    fun `a stalled stream fails loudly instead of busy-spinning`() {
        val stall = object : InputStream() {
            override fun read(): Int = -1
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0 // frozen: never progresses
        }
        try {
            WebDavRemoteListingPolicy.scanBackupHrefs(stall)
            fail("a contract-breaking zero-progress stream must fail loudly")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("no progress"))
        }
    }

    // --- the scanner mirrors the regex findBackupHrefs byte-for-byte ----------

    @Test
    fun `bounded scan equals the regex find for a normal listing`() {
        val xml = listingXml(
            "/dav/Noteflow_Vault/noteflow_vault_backup_2026-08-13_Aa.nfb",
            "/dav/Noteflow_Vault/noteflow_vault_backup_2026-08-15_Bb.nfb",
            "/dav/Noteflow_Vault/noteflow_vault_backup_1755302400000.nfb",
            "/dav/Noteflow_Vault/someotherfile.nfb"
        )
        val expected = WebDavRemoteListingPolicy.findBackupHrefs(xml)
        assertEquals(3, expected.size)

        val scanned = WebDavRemoteListingPolicy.scanBackupHrefs(
            ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))
        )

        assertEquals("scan must agree with the regex find", expected, scanned)
    }

    @Test
    fun `case-insensitive matching mirrors the regex IGNORE_CASE`() {
        val xml = """
            <d:multistatus>
              <d:response><D:HREF>/dav/F/NOTEFLOW_VAULT_BACKUP_2026-08-16_X.nfb</D:HREF></d:response>
              <d:response><D:HREF>/dav/F/noteflow_vault_backup_2026-08-14_y.NFB</D:HREF></d:response>
            </d:multistatus>
        """.trimIndent()
        val expected = WebDavRemoteListingPolicy.findBackupHrefs(xml)
        assertEquals(2, expected.size)

        val scanned = WebDavRemoteListingPolicy.scanBackupHrefs(
            ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))
        )
        assertEquals(expected, scanned)
    }

    @Test
    fun `an href whose content contains a '<' is skipped exactly like the regex`() {
        val xml = """
            <d:multistatus>
              <d:response><d:href>/dav/F/noteflow_vault_backup_2026-08-16_good.nfb</d:href></d:response>
              <d:response><d:href>/dav/F/a<b</d:href></d:response>
              <d:response><d:href>/dav/F/noteflow_vault_backup_2026-08-15_after.nfb</d:href></d:response>
            </d:multistatus>
        """.trimIndent()
        val expected = WebDavRemoteListingPolicy.findBackupHrefs(xml)
        assertEquals(2, expected.size)

        val scanned = WebDavRemoteListingPolicy.scanBackupHrefs(
            ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))
        )
        assertEquals(expected, scanned)
    }

    @Test
    fun `a candidate with nothing before the marker or before the suffix is skipped like the regex`() {
        val xml = """
            <d:multistatus>
              <d:response><d:href>noteflow_vault_backup_2026-08-16_x.nfb</d:href></d:response>
              <d:response><d:href>/dav/F/noteflow_vault_backup_.nfb</d:href></d:response>
              <d:response><d:href>/dav/F/noteflow_vault_backup_2026-08-16_ok.nfb</d:href></d:response>
            </d:multistatus>
        """.trimIndent()
        val expected = WebDavRemoteListingPolicy.findBackupHrefs(xml)
        assertEquals(1, expected.size)

        val scanned = WebDavRemoteListingPolicy.scanBackupHrefs(
            ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))
        )
        assertEquals(expected, scanned)
    }

    // --- incremental: hrefs/tags/multibyte chars split across feed boundaries --

    @Test
    fun `hrefs spanning read boundaries are still found`() {
        val xml = listingXml(
            "/dav/F/noteflow_vault_backup_2026-08-16_Aa.nfb",
            "/dav/F/noteflow_vault_backup_2026-08-15_Bb.nfb"
        )
        val expected = WebDavRemoteListingPolicy.findBackupHrefs(xml)

        // A 3-byte read stream forces every tag and href to straddle feeds.
        val drip = DripInputStream(
            totalBytes = xml.toByteArray(Charsets.UTF_8).size.toLong(),
            chunkBytes = 3
        )
        val scanned = WebDavRemoteListingPolicy.scanBackupHrefs(drip)
        assertEquals(expected, scanned)
    }

    @Test
    fun `multibyte href characters split across read boundaries are reassembled`() {
        val xml = listingXml("/dav/F/noteflow_vault_backup_2026-08-13_tést.nfb")
        val expected = WebDavRemoteListingPolicy.findBackupHrefs(xml)
        assertEquals(1, expected.size)
        assertTrue(expected.single().contains("tést"))

        // 1-byte reads guarantee the é (2 UTF-8 bytes) straddles two feeds; the
        // scanner must not emit a replacement char into the href.
        val drip = DripInputStream(
            totalBytes = xml.toByteArray(Charsets.UTF_8).size.toLong(),
            chunkBytes = 1
        )
        val scanned = WebDavRemoteListingPolicy.scanBackupHrefs(drip)
        assertEquals(expected, scanned)
        assertFalse("no replacement char may leak into the href", scanned.single().contains('\uFFFD'))
    }

    // --- source pins ----------------------------------------------------------

    @Test
    fun `the service reads the PROPFIND body through the bounded scan`() {
        val source = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/WebDavSyncService.kt")
            .readText()
        assertTrue("the listing must route through the bounded scan", source.contains("scanBackupHrefs"))
        assertFalse("the unbounded readText() on the listing must be gone", source.contains("val xmlResponse"))
        assertTrue(
            "the too-large listing must be caught with the fixed message",
            source.contains("WebDavFailurePolicy.LISTING_TOO_LARGE_MESSAGE")
        )
        assertTrue(
            "the typed exception must be caught before the blanket catch",
            source.contains("ListingTooLargeException")
        )
    }

    @Test
    fun `the policy pins the 4 MB cap and the failure text is a fixed non-interpolated constant`() {
        val src = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/WebDavRemoteListingPolicy.kt")
            .readText()
        assertTrue("the listing cap must be 4 MB", src.contains("const val MAX_LISTING_BYTES: Long = 4L * 1024 * 1024"))
        assertTrue("the scan must never read the body into one byte array", src.contains("ByteArray(LISTING_CHUNK_BYTES)"))

        val failSrc = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/WebDavFailurePolicy.kt").readText()
        assertTrue("the fixed message constant must exist", failSrc.contains("LISTING_TOO_LARGE_MESSAGE"))
        assertFalse("a fixed message must never interpolate", failSrc.contains("LISTING_TOO_LARGE_MESSAGE"))
        assertEquals(
            "the UI text must be a fixed, human sentence",
            "The WebDAV server's file listing was too large to process safely; the sync was stopped.",
            WebDavFailurePolicy.LISTING_TOO_LARGE_MESSAGE
        )
        for (msg in listOf(WebDavFailurePolicy.LISTING_TOO_LARGE_MESSAGE)) {
            assertFalse("the fixed text must not interpolate", msg.contains("\${"))
        }
    }

    // --- helpers --------------------------------------------------------------

    private fun listingXml(vararg hrefs: String): String = buildString {
        append("<d:multistatus>")
        for (href in hrefs) {
            append("<d:response><d:href>").append(href).append("</d:href></d:response>")
        }
        append("</d:multistatus>")
    }

    /**
     * Synthetic "chunked" stream: hands out at most [chunkBytes] per read and
     * counts what it yielded, so a test can prove the bounded scan stopped early
     * instead of draining the whole body (which pre-fix `readText()` did).
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
