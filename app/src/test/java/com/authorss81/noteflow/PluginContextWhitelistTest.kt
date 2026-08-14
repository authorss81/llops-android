package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.runtime.CapabilityAwarePluginContext
import com.authorss81.noteflow.plugins.runtime.FacadeCall
import com.authorss81.noteflow.plugins.runtime.FacadeHost
import com.authorss81.noteflow.plugins.runtime.FacadeResult
import com.authorss81.noteflow.plugins.runtime.FacadeWhitelist
import com.authorss81.noteflow.plugins.runtime.PluginEntry
import com.authorss81.noteflow.plugins.runtime.PluginEntrySource
import com.authorss81.noteflow.plugins.runtime.PluginVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 23: the capability-whitelist facade. A downloaded plugin may call ONLY
 * the facade calls its declared capability grants; everything else is
 * deny-by-default, and HTTP is only ever the HTTPS variant (no cleartext
 * downgrade). Pure JVM — the grant/deny decision is a pure function of the
 * declared capability set, so the whole matrix is unit-tested.
 */
class PluginContextWhitelistTest {

    private fun entry(capabilities: Set<PluginCapability>) = PluginEntry(
        id = "com.authorss81.noteflow.plugins.test.facade",
        name = "Facade Test",
        description = "Facade test plugin.",
        version = PluginVersion(1, 0, 0),
        capabilities = capabilities,
        category = "Text",
        downloadUrl = "https://plugins.example.com/facade.apk",
        sha256 = "ab12",
        pinnedCertHash = "sha256/AAAA",
        source = PluginEntrySource.REMOTE
    )

    /** Records which calls the host received (proves granted calls delegate). */
    private class RecordingHost : FacadeHost {
        val calls = mutableListOf<String>()
        override fun insertText(text: String): FacadeResult<Unit> {
            calls += "insertText"
            return FacadeResult.Granted(Unit)
        }
        override fun showResult(title: String, body: String): FacadeResult<Unit> {
            calls += "showResult"
            return FacadeResult.Granted(Unit)
        }
        override fun httpGet(url: String): FacadeResult<String> {
            calls += "httpGet:$url"
            return FacadeResult.Granted("body")
        }
        override fun readSelection(): FacadeResult<String> {
            calls += "readSelection"
            return FacadeResult.Granted("selection")
        }
        override fun requestModelDownload(sizeBytes: Long): FacadeResult<Unit> {
            calls += "requestModelDownload"
            return FacadeResult.Granted(Unit)
        }
    }

    @Test
    fun `TextTransform grants insert, result and selection but not network or models`() {
        val ctx = CapabilityAwarePluginContext("t", setOf(PluginCapability.TextTransform), RecordingHost())

        assertTrue(ctx.insertText("x") is FacadeResult.Granted<*>)
        assertTrue(ctx.showResult("t", "b") is FacadeResult.Granted<*>)
        assertTrue(ctx.readSelection() is FacadeResult.Granted<*>)
        assertTrue(ctx.httpGet("https://x", httpsOnly = true) is FacadeResult.Denied)
        assertTrue(ctx.requestModelDownload(10) is FacadeResult.Denied)
    }

    @Test
    fun `OCR grants model download and HTTP plus editor calls`() {
        val ctx = CapabilityAwarePluginContext("t", setOf(PluginCapability.OCR), RecordingHost())

        assertTrue(ctx.requestModelDownload(100) is FacadeResult.Granted<*>)
        assertTrue(ctx.httpGet("https://x", httpsOnly = true) is FacadeResult.Granted<*>)
        assertTrue(ctx.insertText("x") is FacadeResult.Granted<*>)
        assertTrue(ctx.readSelection() is FacadeResult.Granted<*>)
    }

    @Test
    fun `WebSearch grants HTTP but never model download`() {
        val ctx = CapabilityAwarePluginContext("t", setOf(PluginCapability.WebSearch), RecordingHost())

        assertTrue(ctx.httpGet("https://x", httpsOnly = true) is FacadeResult.Granted<*>)
        assertTrue(ctx.requestModelDownload(10) is FacadeResult.Denied)
        assertTrue(ctx.insertText("x") is FacadeResult.Granted<*>)
    }

    @Test
    fun `httpGet refuses a cleartext downgrade even when HTTPS is granted`() {
        val ctx = CapabilityAwarePluginContext("t", setOf(PluginCapability.OCR), RecordingHost())

        val result = ctx.httpGet("http://insecure.example.com", httpsOnly = false)

        assertTrue(result is FacadeResult.Denied)
        assertTrue((result as FacadeResult.Denied).reason.contains("TLS"))
    }

    @Test
    fun `an unknown or unlisted capability contributes nothing (deny-by-default)`() {
        val ctx = CapabilityAwarePluginContext("t", emptySet(), RecordingHost())

        assertTrue(ctx.insertText("x") is FacadeResult.Denied)
        assertTrue(ctx.showResult("t", "b") is FacadeResult.Denied)
        assertTrue(ctx.httpGet("https://x", httpsOnly = true) is FacadeResult.Denied)
        assertTrue(ctx.readSelection() is FacadeResult.Denied)
        assertTrue(ctx.requestModelDownload(10) is FacadeResult.Denied)
    }

    @Test
    fun `the whitelist union matches the documented matrix`() {
        assertEquals(
            setOf(FacadeCall.INSERT_TEXT, FacadeCall.SHOW_RESULT, FacadeCall.READ_SELECTION),
            FacadeWhitelist.grantedFor(setOf(PluginCapability.TextTransform))
        )
        assertEquals(
            setOf(
                FacadeCall.INSERT_TEXT, FacadeCall.SHOW_RESULT, FacadeCall.READ_SELECTION,
                FacadeCall.HTTP_GET_HTTPS, FacadeCall.REQUEST_MODEL_DOWNLOAD
            ),
            FacadeWhitelist.grantedFor(setOf(PluginCapability.OCR))
        )
        assertEquals(
            setOf(
                FacadeCall.INSERT_TEXT, FacadeCall.SHOW_RESULT, FacadeCall.READ_SELECTION,
                FacadeCall.HTTP_GET_HTTPS
            ),
            FacadeWhitelist.grantedFor(setOf(PluginCapability.WebSearch))
        )
        assertEquals(
            setOf(FacadeCall.SHOW_RESULT, FacadeCall.READ_SELECTION),
            FacadeWhitelist.grantedFor(setOf(PluginCapability.Export))
        )
        assertTrue(FacadeWhitelist.grantedFor(emptySet()).isEmpty())
    }

    @Test
    fun `a granted call delegates to the host and returns its result`() {
        val host = RecordingHost()
        val ctx = CapabilityAwarePluginContext("t", setOf(PluginCapability.WebSearch), host)

        val body = ctx.httpGet("https://example.com/query", httpsOnly = true)

        assertTrue(body is FacadeResult.Granted<*>)
        assertEquals("body", (body as FacadeResult.Granted).value)
        assertEquals(listOf("httpGet:https://example.com/query"), host.calls)
    }

    @Test
    fun `a denied call never reaches the host`() {
        val host = RecordingHost()
        val ctx = CapabilityAwarePluginContext("t", setOf(PluginCapability.TextTransform), host)

        assertTrue(ctx.httpGet("https://x", httpsOnly = true) is FacadeResult.Denied)
        assertTrue(ctx.requestModelDownload(10) is FacadeResult.Denied)
        assertFalse(host.calls.isNotEmpty())
    }
}
