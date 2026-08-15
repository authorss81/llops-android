package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.runtime.CompileTimePluginPinStore
import com.authorss81.noteflow.plugins.runtime.HostedPluginManifest
import com.authorss81.noteflow.plugins.runtime.HostedPluginVersion
import com.authorss81.noteflow.plugins.runtime.PinnedPluginRelease
import com.authorss81.noteflow.plugins.runtime.PluginEntry
import com.authorss81.noteflow.plugins.runtime.PluginEntrySource
import com.authorss81.noteflow.plugins.runtime.PluginUpdateChecker
import com.authorss81.noteflow.plugins.runtime.PluginUpdateInfo
import com.authorss81.noteflow.plugins.runtime.PluginVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 24: the update COMPARISON logic. An update is only offered when the
 * manifest lists the installed downloadable plugin, on the same channel, with a
 * version STRICTLY newer than installed — never an equal no-op, never a
 * downgrade, and never for a bundled (compile-time) plugin.
 *
 * Phase 42 (B1-NET-03): an offer is additionally only offered when its
 * sha256/pinnedCertHash match the compile-time per-plugin release pin and its
 * download host is allow-listed — the manifest can never introduce an unpinned
 * release.
 */
class PluginUpdateCheckerTest {

    private val installedId = "com.authorss81.noteflow.plugins.remote.ocr"
    private val testHost = "plugins.example.com"

    /** A pin store that pins [installedId]@each version at the digests the
     *  [offer] helpers use, and allows the test artifact host. */
    private fun pinsFor(vararg versions: PluginVersion): CompileTimePluginPinStore =
        CompileTimePluginPinStore(
            *versions.map { PinnedPluginRelease(installedId, it, "sha-$it", "sha256/pin") }.toTypedArray(),
            allowedDownloadHosts = setOf(testHost)
        )

    private fun remoteEntry(version: PluginVersion, channel: String = "stable"): PluginEntry =
        PluginEntry(
            id = installedId,
            name = "Remote OCR",
            description = "Heavy downloadable OCR engine.",
            version = version,
            capabilities = setOf(PluginCapability.OCR),
            category = "Vision",
            downloadUrl = "https://$testHost/ocr-$version.apk",
            sha256 = "sha-${version}",
            pinnedCertHash = "sha256/pin",
            updateChannel = channel,
            source = PluginEntrySource.REMOTE
        )

    private fun bundledEntry(
        id: String = "com.authorss81.noteflow.plugins.ocr.builtin",
        version: PluginVersion = PluginVersion(1, 0, 0)
    ): PluginEntry =
        PluginEntry(
            id = id,
            name = "Built-in OCR",
            description = "Compiled into the base APK.",
            version = version,
            capabilities = setOf(PluginCapability.OCR),
            category = "Vision",
            source = PluginEntrySource.BUNDLED
        )

    private fun offer(version: PluginVersion, id: String = installedId, channel: String = "stable") =
        HostedPluginVersion(
            id = id,
            version = version,
            downloadUrl = "https://$testHost/$id-$version.apk",
            sha256 = "sha-$version",
            pinnedCertHash = "sha256/pin",
            updateChannel = channel
        )

    @Test
    fun `a strictly newer manifest version is offered`() {
        val installed = remoteEntry(PluginVersion(1, 0, 0))

        val updates = PluginUpdateChecker.check(
            listOf(installed),
            HostedPluginManifest(listOf(offer(PluginVersion(1, 2, 0)))),
            pinsFor(PluginVersion(1, 2, 0))
        )

        assertEquals(1, updates.size)
        val update = updates.first() as PluginUpdateInfo
        assertEquals(PluginVersion(1, 0, 0), update.currentVersion)
        assertEquals(PluginVersion(1, 2, 0), update.newVersion)
        assertEquals("stable", update.updateChannel)
    }

    @Test
    fun `an equal version is never offered - no no-op updates`() {
        val installed = remoteEntry(PluginVersion(1, 0, 0))

        val updates = PluginUpdateChecker.check(
            listOf(installed),
            HostedPluginManifest(listOf(offer(PluginVersion(1, 0, 0)))),
            pinsFor(PluginVersion(1, 0, 0))
        )

        assertTrue(updates.isEmpty())
    }

    @Test
    fun `an older manifest version is never offered - no downgrades`() {
        val installed = remoteEntry(PluginVersion(2, 0, 0))

        val updates = PluginUpdateChecker.check(
            listOf(installed),
            HostedPluginManifest(listOf(offer(PluginVersion(1, 5, 0)))),
            pinsFor(PluginVersion(1, 5, 0))
        )

        assertTrue(updates.isEmpty())
    }

    @Test
    fun `a channel mismatch is never offered`() {
        val installed = remoteEntry(PluginVersion(1, 0, 0), channel = "stable")

        val updates = PluginUpdateChecker.check(
            listOf(installed),
            HostedPluginManifest(listOf(offer(PluginVersion(1, 1, 0), channel = "beta"))),
            pinsFor(PluginVersion(1, 1, 0))
        )

        assertTrue(updates.isEmpty())
    }

    @Test
    fun `bundled plugins are excluded even when the manifest lists them`() {
        val bundled = bundledEntry()

        val updates = PluginUpdateChecker.check(
            listOf(bundled),
            HostedPluginManifest(listOf(offer(PluginVersion(1, 1, 0), id = bundled.id))),
            pinsFor(PluginVersion(1, 1, 0))
        )

        assertTrue(updates.isEmpty())
    }

    @Test
    fun `a manifest offer for an unknown plugin is ignored`() {
        val installed = remoteEntry(PluginVersion(1, 0, 0))

        val updates = PluginUpdateChecker.check(
            listOf(installed),
            HostedPluginManifest(listOf(offer(PluginVersion(9, 0, 0), id = "some.other.plugin"))),
            pinsFor(PluginVersion(9, 0, 0))
        )

        assertTrue(updates.isEmpty())
    }

    @Test
    fun `the offer list is deterministic and ordered by plugin id`() {
        val a = remoteEntry(PluginVersion(1, 0, 0)).copy(id = "aaa.plugin")
        val b = remoteEntry(PluginVersion(1, 0, 0)).copy(id = "bbb.plugin")
        val pins = CompileTimePluginPinStore(
            PinnedPluginRelease("aaa.plugin", PluginVersion(1, 1, 0), "sha-1.1.0", "sha256/pin"),
            PinnedPluginRelease("bbb.plugin", PluginVersion(1, 1, 0), "sha-1.1.0", "sha256/pin"),
            allowedDownloadHosts = setOf(testHost)
        )

        val updates = PluginUpdateChecker.check(
            listOf(b, a),
            HostedPluginManifest(
                listOf(
                    offer(PluginVersion(1, 1, 0), id = "bbb.plugin"),
                    offer(PluginVersion(1, 1, 0), id = "aaa.plugin")
                )
            ),
            pins
        )

        assertEquals(listOf("aaa.plugin", "bbb.plugin"), updates.map { it.pluginId })
    }

    @Test
    fun `an empty manifest reports no updates`() {
        val installed = remoteEntry(PluginVersion(1, 0, 0))

        val updates = PluginUpdateChecker.check(listOf(installed), HostedPluginManifest(emptyList()), pinsFor())

        assertTrue(updates.isEmpty())
    }

    @Test
    fun `toTargetEntry preserves identity and carries the new version and digests`() {
        val installed = remoteEntry(
            PluginVersion(1, 0, 0),
            channel = "stable"
        ).copy(
            name = "Remote OCR",
            description = "Heavy downloadable OCR engine.",
            capabilities = setOf(PluginCapability.OCR),
            category = "Vision"
        )
        val update = PluginUpdateChecker.check(
            listOf(installed),
            HostedPluginManifest(listOf(offer(PluginVersion(2, 0, 0)))),
            pinsFor(PluginVersion(2, 0, 0))
        ).first()

        val target = update.toTargetEntry(installed)

        assertEquals(installed.id, target.id)
        assertEquals(installed.name, target.name)
        assertEquals(installed.description, target.description)
        assertEquals(installed.capabilities, target.capabilities)
        assertEquals(PluginVersion(2, 0, 0), target.version)
        assertEquals("https://$testHost/$installedId-2.0.0.apk", target.downloadUrl)
        assertEquals("sha-2.0.0", target.sha256)
        assertEquals(PluginEntrySource.REMOTE, target.source)
    }

    // ---- B1-NET-03 (Phase 42): the manifest can never set the trust anchor ----

    @Test
    fun `an offer whose sha256 differs from the compile-time pin is not offered`() {
        val installed = remoteEntry(PluginVersion(1, 0, 0))
        val forged = offer(PluginVersion(1, 2, 0), id = installedId)
            .copy(sha256 = "f00d") // structurally valid offer, wrong digest

        val updates = PluginUpdateChecker.check(
            listOf(installed),
            HostedPluginManifest(listOf(forged)),
            pinsFor(PluginVersion(1, 2, 0))
        )

        assertTrue(updates.isEmpty())
    }

    @Test
    fun `an offer whose certificate pin differs from the compile-time pin is not offered`() {
        val installed = remoteEntry(PluginVersion(1, 0, 0))
        val forged = offer(PluginVersion(1, 2, 0), id = installedId)
            .copy(pinnedCertHash = "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=")

        val updates = PluginUpdateChecker.check(
            listOf(installed),
            HostedPluginManifest(listOf(forged)),
            pinsFor(PluginVersion(1, 2, 0))
        )

        assertTrue(updates.isEmpty())
    }

    @Test
    fun `an offer for a version with no compile-time pin is not offered - fail closed`() {
        val installed = remoteEntry(PluginVersion(1, 0, 0))

        val updates = PluginUpdateChecker.check(
            listOf(installed),
            HostedPluginManifest(listOf(offer(PluginVersion(1, 2, 0)))),
            // The pin table only knows about 1.0.0 — 1.2.0 was never shipped in this build.
            pinsFor(PluginVersion(1, 0, 0))
        )

        assertTrue(updates.isEmpty())
    }

    @Test
    fun `an offer whose download host is not allow-listed is not offered`() {
        val installed = remoteEntry(PluginVersion(1, 0, 0))
        val forged = offer(PluginVersion(1, 2, 0), id = installedId)
            .copy(downloadUrl = "https://attacker.example/ocr-1.2.0.apk")

        val updates = PluginUpdateChecker.check(
            listOf(installed),
            HostedPluginManifest(listOf(forged)),
            pinsFor(PluginVersion(1, 2, 0))
        )

        assertTrue(updates.isEmpty())
    }

    @Test
    fun `a pinned offer carries the compile-time pin values not the manifest text`() {
        val installed = remoteEntry(PluginVersion(1, 0, 0))
        // The offer's digests match the pin, but are written in UPPERCASE hex to
        // prove the surviving PluginUpdateInfo uses the COMPILE-TIME values.
        val uppercase = offer(PluginVersion(1, 2, 0), id = installedId)
            .copy(sha256 = "SHA-1.2.0")

        val updates = PluginUpdateChecker.check(
            listOf(installed),
            HostedPluginManifest(listOf(uppercase)),
            pinsFor(PluginVersion(1, 2, 0))
        )

        assertEquals(1, updates.size)
        // The fingerprint is the compile-time pin (lowercase), never the wire text.
        assertEquals("sha-1.2.0", updates.first().sha256)
    }
}