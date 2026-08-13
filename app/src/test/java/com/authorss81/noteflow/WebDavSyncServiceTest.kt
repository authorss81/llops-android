package com.authorss81.noteflow

import com.authorss81.noteflow.services.ImportExportService
import com.authorss81.noteflow.services.WebDavSyncService
import org.junit.Assert.*
import org.junit.Test
import java.net.URL

/**
 * JVM unit tests for phase-06 WebDAV sync hardening:
 * - HTTPS enforcement / URL parsing / local-network scoping of the HTTP opt-in
 *   (WebDavSyncService companion helpers).
 * - the unit-testable slice of the transactional restore orchestration
 *   (ImportExportService.checkRestoredSchemaNotNewer — the newer-schema guard
 *   that must run BEFORE the replaced DB is ever allowed to swap into place).
 */
class WebDavSyncServiceTest {

    // --- validateServerUrl: parsing + https enforcement ---

    @Test
    fun httpsUrlIsAcceptedAndNormalized() {
        assertEquals(
            "https://cloud.example.com/remote.php/dav",
            WebDavSyncService.validateServerUrl("https://cloud.example.com/remote.php/dav/")
        )
    }

    @Test
    fun httpsUrlWithoutTrailingSlashIsUnchanged() {
        assertEquals(
            "https://cloud.example.com",
            WebDavSyncService.validateServerUrl("  https://cloud.example.com  ")
        )
    }

    @Test
    fun httpPublicHostRejected() {
        try {
            WebDavSyncService.validateServerUrl("http://cloud.example.com/remote.php/dav")
            fail("Expected IllegalArgumentException for cleartext http to a public host")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("HTTPS"))
        }
    }

    @Test
    fun httpPublicHostStillRejectedEvenWhenInsecureOptedIn() {
        try {
            WebDavSyncService.validateServerUrl("http://cloud.example.com/remote.php/dav", allowInsecureHttp = true)
            fail("Expected public non-HTTPS host to be rejected even with allowInsecureHttp")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("HTTPS"))
        }
    }

    @Test
    fun httpLoopbackAllowedOnlyWithExplicitOptIn() {
        try {
            WebDavSyncService.validateServerUrl("http://localhost:8080")
            fail("Expected plain http localhost without opt-in to be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertEquals(
            "http://localhost:8080",
            WebDavSyncService.validateServerUrl("http://localhost:8080", allowInsecureHttp = true)
        )
    }

    @Test
    fun httpPrivateNetworkAllowedOnlyWithExplicitOptIn() {
        try {
            WebDavSyncService.validateServerUrl("http://192.168.1.50:8090", allowInsecureHttp = false)
            fail("Expected private-IP http without opt-in to be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertEquals(
            "http://192.168.1.50:8090",
            WebDavSyncService.validateServerUrl("http://192.168.1.50:8090", allowInsecureHttp = true)
        )
    }

    @Test
    fun nonHttpNonHttpsSchemeRejected() {
        try {
            WebDavSyncService.validateServerUrl("ftp://files.example.com")
            fail("Expected ftp:// to be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun emptyAndMalformedUrlsRejected() {
        try {
            WebDavSyncService.validateServerUrl("  ")
            fail("Expected empty URL to be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            WebDavSyncService.validateServerUrl("not a url at all")
            fail("Expected malformed URL to be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            WebDavSyncService.validateServerUrl("https:///missinghost")
            fail("Expected URL without a host to be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // --- isLocalNetworkHost ---

    @Test
    fun localNetworkHostDetection() {
        assertTrue(WebDavSyncService.isLocalNetworkHost("localhost"))
        assertTrue(WebDavSyncService.isLocalNetworkHost("127.0.0.1"))
        assertTrue(WebDavSyncService.isLocalNetworkHost("::1"))
        assertTrue(WebDavSyncService.isLocalNetworkHost("10.0.0.7"))
        assertTrue(WebDavSyncService.isLocalNetworkHost("172.16.0.1"))
        assertTrue(WebDavSyncService.isLocalNetworkHost("172.31.255.255"))
        assertTrue(WebDavSyncService.isLocalNetworkHost("192.168.1.50"))
        assertTrue(WebDavSyncService.isLocalNetworkHost("169.254.10.10"))
        assertTrue(WebDavSyncService.isLocalNetworkHost("nas.local"))
        assertTrue(WebDavSyncService.isLocalNetworkHost("LOCALHOST"))

        assertFalse(WebDavSyncService.isLocalNetworkHost("8.8.8.8"))
        assertFalse(WebDavSyncService.isLocalNetworkHost("172.32.0.1"))
        assertFalse(WebDavSyncService.isLocalNetworkHost("172.15.0.1"))
        assertFalse(WebDavSyncService.isLocalNetworkHost("cloud.example.com"))
    }

    @Test
    fun normalizeBaseUrlAddsTrailingSlash() {
        assertEquals(
            "https://cloud.example.com/dav/",
            WebDavSyncService.normalizeBaseUrl("https://cloud.example.com/dav", allowInsecureHttp = false)
        )
    }

    // --- requireSecureUrl (per-connection gate) ---

    @Test
    fun requireSecureUrlAllowsHttps() {
        WebDavSyncService.requireSecureUrl(URL("https://cloud.example.com/Noteflow_Vault/backup.nfb"), allowInsecureHttp = false)
    }

    @Test
    fun requireSecureUrlRejectsPublicHttp() {
        try {
            WebDavSyncService.requireSecureUrl(URL("http://cloud.example.com/backup.nfb"), allowInsecureHttp = true)
            fail("Expected public http URL to be rejected at the connection gate")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("HTTPS"))
        }
    }

    @Test
    fun requireSecureUrlAllowsLocalhostHttpOnlyWhenOptedIn() {
        try {
            WebDavSyncService.requireSecureUrl(URL("http://localhost:8080/backup.nfb"), allowInsecureHttp = false)
            fail("Expected localhost http without opt-in to be rejected at the connection gate")
        } catch (e: IllegalStateException) {
            // expected
        }
        WebDavSyncService.requireSecureUrl(URL("http://localhost:8080/backup.nfb"), allowInsecureHttp = true)
    }

    // --- transactional restore orchestration: newer-schema guard ---

    @Test
    fun restoreSchemaNotNewerAcceptsEqualAndOlder() {
        // Equal schema: fine.
        ImportExportService.checkRestoredSchemaNotNewer(8, 8)
        // Older schema: fine (Room migrations run forward).
        ImportExportService.checkRestoredSchemaNotNewer(7, 8)
        ImportExportService.checkRestoredSchemaNotNewer(0, 8)
    }

    @Test
    fun restoreSchemaNewerIsRejectedBeforeSwap() {
        try {
            ImportExportService.checkRestoredSchemaNotNewer(9, 8)
            fail("Expected a newer backup schema to be rejected")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("newer"))
        }
    }

    @Test
    fun restoreFieldReencryptLeavesPlaintextAlone() {
        // A field that is plaintext (not DEK-ciphertext) must be left untouched
        // by the cross-device re-key pass, not garbled.
        assertNull(
            ImportExportService.reencryptFieldValue("plain title", oldDek = ByteArray(32) { 1 }, newDek = ByteArray(32) { 2 })
        )
        assertNull(
            ImportExportService.reencryptFieldValue("", oldDek = ByteArray(32) { 1 }, newDek = ByteArray(32) { 2 })
        )
        assertNull(
            ImportExportService.reencryptFieldValue(null, oldDek = ByteArray(32) { 1 }, newDek = ByteArray(32) { 2 })
        )
    }
}