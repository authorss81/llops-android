package com.authorss81.noteflow.llm

import com.authorss81.noteflow.llm.engine.AssistantModelDownloadRunner
import com.authorss81.noteflow.llm.policy.AssistantStoragePolicy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2-DEPS-05 (phase-77): pure-JVM end-to-end tests of the pinned model
 * downloader's byte flow. A scripted [HttpURLConnection] fake answers 3xx /
 * 2xx / plain bytes so the FULL redirect-validation + size + SHA-256 pipeline
 * is exercised without a network and without Android:
 *   1. a 2xx body whose bytes do not match the published SHA-256 is rejected
 *      and the temp file deleted;
 *   2. a 2xx body of the wrong size is rejected and the temp file deleted;
 *   3. an off-family or http-downgrading redirect is refused BEFORE the next
 *      connection opens;
 *   4. a same-family CDN redirect (the real HuggingFace behavior) is followed
 *      and verifies;
 *   5. a non-2xx response is rejected; too many hops are rejected;
 *   6. the entry URL host pin is enforced before any connection.
 */
class AssistantModelDownloadTest {

    private val fixture = "B2-DEPS-05 pinned model fixture".toByteArray(Charsets.UTF_8)
    private val fixtureSize = fixture.size.toLong()
    private val fixtureSha = sha256Hex(fixture)

    private val entryUrl = AssistantStoragePolicy.DEFAULT_MODEL_URL

    // ---- success / verification --------------------------------------------

    @Test
    fun `matching body downloads and verifies, temp preserved for rename`() {
        val tmp = File(tmpDir, "a.gguf.part")
        val opened = mutableListOf<String>()
        val runner = runner(fixture = fixture, code = 200, opened = opened, expectedSha = fixtureSha)
        runBlocking {
            val outcome = runner.downloadTo(entryUrl, tmp) {}
            assertTrue("expected Success but got $outcome", outcome is AssistantModelDownloadRunner.Outcome.Success)
            assertEquals(fixtureSize, (outcome as AssistantModelDownloadRunner.Outcome.Success).bytes)
            assertTrue("temp must retain the verified bytes for the caller's rename", tmp.exists())
            assertEquals(fixture.size.toLong(), tmp.length())
            assertTrue(tmp.readBytes().contentEquals(fixture))
        }
        assertEquals(listOf(entryUrl), opened)
    }

    @Test
    fun `bytes not matching the published sha256 are rejected and deleted`() {
        val tmp = File(tmpDir, "b.gguf.part")
        val tampered = fixture.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val runner = runner(fixture = tampered, code = 200)
        runBlocking {
            val outcome = runner.downloadTo(entryUrl, tmp) {}
            assertTrue("tampered download must fail", outcome is AssistantModelDownloadRunner.Outcome.Failure)
            assertTrue((outcome as AssistantModelDownloadRunner.Outcome.Failure).message.contains("SHA-256"))
            assertFalse("temp file must be deleted on hash mismatch", tmp.exists())
        }
    }

    @Test
    fun `a body of the wrong size is rejected and deleted`() {
        val tmp = File(tmpDir, "c.gguf.part")
        val runner = runner(fixture = "small".toByteArray(), code = 200)
        runBlocking {
            val outcome = runner.downloadTo(entryUrl, tmp) {}
            assertTrue("short download must fail", outcome is AssistantModelDownloadRunner.Outcome.Failure)
            assertTrue(
                (outcome as AssistantModelDownloadRunner.Outcome.Failure).message.contains("size")
            )
            assertFalse("temp file must be deleted on size mismatch", tmp.exists())
        }
    }

    // ---- redirects ----------------------------------------------------------

    @Test
    fun `huggingface cdn redirect is followed and the pinned bytes verify`() {
        val tmp = File(tmpDir, "d.gguf.part")
        val cdn = "https://us.aws.cdn.hf.co/xet-bridge-us/some-id?token=abc"
        val opened = mutableListOf<String>()
        val runner = runner(
            responses = listOf(
                Response(302, location = cdn),
                Response(200, body = fixture)
            ),
            opened = opened,
            expectedSha = fixtureSha
        )
        runBlocking {
            val outcome = runner.downloadTo(entryUrl, tmp) {}
            assertTrue("cdn redirect flow must succeed, got $outcome", outcome is AssistantModelDownloadRunner.Outcome.Success)
            assertEquals(fixtureSize, (outcome as AssistantModelDownloadRunner.Outcome.Success).bytes)
            assertTrue(tmp.readBytes().contentEquals(fixture))
        }
        assertEquals(listOf(entryUrl, cdn), opened)
    }

    @Test
    fun `off family redirect is refused before the next connection opens`() {
        val tmp = File(tmpDir, "e.gguf.part")
        val opened = mutableListOf<String>()
        val runner = runner(
            responses = listOf(
                Response(302, location = "https://attacker.example/evil.gguf"),
                Response(200, body = fixture)
            ),
            opened = opened,
            expectedSha = fixtureSha
        )
        runBlocking {
            val outcome = runner.downloadTo(entryUrl, tmp) {}
            assertTrue("off-family redirect must fail", outcome is AssistantModelDownloadRunner.Outcome.Failure)
            assertTrue((outcome as AssistantModelDownloadRunner.Outcome.Failure).message.contains("HuggingFace"))
            assertFalse("temp file must be deleted", tmp.exists())
        }
        assertEquals("the attacker URL must never be opened", listOf(entryUrl), opened)
    }

    @Test
    fun `an http downgrade redirect is refused before the next connection opens`() {
        val tmp = File(tmpDir, "f.gguf.part")
        val opened = mutableListOf<String>()
        val runner = runner(
            responses = listOf(
                Response(302, location = "http://cdn.hf.co/x.gguf"),
                Response(200, body = fixture)
            ),
            opened = opened,
            expectedSha = fixtureSha
        )
        runBlocking {
            val outcome = runner.downloadTo(entryUrl, tmp) {}
            assertTrue("http downgrade must fail", outcome is AssistantModelDownloadRunner.Outcome.Failure)
            assertFalse("temp file must be deleted", tmp.exists())
        }
        assertEquals("the downgraded URL must never be opened", listOf(entryUrl), opened)
    }

    @Test
    fun `a redirect back to the same url loops and is refused`() {
        val tmp = File(tmpDir, "g.gguf.part")
        val runner = runner(
            responses = listOf(
                Response(302, location = entryUrl),
                Response(200, body = fixture)
            ),
            expectedSha = fixtureSha
        )
        runBlocking {
            val outcome = runner.downloadTo(entryUrl, tmp) {}
            assertTrue("loop redirect must fail", outcome is AssistantModelDownloadRunner.Outcome.Failure)
            assertFalse("temp file must be deleted", tmp.exists())
        }
    }

    @Test
    fun `too many redirect hops are refused`() {
        val tmp = File(tmpDir, "h.gguf.part")
        val responses = List(9) { i -> Response(302, location = "https://us.aws.cdn.hf.co/hop$i") }
        val runner = runner(responses = responses, expectedSha = fixtureSha)
        runBlocking {
            val outcome = runner.downloadTo(entryUrl, tmp) {}
            assertTrue("too-many-hops must fail", outcome is AssistantModelDownloadRunner.Outcome.Failure)
            assertTrue((outcome as AssistantModelDownloadRunner.Outcome.Failure).message.contains("redirect"))
            assertFalse("temp file must be deleted", tmp.exists())
        }
    }

    // ---- entry / other ------------------------------------------------------

    @Test
    fun `a non https entry url is refused before any connection`() {
        val tmp = File(tmpDir, "i.gguf.part")
        val opened = mutableListOf<String>()
        val runner = runner(responses = listOf(Response(200, body = fixture)), opened = opened, expectedSha = fixtureSha)
        runBlocking {
            val outcome = runner.downloadTo("http://huggingface.co/x.gguf", tmp) {}
            assertTrue("http entry must fail", outcome is AssistantModelDownloadRunner.Outcome.Failure)
        }
        assertTrue("no connection may be opened", opened.isEmpty())
    }

    @Test
    fun `a non huggingface entry url is refused before any connection`() {
        val tmp = File(tmpDir, "j.gguf.part")
        val opened = mutableListOf<String>()
        val runner = runner(responses = listOf(Response(200, body = fixture)), opened = opened, expectedSha = fixtureSha)
        runBlocking {
            val outcome = runner.downloadTo("https://attacker.example/x.gguf", tmp) {}
            assertTrue("off-family entry must fail", outcome is AssistantModelDownloadRunner.Outcome.Failure)
        }
        assertTrue("no connection may be opened", opened.isEmpty())
    }

    @Test
    fun `a non 2xx response is rejected and deleted`() {
        val tmp = File(tmpDir, "k.gguf.part")
        val runner = runner(code = 404, fixture = ByteArray(0))
        runBlocking {
            val outcome = runner.downloadTo(entryUrl, tmp) {}
            assertTrue("404 must fail", outcome is AssistantModelDownloadRunner.Outcome.Failure)
            assertTrue((outcome as AssistantModelDownloadRunner.Outcome.Failure).message.contains("404"))
            assertFalse("temp file must be deleted", tmp.exists())
        }
    }

    @Test
    fun `the published pin constants are internally consistent`() {
        // The pin itself must be well-formed so the default runner can use it.
        assertTrue(AssistantStoragePolicy.DEFAULT_MODEL_SHA256.length == 64)
        assertTrue(
            AssistantStoragePolicy.DEFAULT_MODEL_SHA256.all { it in "0123456789abcdef" }
        )
        assertTrue(AssistantStoragePolicy.DEFAULT_MODEL_SIZE_BYTES > 0L)
        assertTrue("stale 398 MiB approximation must be gone", AssistantStoragePolicy.DEFAULT_MODEL_SIZE_BYTES != 398L * 1024 * 1024)
    }

    // ---- helpers ------------------------------------------------------------

    private data class Response(
        val code: Int,
        val location: String? = null,
        val body: ByteArray? = null
    )

    private fun runner(
        code: Int = 200,
        fixture: ByteArray,
        opened: MutableList<String>? = null,
        expectedSha: String = fixtureSha
    ): AssistantModelDownloadRunner = runner(
        responses = listOf(Response(code, body = fixture)),
        opened = opened,
        expectedSha = expectedSha
    )

    private fun runner(
        responses: List<Response>,
        opened: MutableList<String>? = null,
        expectedSha: String = fixtureSha
    ): AssistantModelDownloadRunner {
        var counter = 0
        return AssistantModelDownloadRunner(
            expectedSizeBytes = fixtureSize,
            expectedSha256 = expectedSha,
            connectionFactory = { url ->
                opened?.add(url)
                val response = responses.getOrNull(counter) ?: responses.last()
                counter++
                FakeConnection(response.code, response.location, response.body ?: ByteArray(0))
            }
        )
    }

    private val tmpDir: File = File(System.getProperty("java.io.tmpdir"), "phase-77-${System.nanoTime()}")
        .apply { mkdirs() }

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** Minimal [HttpURLConnection] fake: scripted code/Location/body. */
    private class FakeConnection(
        private val fakeCode: Int,
        private val fakeLocation: String?,
        private val body: ByteArray
    ) : HttpURLConnection(URL("https://fake.invalid/")) {

        override fun disconnect() {}

        override fun usingProxy(): Boolean = false

        override fun connect() {}

        override fun getInputStream(): InputStream = ByteArrayInputStream(body)

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getResponseCode(): Int = fakeCode

        override fun getHeaderField(name: String?): String? =
            if (name != null && name.equals("Location", ignoreCase = true)) fakeLocation else null
    }
}