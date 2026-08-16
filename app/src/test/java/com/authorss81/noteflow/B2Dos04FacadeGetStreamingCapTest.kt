package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.runtime.FacadeResult
import com.authorss81.noteflow.services.AppFacadeHost
import com.authorss81.noteflow.services.FacadeHttpGetPolicy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Arrays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * B2-DOS-04 (phase-80): `AppFacadeHost.httpGet` enforces its response-size cap
 * DURING the read, never after the whole body is already in heap.
 *
 * Pre-fix, the body read was `connection.inputStream.use { stream ->
 * stream.readBytes(); if (bytes.size > MAX) ... }` — `readBytes()` slurps the
 * ENTIRE response into an unbounded [ByteArrayOutputStream] before the cap is
 * ever checked, and the `HttpURLConnection.contentLengthLong` pre-check is -1
 * (skipped) for every chunked/unknown-length response, so a granted plugin
 * pointing at a slow-chunked endpoint could pin hundreds of MB in heap and OOM
 * the process. Redirect chains then had no per-hop budget either (a 10 MB cap
 * interpreted globally, in practice unlimited since the pre-check routinely
 * skipped).
 *
 * The fix routes every body read through the pure-JVM
 * [FacadeHttpGetPolicy.readCapped] — the same bounded streaming loop
 * `WebPageFetcher` uses — which aborts mid-stream on the first chunk that
 * crosses [FacadeHttpGetPolicy.MAX_FACADE_GET_BYTES]. Pure JVM, no network;
 * the connection factory is injected with a fake [HttpURLConnection] whose
 * input stream is a synthetic "chunked drip" that never materializes more than
 * it hands out. Tests prove: an over-cap chunked body is refused WITHOUT
 * draining the stream and without holding more than the budget + one buffer,
 * the header pre-check still short-circuits before the stream is opened, the
 * budget is enforced PER HOP on a redirect chain, boundary payloads (exactly
 * at / just under the cap) survive, and source pins hold the invariant.
 */
class B2Dos04FacadeGetStreamingCapTest {

    // ---- behavior: the core finding -----------------------------------------

    @Test
    fun `slow-chunked over-cap body aborts mid-read without exceeding the heap budget`() {
        // Content-Length is -1 (chunked/unknown) so the pre-check skips; the
        // body dribbles in small chunks, far past the cap.
        val drip = DripInputStream(
            totalBytes = FacadeHttpGetPolicy.MAX_FACADE_GET_BYTES + (200L * 1024L),
            chunkBytes = 1024
        )
        val host = AppFacadeHost(connectionFactory = {
            FakeHttpConnection(200, input = drip)
        })

        val result = host.httpGet("https://plugin.example/data.json")

        assertTrue(result is FacadeResult.Failed)
        assertTrue(
            "message must name the size violation",
            "too large" in (result as FacadeResult.Failed).message
        )
        assertTrue(
            "the read must ABORT mid-stream (never drain the whole body)",
            drip.yielded < drip.totalBytes
        )
        assertTrue(
            "the abort must happen AT the budget boundary: at most one read " +
                "buffer of over-read beyond the cap",
            drip.yielded <= FacadeHttpGetPolicy.MAX_FACADE_GET_BYTES +
                FacadeHttpGetPolicy.READ_BUFFER_BYTES
        )
        assertTrue(
            "a drained (pre-fix readBytes) body would have been read from the " +
                "start — prove bytes actually flowed",
            drip.yielded > FacadeHttpGetPolicy.MAX_FACADE_GET_BYTES
        )
    }

    @Test
    fun `body equal to the cap exactly is acceptable`() {
        val drip = DripInputStream(
            totalBytes = FacadeHttpGetPolicy.MAX_FACADE_GET_BYTES,
            chunkBytes = 64 * 1024
        )
        val host = AppFacadeHost(connectionFactory = {
            FakeHttpConnection(200, input = drip)
        })

        val result = host.httpGet("https://plugin.example/data.bin")

        assertTrue(result is FacadeResult.Granted)
        assertEquals(
            FacadeHttpGetPolicy.MAX_FACADE_GET_BYTES.toInt(),
            (result as FacadeResult.Granted).value.toByteArray(Charsets.UTF_8).size
        )
        assertEquals(drip.totalBytes, drip.yielded)
    }

    @Test
    fun `a small response still round-trips`() {
        val host = AppFacadeHost(connectionFactory = {
            FakeHttpConnection(200, input = ByteArrayInputStream("hello world".toByteArray(Charsets.UTF_8)))
        })

        val result = host.httpGet("https://plugin.example/hello.txt")

        assertTrue(result is FacadeResult.Granted)
        assertEquals("hello world", (result as FacadeResult.Granted).value)
    }

    // ---- behavior: early header pre-check ----------------------------------

    @Test
    fun `an over-cap Content-Length header is refused before the stream is opened`() {
        val neverRead = object : InputStream() {
            override fun read(): Int {
                fail("the body stream must never be opened when Content-Length is already over budget")
                error("unreachable")
            }
        }
        val host = AppFacadeHost(connectionFactory = {
            FakeHttpConnection(
                200,
                contentLengthValue = FacadeHttpGetPolicy.MAX_FACADE_GET_BYTES + 1,
                input = neverRead
            )
        })

        val result = host.httpGet("https://plugin.example/data.json")

        assertTrue(result is FacadeResult.Failed)
        assertTrue("too large" in (result as FacadeResult.Failed).message)
    }

    // ---- behavior: per-hop budget on a redirect chain ----------------------

    @Test
    fun `a redirect chain enforces the budget on every hop`() {
        var calls = 0
        val drip = DripInputStream(
            totalBytes = FacadeHttpGetPolicy.MAX_FACADE_GET_BYTES + (200L * 1024L),
            chunkBytes = 1024
        )
        val host = AppFacadeHost(connectionFactory = { url ->
            calls++
            if (calls == 1) {
                FakeHttpConnection(302, location = "https://plugin.example/real-data.json", input = drip)
            } else {
                FakeHttpConnection(200, input = drip)
            }
        })

        val result = host.httpGet("https://plugin.example/start")

        assertTrue(result is FacadeResult.Failed)
        assertTrue("too large" in (result as FacadeResult.Failed).message)
        assertEquals("the downgrade/hop cap guard still ran", 2, calls)
        assertTrue("the final hop must abort mid-stream", drip.yielded < drip.totalBytes)
        assertTrue(
            "the per-hop budget must bound the abort",
            drip.yielded <= FacadeHttpGetPolicy.MAX_FACADE_GET_BYTES +
                FacadeHttpGetPolicy.READ_BUFFER_BYTES
        )
    }

    // ---- source pins --------------------------------------------------------

    @Test
    fun `AppFacadeHost no longer slurps the body with readBytes`() {
        val source = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/AppFacadeHost.kt")
            .readText()
        assertFalse(
            "the unbounded readBytes() must be gone from the httpGet body path",
            source.contains(".readBytes()")
        )
        assertTrue(
            "every body read must route through the bounded FacadeHttpGetPolicy.readCapped",
            source.contains("FacadeHttpGetPolicy.readCapped")
        )
        assertTrue(
            "the B1-NET-05 manual-redirect posture must be retained",
            source.contains("instanceFollowRedirects = false")
        )
        assertTrue(
            "hops must still route through StrictRedirectPolicy",
            source.contains("StrictRedirectPolicy")
        )
    }

    @Test
    fun `the policy enforces the cap during the streaming loop`() {
        val source = File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/services/FacadeHttpGetPolicy.kt"
        ).readText()
        assertTrue(
            "readCapped must compare the running total against the cap inside the loop",
            source.contains("if (total > MAX_FACADE_GET_BYTES)")
        )
        assertTrue(
            "readCapped must write through a fixed buffer, never readBytes",
            source.contains("ByteArray(READ_BUFFER_BYTES)")
        )
        assertTrue(
            "the cap constant must be the documented 10 MB",
            source.contains("const val MAX_FACADE_GET_BYTES: Long = 10L * 1024 * 1024")
        )
        assertTrue(
            "the abort must surface a typed, catchable exception",
            source.contains("class ResponseTooLargeException(message: String) : IOException(message)")
        )
    }

    // ---- fake connection + synthetic drip stream ----------------------------

    /**
     * Minimal [HttpURLConnection] fake. `contentLengthLong` defaults to -1
     * (chunked/unknown-length response) unless [contentLengthValue] is set —
     * exactly the shape B2-DOS-04 exploits via the skipped pre-check.
     */
    private class FakeHttpConnection(
        private val fakeCode: Int,
        private val location: String? = null,
        private val contentLengthValue: Long = -1,
        private val input: InputStream
    ) : HttpURLConnection(URL("https://fake.invalid/")) {

        override fun disconnect() {}
        override fun usingProxy(): Boolean = false
        override fun connect() {}
        override fun getInputStream(): InputStream = input
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getResponseCode(): Int = fakeCode
        override fun getContentLengthLong(): Long = contentLengthValue

        override fun getHeaderField(name: String?): String? =
            if (name != null && name.equals("Location", ignoreCase = true)) location else null
    }

    /**
     * Synthetic "chunked" stream: hands out at most [chunkBytes] per read and
     * counts what it yielded, so a test can prove the reader stopped early
     * instead of draining everything (which is what the pre-fix `readBytes()`
     * did before checking the cap).
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