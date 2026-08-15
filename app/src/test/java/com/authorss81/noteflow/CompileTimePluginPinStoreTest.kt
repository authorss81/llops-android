package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.runtime.CompileTimePluginPinStore
import com.authorss81.noteflow.plugins.runtime.CompileTimePluginPins
import com.authorss81.noteflow.plugins.runtime.DEFAULT_DOWNLOAD_HOSTS
import com.authorss81.noteflow.plugins.runtime.DEFAULT_MANIFEST_HOST
import com.authorss81.noteflow.plugins.runtime.HostedPluginVersion
import com.authorss81.noteflow.plugins.runtime.PinVerdict
import com.authorss81.noteflow.plugins.runtime.PinnedPluginRelease
import com.authorss81.noteflow.plugins.runtime.PluginEntry
import com.authorss81.noteflow.plugins.runtime.PluginEntrySource
import com.authorss81.noteflow.plugins.runtime.PluginVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1-NET-03 (Phase 42): the compile-time per-plugin release pin table is the
 * ONLY trust anchor the update chain accepts. A manifest offer that does not
 * match the compiled-in pin — different sha256, different certificate pin,
 * unlisted version, or a download host outside the allow-list — is Rejected
 * even though the offer itself is structurally valid ("the manifest validates").
 */
class CompileTimePluginPinStoreTest {

    private val id = "com.authorss81.noteflow.plugins.remote.ocr"
    private val v120 = PluginVersion(1, 2, 0)
    private val sha120 = "1a2b3c4d5e6f7890a1b2c3d4e5f67890123456789abcdef0123456789abcdef0"
    private val pin120 = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    private val host = "plugin-updates.inkflow.app"

    private val store = CompileTimePluginPinStore(
        PinnedPluginRelease(id, v120, sha120, pin120)
    )

    private fun offer(
        sha: String = sha120,
        pin: String = pin120,
        downloadUrl: String = "https://$host/ocr-1.2.0.apk",
        version: PluginVersion = v120,
        id: String = this.id
    ) = HostedPluginVersion(
        id = id,
        version = version,
        downloadUrl = downloadUrl,
        sha256 = sha,
        pinnedCertHash = pin,
        updateChannel = "stable"
    )

    @Test
    fun `an offer matching the compile-time pin is verified`() {
        val verdict = store.verifyOffer(offer())
        assertTrue(verdict is PinVerdict.Verified)
        assertEquals(sha120, (verdict as PinVerdict.Verified).pin.sha256)
        assertEquals(pin120, verdict.pin.pinnedCertHash)
    }

    @Test
    fun `an offer whose sha256 differs from the compile-time pin is rejected`() {
        val forged = "f00d" + sha120.drop(4) // flip the first nibble
        val verdict = store.verifyOffer(offer(sha = forged))

        assertTrue(verdict is PinVerdict.Rejected)
        assertTrue((verdict as PinVerdict.Rejected).reason.contains("SHA-256"))
    }

    @Test
    fun `an offer whose certificate pin differs from the compile-time pin is rejected`() {
        val forged = "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="
        val verdict = store.verifyOffer(offer(pin = forged))

        assertTrue(verdict is PinVerdict.Rejected)
        assertTrue((verdict as PinVerdict.Rejected).reason.contains("certificate pin"))
    }

    @Test
    fun `an offer for a version with no compile-time pin is rejected - fail closed`() {
        val newer = PluginVersion(2, 0, 0)
        val verdict = store.verifyOffer(offer(version = newer, sha = "0" + sha120.drop(1)))

        assertTrue(verdict is PinVerdict.Rejected)
        assertTrue((verdict as PinVerdict.Rejected).reason.contains("no compile-time pinned identity"))
    }

    @Test
    fun `an offer whose download host is not on the allow-list is rejected`() {
        val verdict = store.verifyOffer(
            offer(downloadUrl = "https://attacker.example/ocr-1.2.0.apk")
        )

        assertTrue(verdict is PinVerdict.Rejected)
        assertTrue((verdict as PinVerdict.Rejected).reason.contains("allow-listed"))
    }

    @Test
    fun `an offer for an unknown plugin is rejected`() {
        val verdict = store.verifyOffer(offer(id = "some.other.plugin", sha = "0" + sha120.drop(1)))

        assertTrue(verdict is PinVerdict.Rejected)
        assertTrue((verdict as PinVerdict.Rejected).reason.contains("no compile-time pinned identity"))
    }

    @Test
    fun `pinnedFor returns null for an unlisted id or version`() {
        assertNull(store.pinnedFor(id, PluginVersion(9, 9, 9)))
        assertNull(store.pinnedFor("unknown.id", v120))
        assertEquals(sha120, store.pinnedFor(id, v120)?.sha256)
    }

    @Test
    fun `host allow-list is case-insensitive and folds a trailing dot`() {
        assertTrue(store.isAllowedDownloadHost("HTTPS://$host/ocr.apk"))
        assertTrue(store.isAllowedDownloadHost("https://$host./ocr.apk"))
        assertTrue(store.isAllowedDownloadHost("https://$host:443/ocr.apk"))
        assertFalse(store.isAllowedDownloadHost("https://evil.example.org/ocr.apk"))
        assertFalse(store.isAllowedDownloadHost("not a url"))
        assertFalse(store.isAllowedDownloadHost("https:///no-host"))
    }

    @Test
    fun `an entry target is verified against the same compile-time anchor`() {
        val entry = PluginEntry(
            id = id,
            name = "Remote OCR",
            description = "Heavy downloadable OCR engine.",
            version = v120,
            capabilities = setOf(PluginCapability.OCR),
            category = "Vision",
            downloadUrl = "https://$host/ocr-1.2.0.apk",
            sha256 = sha120,
            pinnedCertHash = pin120,
            source = PluginEntrySource.REMOTE
        )
        assertTrue(store.verifyEntry(entry) is PinVerdict.Verified)

        val repinned = entry.copy(pinnedCertHash = "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=")
        val verdict = store.verifyEntry(repinned)
        assertTrue(verdict is PinVerdict.Rejected)
    }

    @Test
    fun `the production default store is fail-closed and the host allow-list includes the manifest host`() {
        assertTrue(CompileTimePluginPins.RELEASES.isEmpty())
        assertTrue(DEFAULT_DOWNLOAD_HOSTS.contains(DEFAULT_MANIFEST_HOST))
        // No release is pinned in this build, so even a well-formed offer is rejected.
        val verdict = CompileTimePluginPins.defaultStore.verifyOffer(offer())
        assertTrue(verdict is PinVerdict.Rejected)
        assertTrue((verdict as PinVerdict.Rejected).reason.contains("no compile-time pinned identity"))
    }
}