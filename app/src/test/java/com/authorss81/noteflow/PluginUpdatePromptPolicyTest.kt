package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.runtime.PluginUpdateInfo
import com.authorss81.noteflow.plugins.runtime.PluginVersion
import com.authorss81.noteflow.services.PluginUpdatePromptPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-157 feature 2: the pure-JVM update-dialog decision table — scrubbed
 * release notes, version deltas and the sequential "Update all" plan. Pins the
 * R2-b2b3-LOG-03 guarantee that hosted (attacker-influenceable) update notes
 * never reach the UI raw.
 */
class PluginUpdatePromptPolicyTest {

    private fun info(
        id: String = "com.authorss81.noteflow.plugins.remote.ocr",
        current: PluginVersion = PluginVersion.parse("1.2.0")!!,
        new: PluginVersion = PluginVersion.parse("1.3.0")!!,
        notes: String? = "Faster on low-end devices."
    ): PluginUpdateInfo = PluginUpdateInfo(
        pluginId = id,
        currentVersion = current,
        newVersion = new,
        downloadUrl = "https://plugins.example.com/ocr-1.3.0.apk",
        sha256 = "a".repeat(64),
        pinnedCertHash = "sha256/AAAABBBBCCCCDDDDEEEEFFFFGGGGHHHHIIIIJJJKKKLLLMMM",
        installSizeBytes = 1024L,
        updateNotes = notes
    )

    @Test
    fun `null and blank notes render as no notes`() {
        assertNull(PluginUpdatePromptPolicy.notesForDisplay(null))
        assertNull(PluginUpdatePromptPolicy.notesForDisplay(""))
        assertNull(PluginUpdatePromptPolicy.notesForDisplay("   "))
    }

    @Test
    fun `whitespace and line breaks collapse to a single space`() {
        // A CR/LF-bearing note (logcat line-forgery vehicle) must not forge
        // a second dialog line — it collapses into the one line.
        val notes = "First line\r\nDANGER: echo me\n\nThird line"
        val display = PluginUpdatePromptPolicy.notesForDisplay(notes)
        assertTrue(display!!.contains("First line DANGER: echo me Third line"))
        assertTrue(display.indexOf('\n') == -1)
        assertTrue(display.indexOf('\r') == -1)
    }

    @Test
    fun `notes are bounded to the display cap`() {
        val notes = "x".repeat(PluginUpdatePromptPolicy.MAX_NOTES_CHARS + 500)
        val display = PluginUpdatePromptPolicy.notesForDisplay(notes)
        assertTrue(display!!.length <= PluginUpdatePromptPolicy.MAX_NOTES_CHARS + 1)
        assertTrue(display.endsWith("…"))
    }

    @Test
    fun `url credentials and absolute paths never reach the dialog`() {
        val hostile = "see https://user:secret@host.example.com/private?token=abc123 " +
            "stored at /data/user/0/com.authorss81.noteflow.app/db/noteflow.sqlite"
        val display = PluginUpdatePromptPolicy.notesForDisplay(hostile)!!
        assertTrue("credential leaked: $display", !display.contains("secret"))
        assertTrue("query token leaked: $display", !display.contains("token=abc123"))
        assertTrue("vault file path leaked: $display", !display.contains("noteflow.sqlite"))
        assertTrue("url path leaked: $display", !display.contains("/private"))
        // The scrub keeps only the redacted root marker ("/data/user/0/..."),
        // never the tail of the absolute path.
        assertTrue("path not collapsed: $display", display.contains("/data/user/0/..."))
        assertTrue("path root+tail leaked: $display", !display.contains("noteflow.app"))
    }

    @Test
    fun `version delta text is compact and unambiguous`() {
        assertEquals("v1.2.0 → v1.3.0", PluginUpdatePromptPolicy.versionDeltaText(info()))
    }

    @Test
    fun `update-all plan is sorted by id and deduplicated`() {
        val a = info(id = "zzz.ocr")
        val b = info(id = "aa.llm", new = PluginVersion.parse("2.0.0")!!)
        val dup = info(id = "aa.llm", new = PluginVersion.parse("2.0.0")!!)

        val plan = PluginUpdatePromptPolicy.updateAllPlan(listOf(a, b, dup)) { it }
        assertEquals(listOf("aa.llm", "zzz.ocr"), plan.map { it.pluginId })
        assertEquals(listOf("v1.2.0 → v2.0.0", "v1.2.0 → v1.3.0"), plan.map { it.versionDeltaText })
    }

    @Test
    fun `update-all plan uses provided names and scrubs them`() {
        val plan = PluginUpdatePromptPolicy.updateAllPlan(
            listOf(info()),
            nameOf = { "On-Device OCR" }
        )
        assertEquals("On-Device OCR", plan.single().name)
        assertEquals("Faster on low-end devices.", plan.single().notes)
    }

    @Test
    fun `update-all plan name falls back to plugin id`() {
        val plan = PluginUpdatePromptPolicy.updateAllPlan(
            listOf(info()),
            nameOf = { "" }
        )
        assertEquals(info().pluginId, plan.single().name)
    }

    @Test
    fun `batch summary folds long name lists with the count`() {
        val updates = listOf(
            info(id = "a.one", notes = null),
            info(id = "b.two", notes = null),
            info(id = "c.three", notes = null),
            info(id = "d.four", notes = null)
        )
        val summary = PluginUpdatePromptPolicy.batchSummary(updates) { it.uppercase() }
        assertTrue(summary!!.startsWith("4 updates ready"))
        assertTrue(summary.contains("A.ONE, B.TWO, C.THREE"))
        assertTrue(summary.endsWith(" +1 more"))
    }

    @Test
    fun `batch summary is singular and null when empty`() {
        val single = PluginUpdatePromptPolicy.batchSummary(listOf(info())) { it }
        assertTrue(single!!.startsWith("1 update ready"))
        assertNull(PluginUpdatePromptPolicy.batchSummary(emptyList()) { it })
    }
}