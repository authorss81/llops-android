package com.authorss81.noteflow

import com.authorss81.noteflow.services.WebDavFailurePolicy
import com.authorss81.noteflow.services.WebDavSyncService
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * B2-LOG-05 (phase-94): WebDAV failure paths must never echo raw exception text
 * or raw URL-derived text (incl. any pasted `user:pass@` userinfo or
 * server-controlled PROPFIND href paths) into the user-facing sync-status UI.
 *
 * Pre-fix:
 *  - `WebDavSyncService.validateServerUrl` wrapped the `MalformedURLException`
 *    dump of the user's RAW input into `Invalid WebDAV server URL: ${e.message}`
 *    and returned the raw string (userinfo intact) as the "normalized" URL;
 *  - the connect/upload/download blanket catches rendered
 *    `…Failed: ${e.localizedMessage ?: e.message}` verbatim in
 *    `WebDavSyncDialog`'s status surface.
 *
 * These tests exercise the new pure-JVM decision table [WebDavFailurePolicy]
 * (fixed-category messages + userinfo stripping + `host/...` scrubbing +
 * fixed refusal tokens) and source-pin the wiring into `WebDavSyncService.kt`
 * and `WebDavSyncDialog.kt`.
 */
class B2Log05WebDavFailureTextTest {

    // --- validateServerUrl: userinfo stripped + malformed-input echo closed ---

    @Test
    fun `validateServerUrl strips a pasted userinfo off the normalized URL`() {
        assertEquals(
            "https://cloud.example.com/remote.php/dav",
            WebDavSyncService.validateServerUrl("https://dummy:S3CrEt@cloud.example.com/remote.php/dav/")
        )
        assertEquals(
            "https://cloud.example.com/dav",
            WebDavSyncService.validateServerUrl("https://alice@cloud.example.com/dav/")
        )
        // Credentials embedded in an http-opt-in URL are stripped too — the
        // username/password fields are the ONLY credential channel.
        assertEquals(
            "http://192.168.1.50:8090",
            WebDavSyncService.validateServerUrl("http://bob:hunter2@192.168.1.50:8090/", allowInsecureHttp = true)
        )
    }

    @Test
    fun `validateServerUrl keeps userinfo-free URLs byte-identical`() {
        assertEquals(
            "https://cloud.example.com/remote.php/dav",
            WebDavSyncService.validateServerUrl("https://cloud.example.com/remote.php/dav/")
        )
        assertEquals("https://cloud.example.com", WebDavSyncService.validateServerUrl("  https://cloud.example.com  "))
    }

    @Test
    fun `a malformed URL embedding credentials throws the FIXED message with no echo`() {
        val e = try {
            // The genuinely-malformed input still carries the user's secret — the
            // JVM's MalformedURLException message would dump it verbatim; the
            // service must not forward that text.
            WebDavSyncService.validateServerUrl("://bob:S3CrEt@cloud.example.com/dav")
            fail("expected a malformed URL to be rejected")
            null
        } catch (e: IllegalArgumentException) {
            e
        }
        assertEquals(WebDavFailurePolicy.INVALID_URL_MESSAGE, e?.message)
        for (secret in listOf("S3CrEt", "bob", "@cloud")) {
            assertFalse("the thrown message must not echo the raw input: ${e?.message}", e?.message.orEmpty().contains(secret))
        }
    }

    // --- policy: fixed category messages (never the exception text) ------------

    @Test
    fun `the user-facing failure constants never interpolate exception text`() {
        val messages = listOf(
            WebDavFailurePolicy.CONNECT_FAILURE_MESSAGE,
            WebDavFailurePolicy.UPLOAD_FAILURE_MESSAGE,
            WebDavFailurePolicy.DOWNLOAD_FAILURE_MESSAGE,
            WebDavFailurePolicy.TOO_LARGE_DOWNLOAD_MESSAGE,
            WebDavFailurePolicy.INVALID_URL_MESSAGE
        )
        for (msg in messages) {
            assertFalse("a fixed message must never interpolate: $msg", msg.contains("\${"))
        }
        // The old sink `Connection failed: ${e.localizedMessage ?: e.message}`
        // has no counterpart: the connect category maps to ONE fixed string.
        assertEquals(
            "Could not connect to your WebDAV server. Check the server address, your " +
                "credentials and your internet connection, then try again.",
            WebDavFailurePolicy.CONNECT_FAILURE_MESSAGE
        )
    }

    @Test
    fun `a connect failure carrying credentials in its text yields a status free of them`() {
        // Simulates the pre-fix "Connection failed: ${localizedMessage}" sink with
        // a MalformedURLException-style message embedding user:pass@. The category
        // maps to the FIXED constant — and even a URL-bearing message that had to
        // be displayed is scrubbed of the secret by the UI's defense-in-depth.
        val leaky = IllegalArgumentException(
            "MalformedURLException: no protocol: https://bob:S3CrEt@cloud.example.com/remote.php/dav"
        )
        val connectText = WebDavFailurePolicy.CONNECT_FAILURE_MESSAGE
        for (secret in listOf("S3CrEt", "bob", "remote.php", "cloud.example.com")) {
            assertFalse("connect text must not carry failure details: $connectText", connectText.contains(secret))
        }
        val scrubbed = WebDavFailurePolicy.scrubForDisplay("Connection failed: ${leaky.message}")
        for (secret in listOf("S3CrEt", "bob:", "remote.php")) {
            assertFalse("scrubbed status must be free of the credentials: $scrubbed", scrubbed.contains(secret))
        }
    }

    // --- policy: scrubForDisplay ----------------------------------------------

    @Test
    fun `scrubForDisplay strips userinfo and collapses host path to host`() {
        assertEquals(
            "cloud.example.com/...",
            WebDavFailurePolicy.scrubForDisplay("https://cloud.example.com/remote.php/dav")
        )
        assertEquals(
            "cloud.example.com/...",
            WebDavFailurePolicy.scrubForDisplay("https://user:S3CrEt@cloud.example.com/remote.php/dav")
        )
        assertEquals(
            "host/...",
            WebDavFailurePolicy.scrubForDisplay("https://alice@host/x/y")
        )
        // Bare authority (no path) keeps its host:port — the host is the user's
        // own configuration, never a secret.
        assertEquals(
            "cloud.example.com:8080",
            WebDavFailurePolicy.scrubForDisplay("http://cloud.example.com:8080")
        )
    }

    @Test
    fun `scrubForDisplay leaves non-URL text untouched`() {
        assertEquals("plain words stay plain", WebDavFailurePolicy.scrubForDisplay("plain words stay plain"))
        assertEquals(
            "an email@example.com and a host:port stay",
            WebDavFailurePolicy.scrubForDisplay("an email@example.com and a host:port stay")
        )
        assertEquals("", WebDavFailurePolicy.scrubForDisplay(""))
    }

    @Test
    fun `stripUrlUserInfo removes every userinfo segment`() {
        assertEquals(
            "https://cloud.example.com/remote.php/dav",
            WebDavFailurePolicy.stripUrlUserInfo("https://user:S3CrEt@cloud.example.com/remote.php/dav")
        )
        assertEquals(
            "https://host:443/x",
            WebDavFailurePolicy.stripUrlUserInfo("https://a@host:443/x")
        )
        // Nothing to strip: the input is returned unchanged.
        assertEquals(
            "https://cloud.example.com/dav",
            WebDavFailurePolicy.stripUrlUserInfo("https://cloud.example.com/dav")
        )
    }

    // --- policy: refusalReason (fixed tokens, never the href/URL) --------------

    @Test
    fun `refusalReason classifies defusals into fixed tokens without echoing href or url`() {
        val offOrigin = IllegalArgumentException(
            "Refusing WebDAV href that resolves outside the configured server " +
                "(https://user:S3CrEt@cloud.example.com:443): /dav/Noteflow_Vault/noteflow_vault_backup_2026-08-16_Ax.nfb"
        )
        val r1 = WebDavFailurePolicy.refusalReason(offOrigin)
        assertTrue("off-origin defusal keeps its cause: $r1", r1.contains("outside your configured WebDAV server"))
        for (secret in listOf("S3CrEt", "user:", "cloud.example.com", "Noteflow_Vault", "2026-08-16", "noteflow_vault_backup_")) {
            assertFalse("no href/URL text may survive a refusal: $r1", r1.contains(secret))
        }

        val networkPath = IllegalArgumentException(
            "Refusing network-path reference `//evil.example/steal?x=1` — it would resolve to a host that is not the configured WebDAV server."
        )
        val r2 = WebDavFailurePolicy.refusalReason(networkPath)
        assertTrue("network-path defusal keeps its cause: $r2", r2.contains("network-path"))
        for (secret in listOf("evil.example", "steal", "x=1")) {
            assertFalse("no network-path text may survive: $r2", r2.contains(secret))
        }

        val malformed = IllegalArgumentException(
            "Malformed href returned by WebDAV server: https://user:S3CrEt@evil.example/x"
        )
        val r3 = WebDavFailurePolicy.refusalReason(malformed)
        assertTrue("malformed defusal keeps its cause: $r3", r3.contains("unusable"))
        for (secret in listOf("S3CrEt", "evil.example", "/x")) {
            assertFalse("no href text may survive: $r3", r3.contains(secret))
        }

        val nonHttp = IllegalArgumentException("Refusing non-HTTP(S) href from WebDAV server: ftp://host/x")
        assertTrue(
            "non-HTTPS defusal keeps its cause: ${WebDavFailurePolicy.refusalReason(nonHttp)}",
            WebDavFailurePolicy.refusalReason(nonHttp).contains("not HTTPS")
        )

        val ise = IllegalStateException(
            "Refusing to send WebDAV credentials to a host that is not the configured server (https://cloud.example.com:443)."
        )
        val r5 = WebDavFailurePolicy.refusalReason(ise)
        assertTrue("off-origin ISE keeps its cause: $r5", r5.contains("outside your configured WebDAV server"))
        assertFalse("no configured-server text may survive: $r5", r5.contains("cloud.example.com"))
    }

    // --- policy: configFailureText --------------------------------------------

    @Test
    fun `configFailureText falls back when the exception message is blank`() {
        assertEquals(
            WebDavFailurePolicy.INVALID_URL_MESSAGE,
            WebDavFailurePolicy.configFailureText(IllegalArgumentException("  "), WebDavFailurePolicy.INVALID_URL_MESSAGE)
        )
        assertEquals(
            WebDavFailurePolicy.INVALID_URL_MESSAGE,
            WebDavFailurePolicy.configFailureText(IllegalArgumentException(), WebDavFailurePolicy.INVALID_URL_MESSAGE)
        )
        assertEquals(
            "Malformed URL: host/...",
            WebDavFailurePolicy.configFailureText(IllegalArgumentException("Malformed URL: https://u:p@host/x"), "fallback")
        )
    }

    // --- source pins ----------------------------------------------------------

    @Test
    fun `no exception-message echo remains in WebDavSyncService`() {
        val source = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/WebDavSyncService.kt").readText()
        assertFalse("localizedMessage echo must be gone", source.contains("localizedMessage"))
        assertFalse("e.message user-facing echo must be gone", source.contains("\${e.message}"))
        assertFalse("e.message elvis fallback must be gone", source.contains("e.message ?:"))
        assertTrue("malformed-URL catch must echo the FIXED string", source.contains("throw IllegalArgumentException(WebDavFailurePolicy.INVALID_URL_MESSAGE)"))
        assertTrue("the normalized URL must be userinfo-stripped", source.contains("WebDavFailurePolicy.stripUrlUserInfo("))
        assertTrue("config-validation messages must route through the policy", source.contains("WebDavFailurePolicy.configFailureText("))
        assertTrue("href defusals must route through fixed tokens", source.contains("WebDavFailurePolicy.refusalReason(e)"))
        // The three blanket catches must map to fixed constants.
        for (const in listOf(
            "WebDavFailurePolicy.CONNECT_FAILURE_MESSAGE",
            "WebDavFailurePolicy.UPLOAD_FAILURE_MESSAGE",
            "WebDavFailurePolicy.DOWNLOAD_FAILURE_MESSAGE",
            "WebDavFailurePolicy.TOO_LARGE_DOWNLOAD_MESSAGE"
        )) {
            assertTrue("$const must be used by the matching catch", source.contains(const))
        }
    }

    @Test
    fun `the sync dialog scrubs every rendered sync status`() {
        val source = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/components/WebDavSyncDialog.kt").readText()
        assertEquals(
            "every rendered status must route through scrubForDisplay (upload res, restore failure, download res)",
            3,
            Regex("WebDavFailurePolicy\\.scrubForDisplay").findAll(source).toList().size
        )
        assertFalse("raw res.message rendering must be gone", source.contains("syncStatus = res.message"))
        assertFalse("raw failureMessage rendering must be gone", source.contains("syncStatus = failureMessage"))
    }

    @Test
    fun `the policy has no interpolated text and reads an exception message only via the scrubbed classifiers`() {
        val src = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/WebDavFailurePolicy.kt").readText()
        assertFalse("fixed strings must never interpolate", src.contains("\${"))
        // Exactly 2 CODE reads of `e.message` exist: the scrubbed classifiers
        // (configFailureText + refusalReason) — every other path is a fixed token.
        // (KDoc documents the pre-fix pattern with the `<e.message>` placeholder.)
        assertEquals(2, Regex("e\\.message\\.orEmpty\\(\\)").findAll(src).toList().size)
    }

    // --- helpers --------------------------------------------------------------

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