package com.authorss81.noteflow

import com.authorss81.noteflow.services.localsend.LocalSendBodyReadPolicy
import java.io.File
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2-B1N-01 (phase-142): LocalSend peer response bodies are read CAPPED
 * mid-stream — never slurped-whole-then-truncated.
 *
 * Pre-fix `LocalSendSender` did `bufferedReader().use { it.readText() }.take(2048)`
 * (register probe), `.take(8192)` (`/prepare-upload` success body) and
 * `.take(512)` (error stream) — an endless LAN-peer body was pinned in heap
 * for the whole read timeout before any truncation ran. Now all three sites
 * read through the shared pure-JVM [LocalSendBodyReadPolicy.readText] bounded
 * loop. Behavior + source pins.
 */
class LocalSendBodyReadPolicyTest {

    // ---- behavior: the capped mid-stream reader -----------------------------

    @Test
    fun `a body exactly at the register-probe cap is read fully`() {
        val body = "a".repeat(LocalSendBodyReadPolicy.REGISTER_BODY_LIMIT)
        assertEquals(body, LocalSendBodyReadPolicy.readText(StringReader(body), LocalSendBodyReadPolicy.REGISTER_BODY_LIMIT))
    }

    @Test
    fun `a body over the register-probe cap aborts mid-read - fail closed`() {
        val over = LocalSendBodyReadPolicy.REGISTER_BODY_LIMIT + 1
        val ex = assertThrows(LocalSendBodyReadPolicy.ResponseTooLargeException::class.java) {
            LocalSendBodyReadPolicy.readText(StringReader("b".repeat(over)), LocalSendBodyReadPolicy.REGISTER_BODY_LIMIT)
        }
        assertTrue("message must name the cap", "cap" in ex.message.orEmpty())
    }

    @Test
    fun `a body exactly at the prepare-upload success cap is read fully`() {
        val body = "c".repeat(LocalSendBodyReadPolicy.SUCCESS_BODY_LIMIT)
        assertEquals(body, LocalSendBodyReadPolicy.readText(StringReader(body), LocalSendBodyReadPolicy.SUCCESS_BODY_LIMIT))
    }

    @Test
    fun `a body over the prepare-upload cap aborts mid-read`() {
        assertThrows(LocalSendBodyReadPolicy.ResponseTooLargeException::class.java) {
            LocalSendBodyReadPolicy.readText(
                StringReader("d".repeat(LocalSendBodyReadPolicy.SUCCESS_BODY_LIMIT + 2049)),
                LocalSendBodyReadPolicy.SUCCESS_BODY_LIMIT
            )
        }
    }

    @Test
    fun `a body over the error-stream cap aborts mid-read`() {
        assertThrows(LocalSendBodyReadPolicy.ResponseTooLargeException::class.java) {
            LocalSendBodyReadPolicy.readText(
                StringReader("e".repeat(LocalSendBodyReadPolicy.ERROR_BODY_LIMIT + 1)),
                LocalSendBodyReadPolicy.ERROR_BODY_LIMIT
            )
        }
    }

    @Test
    fun `an empty body yields an empty string`() {
        assertEquals("", LocalSendBodyReadPolicy.readText(StringReader(""), LocalSendBodyReadPolicy.SUCCESS_BODY_LIMIT))
    }

    @Test
    fun `a small body still round-trips`() {
        val body = "{\"sessionId\": \"abc\", \"files\": {\"id\": \"tok\"}}"
        assertEquals(body, LocalSendBodyReadPolicy.readText(StringReader(body), LocalSendBodyReadPolicy.SUCCESS_BODY_LIMIT))
    }

    @Test
    fun `a zero limit refuses any non-empty body but reads an empty one`() {
        assertEquals("", LocalSendBodyReadPolicy.readText(StringReader(""), 0))
        assertThrows(LocalSendBodyReadPolicy.ResponseTooLargeException::class.java) {
            LocalSendBodyReadPolicy.readText(StringReader("x"), 0)
        }
    }

    // ---- source pins: LocalSendSender has no slurp-then-truncate left --------

    @Test
    fun `LocalSendSender no longer has any unbounded readText slurp`() {
        val source = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/localsend/LocalSendSender.kt").readText()
        // The pre-fix shape `...readText().take(...)` (empty arg list = unbounded)
        // must be gone entirely. The new bounded loop is `Policy.readText(it, limit)`.
        assertFalse("no .readText() slurp may remain in LocalSendSender", Regex("\\.readText\\s*\\(\\)").containsMatchIn(source))
        // Only the slurp-then-truncate shape `readText(...).take(n)` is banned —
        // NOT every `.take(n)` call (LocalSendSender legitimately chunks lists
        // with `.take(3)`/`.take(40)` for discovery batching).
        assertFalse(
            "no .readText() slurp then .take() truncation may remain",
            Regex("\\.readText\\s*\\(\\s*\\)\\s*\\.take\\s*\\(\\s*\\d+\\s*\\)").containsMatchIn(source)
        )
    }

    @Test
    fun `the three LocalSend read sites are each capped mid-stream`() {
        val source = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/localsend/LocalSendSender.kt").readText()
        // Register probe (2048), prepare-upload success body (8192) and the
        // error stream (512) each read through the shared bounded policy with
        // their dedicated cap constant — one usage per site.
        assertEquals(
            "register probe must cap at REGISTER_BODY_LIMIT",
            1,
            Regex("LocalSendBodyReadPolicy\\.readText\\s*\\(\\s*it\\s*,\\s*LocalSendBodyReadPolicy\\.REGISTER_BODY_LIMIT\\s*\\)").findAll(source).count()
        )
        assertEquals(
            "success body must cap at SUCCESS_BODY_LIMIT",
            1,
            Regex("LocalSendBodyReadPolicy\\.readText\\s*\\(\\s*it\\s*,\\s*LocalSendBodyReadPolicy\\.SUCCESS_BODY_LIMIT\\s*\\)").findAll(source).count()
        )
        assertEquals(
            "error stream must cap at ERROR_BODY_LIMIT",
            1,
            Regex("LocalSendBodyReadPolicy\\.readText\\s*\\(\\s*it\\s*,\\s*LocalSendBodyReadPolicy\\.ERROR_BODY_LIMIT\\s*\\)").findAll(source).count()
        )
    }

    @Test
    fun `the bounded read policy is pure JVM and the caps mirror the pre-fix windows`() {
        val policySource = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/localsend/LocalSendBodyReadPolicy.kt").readText()
        // Pure JVM: the only imports allowed are java.*.
        assertTrue("the policy must be pure JVM", "import java.io.Reader" in policySource)
        assertFalse("no Android import may reach the policy", policySource.contains("import android."))
        assertEquals(2048, LocalSendBodyReadPolicy.REGISTER_BODY_LIMIT)
        assertEquals(8192, LocalSendBodyReadPolicy.SUCCESS_BODY_LIMIT)
        assertEquals(512, LocalSendBodyReadPolicy.ERROR_BODY_LIMIT)
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