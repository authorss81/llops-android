package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.webcapture.WebPageFetchPolicy
import com.authorss81.noteflow.plugins.webcapture.WebToMarkdownExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 15 Web Capture pure-JVM tests (no network): jsoup extraction over a
 * captured HTML fixture, chrome stripping, container fallback, and URL
 * validation policy.
 */
class WebCaptureExtractorTest {

    private val sampleHtml = """
        <!DOCTYPE html>
        <html>
        <head><title>My Great Article</title></head>
        <body>
          <nav><a href="/">Home</a> <a href="/about">About</a></nav>
          <header><h1>Site header</h1></header>
          <script>alert("nope")</script>
          <style>.ads{}</style>
          <div class="ad-banner">BUY NOW</div>
          <article>
            <h1>My Great Article</h1>
            <p>This is the first paragraph with a <a href="https://example.com/x">link</a>.</p>
            <ul>
              <li>First item</li>
              <li>Second item</li>
            </ul>
            <blockquote>A quoted line.</blockquote>
          </article>
          <footer>footer stuff</footer>
        </body>
        </html>
    """.trimIndent()

    // ---- extraction --------------------------------------------------------

    @Test
    fun `extracts title and article content as markdown`() {
        val result = WebToMarkdownExtractor.extract(sampleHtml)
        assertEquals("My Great Article", result.title)
        assertTrue(result.markdown.contains("This is the first paragraph"))
        assertTrue(result.markdown.contains("[link](https://example.com/x)"))
    }

    @Test
    fun `strips scripts styles nav header and footer from the output`() {
        val result = WebToMarkdownExtractor.extract(sampleHtml)
        assertTrue(!result.markdown.contains("alert"))
        assertTrue(!result.markdown.contains("BUY NOW"))
        assertTrue(!result.markdown.contains("Site header"))
        assertTrue(!result.markdown.contains("footer stuff"))
        assertTrue(!result.markdown.contains("Home"))
    }

    @Test
    fun `converts lists and blockquotes`() {
        val result = WebToMarkdownExtractor.extract(sampleHtml)
        assertTrue(result.markdown.contains("- First item"))
        assertTrue(result.markdown.contains("- Second item"))
        assertTrue(result.markdown.contains("> A quoted line."))
    }

    @Test
    fun `falls back to body when no article container exists`() {
        val html = "<html><head><title>t</title></head><body><p>just some body text</p></body></html>"
        val result = WebToMarkdownExtractor.extract(html)
        assertTrue(result.markdown.contains("just some body text"))
    }

    @Test
    fun `blank page yields blank markdown`() {
        val result = WebToMarkdownExtractor.extract("")
        assertEquals("", result.markdown)
    }

    @Test
    fun `empty or unreadable page yields blank markdown`() {
        val html = "<html><head><title>x</title></head><body><div>   </div></body></html>"
        val result = WebToMarkdownExtractor.extract(html)
        assertEquals("", result.markdown)
    }

    @Test
    fun `inline formatting survives conversion`() {
        val html = "<article><h1>H</h1><p><strong>bold</strong> and <em>italic</em> and <code>code</code></p></article>"
        val result = WebToMarkdownExtractor.extract(html)
        assertTrue(result.markdown.contains("**bold**"))
        assertTrue(result.markdown.contains("*italic*"))
        assertTrue(result.markdown.contains("`code`"))
    }

    // ---- URL validation policy ---------------------------------------------

    @Test
    fun `bare hostnames are normalized to https`() {
        val valid = WebPageFetchPolicy.validateUrl("example.com/article")
        assertTrue(valid is WebPageFetchPolicy.Either.Valid)
        assertTrue((valid as WebPageFetchPolicy.Either.Valid).validation.scheme == "https")
    }

    @Test
    fun `http and https urls are accepted`() {
        assertTrue(WebPageFetchPolicy.validateUrl("https://example.com") is WebPageFetchPolicy.Either.Valid)
        assertTrue(WebPageFetchPolicy.validateUrl("http://example.com") is WebPageFetchPolicy.Either.Valid)
    }

    @Test
    fun `non-http schemes are rejected`() {
        val out = WebPageFetchPolicy.validateUrl("ftp://example.com/x")
        assertTrue(out is WebPageFetchPolicy.Either.Error)
        assertTrue((out as WebPageFetchPolicy.Either.Error).message.contains("http"))
    }

    @Test
    fun `blank input is rejected with a prompt`() {
        val out = WebPageFetchPolicy.validateUrl("   ")
        assertTrue(out is WebPageFetchPolicy.Either.Error)
    }

    @Test
    fun `hostless input is rejected`() {
        val out = WebPageFetchPolicy.validateUrl("https://")
        assertTrue(out is WebPageFetchPolicy.Either.Error)
    }

    @Test
    fun `js url is rejected as an unsafe scheme`() {
        val out = WebPageFetchPolicy.validateUrl("javascript:alert(1)")
        assertTrue(out is WebPageFetchPolicy.Either.Error)
    }
}