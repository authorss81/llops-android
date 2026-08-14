package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.runtime.DefaultPluginContext
import com.authorss81.noteflow.plugins.runtime.FacadeResult
import com.authorss81.noteflow.plugins.runtime.PluginContext
import com.authorss81.noteflow.plugins.runtime.PluginContextFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 22: the capability facade is DENY-BY-DEFAULT.
 *
 * No downloadable plugin is permitted anything until Phase 23 wires the
 * capability whitelist. Every facade call must return [FacadeResult.Denied]
 * with an honest reason — never a fake success, never a throw.
 */
class PluginContextFacadeTest {

    private val ctx: PluginContext = DefaultPluginContext("com.authorss81.noteflow.plugins.remote.ocr")

    private fun assertDenied(result: FacadeResult<*>, mentionCall: String) {
        assertTrue("expected Denied but got $result", result is FacadeResult.Denied)
        val denied = result as FacadeResult.Denied
        assertTrue(denied.reason.contains("deny-by-default"))
        assertTrue("reason should name the denied call", denied.reason.contains(mentionCall))
    }

    @Test
    fun `insertText is denied`() {
        assertDenied(ctx.insertText("hello"), "insertText")
    }

    @Test
    fun `showResult is denied`() {
        assertDenied(ctx.showResult("Title", "Body"), "showResult")
    }

    @Test
    fun `httpGet is denied even for https`() {
        assertDenied(ctx.httpGet("https://example.com", httpsOnly = true), "httpGet")
        assertDenied(ctx.httpGet("http://example.com", httpsOnly = true), "httpGet")
    }

    @Test
    fun `readSelection is denied`() {
        assertDenied(ctx.readSelection(), "readSelection")
    }

    @Test
    fun `requestModelDownload is denied`() {
        assertDenied(ctx.requestModelDownload(45_000_000L), "requestModelDownload")
    }

    @Test
    fun `the facade is scoped to the owning plugin id`() {
        val id = "com.authorss81.noteflow.plugins.remote.ocr"
        val denied = ctx.readSelection() as FacadeResult.Denied
        assertTrue(denied.reason.contains(id))
    }

    @Test
    fun `the default context factory hands out deny-by-default contexts`() {
        val entry = com.authorss81.noteflow.plugins.runtime.PluginEntry(
            id = "com.authorss81.noteflow.plugins.remote.llm",
            name = "Remote LLM",
            description = "Heavy downloadable local LLM.",
            version = com.authorss81.noteflow.plugins.runtime.PluginVersion(0, 1, 0),
            capabilities = setOf(com.authorss81.noteflow.plugins.PluginCapability.Assistant),
            category = "AI",
            downloadUrl = "https://plugins.example.com/llm.apk",
            sha256 = "f00d",
            pinnedCertHash = "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=",
            source = com.authorss81.noteflow.plugins.runtime.PluginEntrySource.REMOTE
        )

        val context = PluginContextFactory.DEFAULT.contextFor(entry)

        assertEquals(entry.id, context.pluginId)
        assertTrue(context is DefaultPluginContext)
        assertTrue(context.httpGet("https://example.com", httpsOnly = true) is FacadeResult.Denied)
    }
}
