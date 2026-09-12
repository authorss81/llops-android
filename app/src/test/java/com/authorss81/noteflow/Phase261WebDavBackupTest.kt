package com.authorss81.noteflow

import com.authorss81.noteflow.services.BackupExportPolicy
import com.authorss81.noteflow.services.WebDavSyncService
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 261 — WebDAV DNS-masquerade + backup staging hygiene.
 *
 * 1. `isLocalNetworkHost` must never treat a DNS name as local, even when it
 *    starts with a private-range label (`10.evil.com`,
 *    `192.168.attacker.example`), while literal private IPs stay local.
 * 2. The transient plaintext zip stage must be owned by BackupExportPolicy
 *    (`createTempFile` + in-policy `finally` delete) so a crash cannot leave
 *    plaintext behind.
 */
class Phase261WebDavBackupTest {

    // --- DNS masquerade: hostile names are NEVER local ---

    @Test
    fun `dns masquerade 10-evil-com is NOT local`() {
        assertFalse(WebDavSyncService.isLocalNetworkHost("10.evil.com"))
    }

    @Test
    fun `dns masquerade 192-168-attacker is NOT local`() {
        assertFalse(WebDavSyncService.isLocalNetworkHost("192.168.attacker.example"))
    }

    @Test
    fun `dns masquerade 172-16-attacker is NOT local`() {
        assertFalse(WebDavSyncService.isLocalNetworkHost("172.16.attacker.example"))
    }

    @Test
    fun `public dns and public ips are NOT local`() {
        assertFalse(WebDavSyncService.isLocalNetworkHost("cloud.example.com"))
        assertFalse(WebDavSyncService.isLocalNetworkHost("8.8.8.8"))
        assertFalse(WebDavSyncService.isLocalNetworkHost("172.32.0.1"))
        assertFalse(WebDavSyncService.isLocalNetworkHost("172.15.255.255"))
        assertFalse(WebDavSyncService.isLocalNetworkHost("2001:db8::1"))
    }

    @Test
    fun `colon garbage never reaches the resolver`() {
        // Review-fix: the old contains(':') shortcut passed any colon-bearing
        // string to InetAddress.getByName. Only shape-validated IPv6 literals
        // proceed now — single-colon tokens and non-hex garbage are NOT local.
        assertFalse(WebDavSyncService.isLocalNetworkHost("foo:bar"))
        assertFalse(WebDavSyncService.isLocalNetworkHost("12345:garbage"))
        assertFalse(WebDavSyncService.isLocalNetworkHost("dead:beef"))
    }

    @Test
    fun `public ipv4-mapped ipv6 is NOT local`() {
        // The mapped prefix alone must not confer locality — only a PRIVATE
        // embedded tail does (::ffff:10.x stays local per the literals test).
        assertFalse(WebDavSyncService.isLocalNetworkHost("::ffff:8.8.8.8"))
    }

    @Test
    fun `hex-dotted ipv4 fails closed`() {
        // Review-fix: dotted candidates are decimal-only so the gate agrees
        // with the structural parser — hex-dotted hosts are refused HTTP
        // instead of depending on per-platform resolver quirks.
        assertFalse(WebDavSyncService.isLocalNetworkHost("0x7f.0.0.1"))
        assertFalse(WebDavSyncService.isLocalNetworkHost("0x0a.0.0.1"))
    }

    // --- literals: private/loopback/link-local/ULA/mapped ARE local ---

    @Test
    fun `literal 10-0-0-5 IS local`() {
        assertTrue(WebDavSyncService.isLocalNetworkHost("10.0.0.5"))
    }

    @Test
    fun `rfc1918 and loopback literals ARE local`() {
        assertTrue(WebDavSyncService.isLocalNetworkHost("192.168.1.50"))
        assertTrue(WebDavSyncService.isLocalNetworkHost("172.16.0.1"))
        assertTrue(WebDavSyncService.isLocalNetworkHost("172.31.255.255"))
        assertTrue(WebDavSyncService.isLocalNetworkHost("127.0.0.1"))
        assertTrue(WebDavSyncService.isLocalNetworkHost("127.0.0.2"))
        assertTrue(WebDavSyncService.isLocalNetworkHost("localhost"))
        assertTrue(WebDavSyncService.isLocalNetworkHost("printer.local"))
        assertTrue(WebDavSyncService.isLocalNetworkHost("::1"))
        assertTrue(WebDavSyncService.isLocalNetworkHost("169.254.10.20"))
        assertTrue(WebDavSyncService.isLocalNetworkHost("fc00::1"))
        assertTrue(WebDavSyncService.isLocalNetworkHost("fd00::1"))
        assertTrue(WebDavSyncService.isLocalNetworkHost("fe80::1"))
        assertTrue(WebDavSyncService.isLocalNetworkHost("::ffff:10.0.0.1"))
    }

    @Test
    fun `http opt-in still refuses masquerade but allows literal`() {
        try {
            WebDavSyncService.validateServerUrl("http://10.evil.com/remote.php/dav", allowInsecureHttp = true)
            fail("masquerade DNS must be refused even with allowInsecureHttp")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("HTTPS"))
        }
        assertEquals(
            "http://10.0.0.5/remote.php/dav",
            WebDavSyncService.validateServerUrl("http://10.0.0.5/remote.php/dav", allowInsecureHttp = true)
        )
    }

    // --- staging hygiene: crash deletes the plaintext stage ---

    @Test
    fun `staging file is deleted after success`() {
        val dir = Files.createTempDirectory("phase261-ok").toFile()
        try {
            var seen: File? = null
            BackupExportPolicy.useStagingZip(dir) { staging ->
                seen = staging
                assertTrue(staging.isFile)
                staging.writeText("plaintext")
            }
            assertTrue(seen != null)
            assertFalse(seen!!.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `staging file is deleted on crash`() {
        val dir = Files.createTempDirectory("phase261-crash").toFile()
        try {
            var seen: File? = null
            try {
                BackupExportPolicy.useStagingZip(dir) { staging ->
                    seen = staging
                    staging.writeText("plaintext")
                    throw IllegalStateException("simulated crash between staging and delete")
                }
                fail("exception must propagate")
            } catch (e: IllegalStateException) {
                assertTrue(e.message.orEmpty().contains("simulated crash"))
            }
            assertTrue(seen != null)
            assertFalse("crash must not leave plaintext staging behind", seen!!.exists())
            assertEquals(0, dir.listFiles()?.size ?: 0)
        } finally {
            dir.deleteRecursively()
        }
    }

    // --- source pins ---

    private fun mainSource(rel: String): String {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val candidates = listOf(
            "src/main/kotlin/com/authorss81/noteflow/$rel",
            "app/src/main/kotlin/com/authorss81/noteflow/$rel"
        )
        var dir: File? = start
        while (dir != null) {
            candidates.forEach { c ->
                File(dir, c).takeIf { it.isFile }?.let { return it.readText() }
            }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate $rel from ${start.path}")
    }

    private fun resStrings(): String {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            val f = File(dir, "app/src/main/res/values/strings.xml")
            if (f.isFile) return f.readText()
            dir = dir.parentFile
        }
        throw AssertionError("could not locate strings.xml from ${start.path}")
    }

    @Test
    fun `isLocalNetworkHost classifies via InetAddress never startsWith on DNS`() {
        val src = mainSource("services/WebDavSyncService.kt")
        assertTrue(src.contains("InetAddress.getByName"))
        assertTrue(src.contains("isLoopbackAddress"))
        assertTrue(src.contains("isSiteLocalAddress"))
        assertTrue(src.contains("isLinkLocalAddress"))
        assertFalse(
            "raw startsWith on private prefixes misclassifies DNS (10.evil.com)",
            src.contains("startsWith(\"10.\")")
        )
        assertFalse(
            "raw startsWith on private prefixes misclassifies DNS (192.168.attacker.example)",
            src.contains("startsWith(\"192.168.\")")
        )
        assertFalse(
            "raw startsWith on 172. runs InetAddress-equivalent logic on DNS labels",
            src.contains("startsWith(\"172.\")")
        )
        assertFalse(
            "raw startsWith on link-local runs on DNS labels",
            src.contains("startsWith(\"169.254.\")")
        )
    }

    @Test
    fun `staging lifecycle is owned in-policy via createTempFile plus finally delete`() {
        val policy = mainSource("services/BackupExportPolicy.kt")
        assertTrue(policy.contains("fun <T> useStagingZip"))
        assertTrue(policy.contains("createTempFile"))
        assertTrue(policy.contains("finally"))
        assertTrue(policy.contains("staging.delete()"))
        val exporter = mainSource("services/ImportExportService.kt")
        assertTrue(exporter.contains("useStagingZip"))
    }

    @Test
    fun `sync call sites carry the explicit device-keyed warning`() {
        val webdav = mainSource("ui/components/WebDavSyncDialog.kt")
        assertTrue(webdav.contains("webdav_device_keyed_notice"))
        val localsend = mainSource("ui/components/LocalSendSendDialog.kt")
        assertTrue(localsend.contains("localsend_device_keyed_notice"))
        val strings = resStrings()
        assertTrue(strings.contains("webdav_device_keyed_notice"))
        assertTrue(strings.contains("localsend_device_keyed_notice"))
        // Review-fix: the device-keyed warning's single source of truth is
        // strings.xml — the unreferenced DEVICE_KEYED_SYNC_WARNING const was
        // deleted so a third copy cannot drift; pin its absence.
        val policy = mainSource("services/BackupPortabilityPolicy.kt")
        assertFalse(policy.contains("DEVICE_KEYED_SYNC_WARNING"))
    }
}
