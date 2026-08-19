package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginInvocationRecord
import com.authorss81.noteflow.plugins.PluginLifecycleState
import com.authorss81.noteflow.plugins.PluginStateInfo
import com.authorss81.noteflow.services.PluginDiagnosticsRowPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-157 feature 3: the pure-JVM per-plugin diagnostics row builder serving
 * Settings → Plugins. Pins the fixed label tables + the phase-148 rule that a
 * failure reason (hostile-plugin-influenceable via `availability()`) and last
 * invocation summaries never carry raw paths into the row.
 */
class PluginDiagnosticsRowPolicyTest {

    @Test
    fun `capability labels are fixed, sorted and bounded`() {
        assertEquals(
            "OCR · Text Tools",
            PluginDiagnosticsRowPolicy.servedCapabilitiesLabel(
                setOf(PluginCapability.OCR, PluginCapability.TextTools)
            )
        )
        assertEquals("OCR", PluginDiagnosticsRowPolicy.servedCapabilitiesLabel(setOf(PluginCapability.OCR)))
        assertEquals(
            "No capabilities declared",
            PluginDiagnosticsRowPolicy.servedCapabilitiesLabel(emptySet())
        )
    }

    @Test
    fun `capability label list folds beyond the cap with a count`() {
        val caps = setOf(
            PluginCapability.OCR,
            PluginCapability.WebSearch,
            PluginCapability.TextTransform,
            PluginCapability.Export,
            PluginCapability.Assistant,
            PluginCapability.FileTransfer
        )
        val label = PluginDiagnosticsRowPolicy.servedCapabilitiesLabel(caps)
        assertTrue(label.startsWith("Assistant · Export · File Transfer · OCR"))
        assertTrue(label.endsWith("+2 more"))
    }

    @Test
    fun `opt-in label mirrors the toggle`() {
        assertEquals("Opt-in: on", PluginDiagnosticsRowPolicy.optInLabel(true))
        assertEquals("Opt-in: off", PluginDiagnosticsRowPolicy.optInLabel(false))
    }

    @Test
    fun `lifecycle labels are the fixed shared table`() {
        assertEquals("Active", PluginDiagnosticsRowPolicy.lifecycleLabel(PluginLifecycleState.AVAILABLE))
        assertEquals("Enabled — verifying", PluginDiagnosticsRowPolicy.lifecycleLabel(PluginLifecycleState.ENABLED))
        assertEquals("Available — off", PluginDiagnosticsRowPolicy.lifecycleLabel(PluginLifecycleState.REGISTERED))
        assertEquals("Disabled", PluginDiagnosticsRowPolicy.lifecycleLabel(PluginLifecycleState.DISABLED))
        assertEquals("Unavailable", PluginDiagnosticsRowPolicy.lifecycleLabel(PluginLifecycleState.UNAVAILABLE))
        assertEquals("Rejected", PluginDiagnosticsRowPolicy.lifecycleLabel(PluginLifecycleState.REJECTED))
        assertEquals("Unknown", PluginDiagnosticsRowPolicy.lifecycleLabel(null))
    }

    @Test
    fun `reason line scrubs raw vault paths before surfacing`() {
        val state = state("availability check failed: failed to open /data/user/0/noteflow/db/noteflow.sqlite")
        val line = PluginDiagnosticsRowPolicy.reasonLine(state)!!
        assertTrue(line.startsWith("Reason: "))
        assertTrue("raw file path leaked: $line", !line.contains("noteflow.sqlite"))
        assertTrue("path tail leaked: $line", !line.contains("/db/"))
        // Only the redacted root marker survives ("/data/user/0/...").
        assertTrue("path not collapsed: $line", line.contains("/data/user/0/..."))
    }

    @Test
    fun `reason line is null when there is no reason`() {
        assertNull(PluginDiagnosticsRowPolicy.reasonLine(state(null)))
        assertNull(PluginDiagnosticsRowPolicy.reasonLine(null))
    }

    @Test
    fun `last invocation ok renders fixed text`() {
        assertEquals(
            "Last check: OK",
            PluginDiagnosticsRowPolicy.lastInvocationLine(PluginInvocationRecord(1L, ok = true, summary = "Success"))
        )
    }

    @Test
    fun `last invocation failure is scrubbed and honest`() {
        val line = PluginDiagnosticsRowPolicy.lastInvocationLine(
            PluginInvocationRecord(1L, ok = false, summary = "Threw SecurityException")
        )
        assertTrue(line!!.startsWith("Last check: failed — "))
        assertTrue(line.contains("SecurityException"))

        val hostile = PluginDiagnosticsRowPolicy.lastInvocationLine(
            PluginInvocationRecord(1L, ok = false, summary = "deleted C:\\Users\\evil\\vault")
        )
        assertTrue("path leaked: $hostile", !hostile!!.contains("C:\\Users"))
    }

    @Test
    fun `last invocation is null when never invoked`() {
        assertNull(PluginDiagnosticsRowPolicy.lastInvocationLine(null))
    }

    @Test
    fun `footer composes serve opt-in and lifecycle compactly`() {
        val footer = PluginDiagnosticsRowPolicy.footer(
            capabilities = setOf(PluginCapability.OCR),
            enabled = true,
            state = PluginLifecycleState.AVAILABLE
        )
        assertEquals("OCR · Opt-in: on · Active", footer)
    }

    private fun state(reason: String?): PluginStateInfo = PluginStateInfo(
        pluginId = "ocr",
        pluginName = "OCR",
        state = PluginLifecycleState.UNAVAILABLE,
        reason = reason,
        version = com.authorss81.noteflow.plugins.SemanticVersion(1, 0, 0),
        enabled = true,
        availableOnDevice = false,
        depsResolved = true,
        conflictWinnerId = null
    )
}