package com.authorss81.noteflow.llm

import com.authorss81.noteflow.llm.policy.AssistantStoragePolicy
import com.authorss81.noteflow.llm.policy.ModelDownloadPolicy
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * B2-DEPS-05 (phase-77): pure-JVM decision-table tests for the model download
 * trust policy — host allow-list, https/credentials hop validation, manual
 * redirect resolution and the size + SHA-256 verification against the
 * published pin. No network, no Android.
 */
class ModelDownloadPolicyTest {

    // ---- host allow-list ---------------------------------------------------

    @Test
    fun `entry host huggingface dot co is allowed`() {
        assertTrue(ModelDownloadPolicy.isAllowedDownloadHost("huggingface.co"))
        assertTrue(ModelDownloadPolicy.isAllowedDownloadHost("HuggingFace.Co"))
    }

    @Test
    fun `huggingface cdn family hosts are allowed as hops`() {
        for (host in listOf(
            "cdn-lfs.huggingface.co",
            "us.aws.cdn.hf.co",
            "eu.cdn.hf.co",
            "cas-bridge.xethub.hf.co",
            "cas-server.xethub.hf.co"
        )) {
            assertTrue("$host must be an allowed download host", ModelDownloadPolicy.isAllowedDownloadHost(host))
        }
    }

    @Test
    fun `off family hosts are refused`() {
        for (host in listOf(
            "attacker.example",
            "localhost",
            "127.0.0.1",
            "169.254.169.254",
            "192.168.0.1",
            "cdn.hf.co.evil.example",
            "hf.co"
        )) {
            assertFalse("$host must be refused", ModelDownloadPolicy.isAllowedDownloadHost(host))
        }
    }

    // ---- entry URL validation ----------------------------------------------

    @Test
    fun `valid entry url is accepted`() {
        assertEquals(
            ModelDownloadPolicy.HopVerdict.Ok,
            ModelDownloadPolicy.validateEntry(AssistantStoragePolicy.DEFAULT_MODEL_URL)
        )
    }

    @Test
    fun `entry url must be https`() {
        assertTrue(
            ModelDownloadPolicy.validateEntry("http://huggingface.co/x/y.gguf")
                is ModelDownloadPolicy.HopVerdict.Refused
        )
    }

    @Test
    fun `entry url must live on huggingface dot co`() {
        for (url in listOf(
            "https://attacker.example/x.gguf",
            "https://cdn.hf.co/x.gguf",
            "https://huggingface.co.evil.example/x.gguf"
        )) {
            assertTrue(
                "$url must be refused as an entry",
                ModelDownloadPolicy.validateEntry(url) is ModelDownloadPolicy.HopVerdict.Refused
            )
        }
    }

    @Test
    fun `entry url with embedded credentials is refused`() {
        assertTrue(
            ModelDownloadPolicy.validateEntry("https://user:pass@huggingface.co/x.gguf")
                is ModelDownloadPolicy.HopVerdict.Refused
        )
    }

    @Test
    fun `malformed entry url is refused`() {
        for (url in listOf("not a url", "https://", "")) {
            assertTrue(
                "$url must be refused",
                ModelDownloadPolicy.validateEntry(url) is ModelDownloadPolicy.HopVerdict.Refused
            )
        }
    }

    // ---- redirect hop validation -------------------------------------------

    @Test
    fun `relative redirect resolves against the current hop`() {
        val cur = URI(AssistantStoragePolicy.DEFAULT_MODEL_URL)
        val next = ModelDownloadPolicy.resolveNextHop(cur, "/Qwen/Qwen2-0.5B-Instruct-GGUF/resolve/main/f.gguf")
        assertTrue(next!!.host == "huggingface.co")
        assertTrue(next.toString().endsWith("f.gguf"))
    }

    @Test
    fun `same family cdn redirect is followed`() {
        val cur = URI("https://huggingface.co/a")
        for (location in listOf(
            "https://us.aws.cdn.hf.co/x/b.gguf",
            "https://cdn-lfs.huggingface.co/x/c.gguf",
            "//cdn-lfs.huggingface.co/x/d.gguf"
        )) {
            val next = ModelDownloadPolicy.resolveNextHop(cur, location)
            val expected = if (location.startsWith("//")) "https:$location" else location
            assertEquals(expected, next.toString())
        }
    }

    @Test
    fun `blank location returns null`() {
        assertNull(ModelDownloadPolicy.resolveNextHop(URI("https://huggingface.co/a"), null))
        assertNull(ModelDownloadPolicy.resolveNextHop(URI("https://huggingface.co/a"), "  "))
    }

    @Test
    fun `downgrade to http is refused`() {
        val cur = URI("https://huggingface.co/a")
        for (location in listOf("http://cdn.hf.co/b", "http://attacker.example/c")) {
            try {
                ModelDownloadPolicy.resolveNextHop(cur, location)
                fail("http downgrade must be refused: $location")
            } catch (e: ModelDownloadPolicy.HopRefusedException) {
                assertTrue(e.message!!.contains("HTTPS"))
            }
        }
    }

    @Test
    fun `redirect to an off family host is refused`() {
        val cur = URI("https://huggingface.co/a")
        for (location in listOf(
            "https://attacker.example/x",
            "https://127.0.0.1/x",
            "https://localhost/x",
            "https://169.254.169.254/latest/meta-data/"
        )) {
            try {
                ModelDownloadPolicy.resolveNextHop(cur, location)
                fail("off-family redirect must be refused: $location")
            } catch (e: ModelDownloadPolicy.HopRefusedException) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
    }

    @Test
    fun `redirect to a url with credentials is refused`() {
        try {
            ModelDownloadPolicy.resolveNextHop(
                URI("https://huggingface.co/a"),
                "https://user:pass@cdn.hf.co/x"
            )
            fail("credentials-bearing redirect must be refused")
        } catch (e: ModelDownloadPolicy.HopRefusedException) {
            assertTrue(e.message!!.contains("credentials"))
        }
    }

    @Test
    fun `redirect loop is refused`() {
        try {
            ModelDownloadPolicy.resolveNextHop(
                URI("https://huggingface.co/a?q=1"),
                "https://huggingface.co/a?q=1"
            )
            fail("redirect loop must be refused")
        } catch (e: ModelDownloadPolicy.HopRefusedException) {
            assertTrue(e.message!!.contains("loop"))
        }
    }

    @Test
    fun `malformed redirect target is refused`() {
        try {
            ModelDownloadPolicy.resolveNextHop(URI("https://huggingface.co/a"), "http://///")
            fail("malformed redirect must be refused")
        } catch (e: ModelDownloadPolicy.HopRefusedException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    // ---- download verification ---------------------------------------------

    private val fixture = "hello pinned model bytes".toByteArray(Charsets.UTF_8)
    private val fixtureSize = fixture.size.toLong()
    private val fixtureSha = sha256Hex(fixture)

    @Test
    fun `exact size and matching hash pass`() {
        assertEquals(
            ModelDownloadPolicy.DownloadVerdict.Match,
            ModelDownloadPolicy.verifyDownload(fixtureSize, fixtureSha, fixtureSize, fixtureSha)
        )
        // Defaults are the real published pin.
        assertEquals(
            ModelDownloadPolicy.DownloadVerdict.Match,
            ModelDownloadPolicy.verifyDownload(
                AssistantStoragePolicy.DEFAULT_MODEL_SIZE_BYTES,
                AssistantStoragePolicy.DEFAULT_MODEL_SHA256
            )
        )
    }

    @Test
    fun `size mismatch is rejected for both smaller and larger files`() {
        for (actualBytes in listOf(fixtureSize - 1, fixtureSize + 1, 0L)) {
            val verdict = ModelDownloadPolicy.verifyDownload(actualBytes, fixtureSha, fixtureSize, fixtureSha)
            assertTrue(
                "size $actualBytes must be a SizeMismatch",
                verdict is ModelDownloadPolicy.DownloadVerdict.SizeMismatch
            )
            val mismatch = verdict as ModelDownloadPolicy.DownloadVerdict.SizeMismatch
            assertEquals(fixtureSize, mismatch.expectedBytes)
            assertEquals(actualBytes, mismatch.actualBytes)
        }
    }

    @Test
    fun `matching size but wrong hash is rejected`() {
        val wrong = sha256Hex("different bytes".toByteArray())
        val verdict = ModelDownloadPolicy.verifyDownload(fixtureSize, wrong, fixtureSize, fixtureSha)
        assertTrue(verdict is ModelDownloadPolicy.DownloadVerdict.HashMismatch)
    }

    @Test
    fun `hash comparison is case-insensitive and full length`() {
        assertTrue(ModelDownloadPolicy.hexEqual(fixtureSha, fixtureSha.uppercase()))
        assertTrue(ModelDownloadPolicy.hexEqual(fixtureSha, fixtureSha))
        assertFalse(ModelDownloadPolicy.hexEqual(fixtureSha, fixtureSha.dropLast(1) + "0"))
        assertFalse("hash compares FULL byte arrays — differing lengths never match",
            ModelDownloadPolicy.hexEqual(fixtureSha, fixtureSha.drop(32)))
    }

    @Test
    fun `sha256 hex shape is validated`() {
        assertTrue(ModelDownloadPolicy.isValidSha256Hex(AssistantStoragePolicy.DEFAULT_MODEL_SHA256))
        assertTrue(ModelDownloadPolicy.isValidSha256Hex("a".repeat(64)))
        assertFalse(ModelDownloadPolicy.isValidSha256Hex("a".repeat(63)))
        assertFalse(ModelDownloadPolicy.isValidSha256Hex("a".repeat(65)))
        assertFalse(ModelDownloadPolicy.isValidSha256Hex("z".repeat(64)))
        assertFalse(ModelDownloadPolicy.isValidSha256Hex(""))
    }

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}