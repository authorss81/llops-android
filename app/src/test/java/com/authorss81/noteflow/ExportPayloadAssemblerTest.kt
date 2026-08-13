package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.ExportFormat
import com.authorss81.noteflow.plugins.export.ExportPayloadAssembler
import com.authorss81.noteflow.plugins.export.MarkdownHtmlConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 15 Export Engine pure-JVM tests: payload assembly, Markdown→HTML via
 * CommonMark (+ GFM tables), plain-text fallbacks, empty-note handling, and
 * file-name sanitization. No Android classes are touched.
 */
class ExportPayloadAssemblerTest {

    // ---- file-name sanitization -------------------------------------------

    @Test
    fun `sanitizeBaseName keeps letters numbers dot underscore dash`() {
        // Run of whitespace collapses to underscores.
        assertEquals("My.Note_2_-_Final", ExportPayloadAssembler.sanitizeBaseName("My.Note_2 - Final"))
    }

    @Test
    fun `sanitizeBaseName replaces illegal characters with underscores`() {
        assertEquals("A_B_C", ExportPayloadAssembler.sanitizeBaseName("A/B?C*"))
    }

    @Test
    fun `sanitizeBaseName trims leading and trailing underscores`() {
        assertEquals("Note", ExportPayloadAssembler.sanitizeBaseName(".___ Note ___."))
    }

    @Test
    fun `sanitizeBaseName falls back to Note when blank`() {
        assertEquals("Note", ExportPayloadAssembler.sanitizeBaseName("   "))
        assertEquals("Note", ExportPayloadAssembler.sanitizeBaseName(""))
    }

    // ---- Markdown → HTML via the app's CommonMark parser ------------------

    @Test
    fun `assemble emits a full standalone html document for HTML format`() {
        val payload = ExportPayloadAssembler.assemble(
            title = "My Note",
            markdown = "# Heading\n\nSome *text*.",
            plainText = null,
            format = ExportFormat.HTML
        )
        assertTrue(payload.fileName.endsWith(".html"))
        assertTrue(payload.html.contains("<!DOCTYPE html>"))
        assertTrue(payload.html.contains("<h1>My Note</h1>"))
        assertTrue(payload.html.contains("<h1>Heading</h1>"))
        assertTrue(payload.html.contains("<em>text</em>"))
    }

    @Test
    fun `assemble renders GFM tables through the tables extension`() {
        val md = "| a | b |\n|---|---|\n| 1 | 2 |"
        val html = MarkdownHtmlConverter.toHtml(md)
        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("<th>a</th>"))
    }

    @Test
    fun `assemble keeps the raw markdown body for MARKDOWN format`() {
        val payload = ExportPayloadAssembler.assemble(
            title = "N",
            markdown = "# Hello",
            plainText = null,
            format = ExportFormat.MARKDOWN
        )
        assertEquals("# Hello", payload.markdown)
        assertTrue(payload.fileName.endsWith(".md"))
    }

    // ---- plain-text-only / empty notes ------------------------------------

    @Test
    fun `assemble escapes a plain-text-only note into paragraphs for HTML`() {
        val payload = ExportPayloadAssembler.assemble(
            title = "N",
            markdown = null,
            plainText = "line one\n\nline two",
            format = ExportFormat.HTML
        )
        assertTrue(payload.html.contains("<p>line one</p>"))
        assertTrue(payload.html.contains("<p>line two</p>"))
    }

    @Test
    fun `assemble derives plain text from markdown for the PDF body`() {
        val payload = ExportPayloadAssembler.assemble(
            title = "N",
            markdown = "Hello **world** with a [link](https://example.com).",
            plainText = null,
            format = ExportFormat.PDF
        )
        assertTrue(payload.plainText.contains("Hello world"))
        assertTrue(payload.fileName.endsWith(".pdf"))
    }

    @Test
    fun `assemble handles an empty note`() {
        val payload = ExportPayloadAssembler.assemble("", null, null, ExportFormat.HTML)
        assertTrue(payload.html.contains("Empty note"))
        assertEquals("Note.html", payload.fileName)
    }

    // ---- HTML escaping (defense against shelling tags into the doc) -------

    @Test
    fun `escapeHtml neutralizes angle brackets and ampersands`() {
        val escaped = MarkdownHtmlConverter.escapeHtml("<script>alert('x')</script> &")
        assertFalse(escaped.contains("<script>"))
        assertTrue(escaped.contains("&lt;script"))
        assertTrue(escaped.contains("&amp;"))
    }

    @Test
    fun `toPlainText strips markdown notation for readability`() {
        val plain = MarkdownHtmlConverter.toPlainText("## Title\n\n- item **one**")
        assertTrue(plain.contains("Title"))
        assertTrue(plain.contains("item one"))
        // No leftover markdown emphasis markers.
        assertFalse(plain.contains("**"))
    }
}