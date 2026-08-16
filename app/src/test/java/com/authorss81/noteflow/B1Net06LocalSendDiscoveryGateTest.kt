package com.authorss81.noteflow

import com.authorss81.noteflow.services.localsend.LocalSendDiscoveryPolicy
import com.authorss81.noteflow.services.localsend.LocalSendMessages
import com.authorss81.noteflow.services.localsend.LocalSendProtocol
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B1-NET-06 (phase-85): the LocalSend send dialog no longer makes the device
 * probe the whole /24 with HTTP `POST /register` sweeps nor announce a device
 * model — before any LAN traffic the user must explicitly act, and the `/24`
 * sweep is an explicit per-search opt-in that defaults OFF.
 *
 * The finding (`docs/security-report.md` B1-NET-06, LOW): opening the dialog
 * used to make the device sweep the whole subnet with HTTP POSTs on port 53317
 * and emit broadcast/multicast announces every ~1.1 s — any LAN host (or a
 * passive AP) could detect the app's presence, exact device model and local IP
 * without user confirmation.
 *
 * Pure JVM + source pins. Tests:
 *  1. The [LocalSendDiscoveryPolicy] decision table: discovery requires an
 *     explicit user action; the /24 HTTP scan is off by default and gated
 *     behind an explicit opt-in (fail closed).
 *  2. `LocalSendSender.discoverDevices` defaults the sweep to the policy OFF
 *     value (never a hard-coded `true`) and routes the probe through the gate.
 *  3. The dialog seeds its opt-in from the policy, feeds that value (not `true`)
 *     into discovery, and never auto-runs discovery on open.
 *  4. The sender identity (aliases/announce/register/prepare-upload bodies)
 *     carries no device model or `Build.MODEL` marker (wired to the policy).
 *  5. No `Build.MODEL` reference survives anywhere in the LocalSend main source.
 */
class B1Net06LocalSendDiscoveryGateTest {

    // ---- 1. Pure-JVM decision table ----

    @Test
    fun `discovery requires an explicit user action`() {
        assertTrue(LocalSendDiscoveryPolicy.DISCOVERY_REQUIRES_EXPLICIT_USER_ACTION)
        assertFalse(LocalSendDiscoveryPolicy.mayRunDiscovery(userInitiated = false))
        assertTrue(LocalSendDiscoveryPolicy.mayRunDiscovery(userInitiated = true))
    }

    @Test
    fun `legacy 24-subnet HTTP scan is off by default and fails closed`() {
        assertFalse(LocalSendDiscoveryPolicy.LEGACY_HTTP_SCAN_ENABLED_BY_DEFAULT)
        assertFalse(LocalSendDiscoveryPolicy.mayRunLegacyHttpScan(userOptedIn = false))
        assertTrue(LocalSendDiscoveryPolicy.mayRunLegacyHttpScan(userOptedIn = true))
    }

    @Test
    fun `sender identity is policy wired and model-free`() {
        assertEquals("InkFlow", LocalSendDiscoveryPolicy.SENDER_ALIAS)
        assertEquals(null, LocalSendDiscoveryPolicy.senderDeviceModel)
        val info = LocalSendMessages.senderIdentity(fingerprint = "inkflow-abc123")
        assertEquals(LocalSendDiscoveryPolicy.SENDER_ALIAS, info.alias)
        assertEquals(LocalSendDiscoveryPolicy.senderDeviceModel, info.deviceModel)
        assertTrue(info.alias.contains("InkFlow"))
        assertFalse(info.alias.contains("Pixel"))
    }

    // ---- 2+3. Source pins ----

    @Test
    fun `sender default never hard-codes the sweep on and routes through the gate`() {
        val sender = source("services/localsend/LocalSendSender.kt")
        // The default for the legacy scan must come from the policy's OFF value,
        // never a hard-coded `= true`.
        assertTrue(
            "discoverDevices must default includeLegacyHttpScan to the policy constant",
            sender.contains("includeLegacyHttpScan: Boolean = LocalSendDiscoveryPolicy.LEGACY_HTTP_SCAN_ENABLED_BY_DEFAULT")
        )
        assertFalse(
            "a hard-coded `includeLegacyHttpScan: Boolean = true` default would auto-enable the sweep",
            sender.contains("includeLegacyHttpScan: Boolean = true")
        )
        // The probe is only reached through the gate decision.
        assertTrue(
            "discoverDevices must route the sweep through MayRunLegacyHttpScan",
            sender.contains("LocalSendDiscoveryPolicy.mayRunLegacyHttpScan(includeLegacyHttpScan) && udpResults.isEmpty()")
        )
    }

    @Test
    fun `dialog seeds opt-in from policy and does not auto-discover on open`() {
        val dialog = source("ui/components/LocalSendSendDialog.kt")
        assertTrue(
            "dialog opt-in must seed from the policy OFF default",
            dialog.contains("mutableStateOf(LocalSendDiscoveryPolicy.LEGACY_HTTP_SCAN_ENABLED_BY_DEFAULT)")
        )
        // The discovery call passes the user's per-search opt-in state, never `true`.
        assertTrue(
            "discover() must pass the opt-in state, not a literal true",
            dialog.contains("includeLegacyHttpScan = legacyHttpScanOptIn")
        )
        assertFalse(
            "no hard-coded true sweep in the dialog",
            dialog.contains("includeLegacyHttpScan = true")
        )
        // Discovery is only reachable from the explicit button onClick handlers.
        assertFalse(
            "dialog must never auto-run discovery on open (no LaunchedEffect discover)",
            dialog.contains("LaunchedEffect") && dialog.contains("discover()")
        )
        assertTrue(
            "dialog must have explicit Find-nearby-devices and Refresh discover calls",
            dialog.contains("onClick = { discover() }")
        )
    }

    @Test
    fun `sender identity factory is wired to the policy constants`() {
        val protocol = source("services/localsend/LocalSendProtocol.kt")
        assertTrue(
            "senderIdentity must build from the policy alias",
            protocol.contains("alias = LocalSendDiscoveryPolicy.SENDER_ALIAS")
            && protocol.contains("deviceModel = LocalSendDiscoveryPolicy.senderDeviceModel")
        )
    }

    @Test
    fun `no Build-MODEL marker survives anywhere in the localsend main source`() {
        val files = listOf(
            "services/localsend/LocalSendSender.kt",
            "services/localsend/LocalSendProtocol.kt",
            "services/localsend/LocalSendPairing.kt",
            "services/localsend/LocalSendDiscoveryPolicy.kt",
            "services/localsend/SettingsLocalSendPairedDeviceStore.kt",
            "ui/components/LocalSendSendDialog.kt"
        )
        for (relative in files) {
            val source = source(relative)
            // Strip line comments so a KDoc that merely DOCUMENTS the ban of
            // `Build.MODEL` (as LocalSendProtocol.kt does) is not a false positive;
            // the pin is on CODE references, which must be absent.
            val codeOnly = source.lines()
                .filter {
                    val t = it.trimStart()
                    !t.startsWith("*") && !t.startsWith("//") && !t.startsWith("/*") && !t.startsWith("/**")
                }
                .joinToString("\n")
            assertFalse(
                "$relative must not reference Build.MODEL in code",
                codeOnly.contains("Build.MODEL")
            )
            assertFalse(
                "$relative must not put a model into the sender identity",
                codeOnly.contains("deviceModel = Build") || codeOnly.contains("\"Build.")
            )
        }
    }

    // ---- helpers ----

    private fun source(relativeMainPath: String): String {
        val root = repoRoot()
        val path = File(
            root,
            "app/src/main/kotlin/com/authorss81/noteflow/" + relativeMainPath
        )
        assertTrue("expected source file $relativeMainPath to exist", path.isFile)
        return path.readText()
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