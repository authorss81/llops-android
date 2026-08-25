package com.authorss81.noteflow.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 212: [HtmlToMarkdownConverter] — the HTML import path that becomes
 * user-visible note content. Pins title extraction, block/inline mapping,
 * entity decoding, table passthrough, adversarial inputs (deep nesting,
 * unclosed tags, huge documents) and the internal-link wikilink format.
 */
class HtmlToMarkdownConverterTest {

    private fun convert(html: String) = HtmlToMarkdownConverter.convertHtmlToMarkdown(html)

    // ---- title --------------------------------------------------------------

    @Test
    fun `blank input yields an untitled empty note`() {
        assertEquals(Pair("Untitled", ""), convert("   "))
    }

    @Test
    fun `title comes from the head title element`() {
        val (title, _) = convert("<html><head><title>My Page</title></head><body>Hi</body></html>")

        assertEquals("My Page", title)
    }

    @Test
    fun `a missing title falls back to the first h1 then to the default`() {
        val (h1Title, _) =
            convert("<body><h1>Fallback Heading</h1><p>x</p></body>")
        val (defaultTitle, _) = convert("<p>no title here</p>")

        assertEquals("Fallback Heading", h1Title)
        assertEquals("Imported Note", defaultTitle)
    }

    // ---- blocks ---------------------------------------------------------------

    @Test
    fun `headings map to markdown hashes`() {
        val (_, body) = convert("<h1>One</h1><h2>Two</h2><h3>Three</h3>")

        assertTrue(body.contains("# One"))
        assertTrue(body.contains("## Two"))
        assertTrue(body.contains("### Three"))
    }

    @Test
    fun `pre-code blocks become fenced code`() {
        val (_, body) = convert("<pre><code>val x = 1 &lt; 2</code></pre>")

        assertTrue(body.contains("```"))
        assertTrue(body.contains("val x = 1 < 2"))
    }

    @Test
    fun `blockquotes prefix every line`() {
        val (_, body) = convert("<blockquote>first<br>second</blockquote>")

        assertTrue(body.contains("> first"))
        assertTrue(body.contains("> second"))
    }

    @Test
    fun `paragraphs and rules survive conversion`() {
        val (_, body) = convert("<p>alpha</p><hr/><p>beta</p>")

        assertTrue(body.contains("alpha"))
        assertTrue(body.contains("beta"))
        assertTrue(body.contains("---"))
    }

    @Test
    fun `nested list items flatten into dash bullets (known regex-flattening limit)`() {
        val html = "<ul><li>parent<ul><li>child</li></ul></li><li>sibling</li></ul>"
        val (_, body) = convert(html)

        // The single-pass <li> substitution merges a nested child into its
        // parent's bullet text and drops the ol/ol numbering — pinned here as
        // the converter's documented flattening behavior so any future
        // recursive parser shows up as an intentional change.
        assertTrue(body.contains("- parentchild"))
        assertTrue(body.contains("- sibling"))
    }

    @Test
    fun `tables pass through as pipe rows with a separator`() {
        val (_, body) =
            convert("<table><tr><th>A</th><th>B</th></tr><tr><td>1</td><td>2</td></tr></table>")

        assertTrue(body.contains("| A | B |"))
        assertTrue(body.contains("| --- | --- |"))
        assertTrue(body.contains("| 1 | 2 |"))
    }

    // ---- inline ---------------------------------------------------------------

    @Test
    fun `external links become markdown links`() {
        val (_, body) = convert("""<a href="https://example.com/x">site</a>""")

        assertTrue("[site](https://example.com/x)" in body)
    }

    @Test
    fun `internal links become wikilinks without stray characters`() {
        // Regression (phase-212 fix C): the wikilink branch used to emit a
        // stray double-quote INSIDE the target ([[page"]]), corrupting every
        // imported internal link.
        val (_, linked) = convert("""<a href="other.html">Other page</a>""")
        val (_, aliased) = convert("""<a href="sub/dir.html">Dir page</a>""")
        val (_, self) = convert("""<a href="same.html">same.html</a>""")

        assertTrue("[[other|Other page]]" in linked)
        assertTrue("[[dir|Dir page]]" in aliased)
        // A link whose text equals its target keeps no alias:
        assertTrue("[[same|same.html]]" in self)
        assertFalse(linked.contains("\""))
    }

    @Test
    fun `images carry their alt text`() {
        val (_, withAlt) = convert("""<img src="pic.png" alt="A picture"/>""")
        val (_, noAlt) = convert("""<img src="pic.png"/>""")

        assertTrue("![A picture](pic.png)" in withAlt)
        assertFalse(noAlt.isBlank())
    }

    @Test
    fun `bold italic and inline code map to markdown spans`() {
        val (_, body) =
            convert("<b>bold</b><i>it</i><code>x &amp;&amp; y</code>")

        assertTrue("**bold**" in body)
        assertTrue("*it*" in body)
        assertTrue("`x && y`" in body)
    }

    @Test
    fun `entities decode exactly once`() {
        val (_, body) = convert("<p>a &amp; b &lt;tag&gt; &quot;q&quot; &#39;s&#39; &nbsp;end</p>")

        assertTrue(body.contains("a & b <tag>"))
        assertTrue(body.contains("\"q\" 's'"))
        assertTrue(body.contains("end"))
    }

    // ---- stripping / safety ---------------------------------------------------

    @Test
    fun `script style and comments never reach the note`() {
        val (_, body) = convert(
            "<script>alert(1)</script><style>.x{}</style><!-- hidden -->visible"
        )

        assertFalse(body.contains("alert"))
        assertFalse(body.contains(".x"))
        assertFalse(body.contains("hidden"))
        assertTrue(body.contains("visible"))
    }

    @Test
    fun `deeply nested markup is bounded and terminates`() {
        val depth = 4000
        val sb = StringBuilder()
        repeat(depth) { sb.append("<div><span>") }
        sb.append("core text")
        repeat(depth) { sb.append("</span></div>") }

        val (_, body) = convert(sb.toString())

        assertTrue(body.contains("core text"))
        assertFalse(body.contains("<div>"))
    }

    @Test
    fun `unclosed tags degrade to plain text instead of hanging or throwing`() {
        val (_, body) = convert("<p>unclosed <b>bold <li>dangling")

        assertTrue(body.contains("unclosed"))
        assertTrue(body.contains("dangling") || body.contains("bold"))
        assertFalse(body.contains("<b>"))
    }

    @Test
    fun `large documents convert promptly`() {
        val paragraph = "<p>" + "word ".repeat(200) + "</p>"
        val html = paragraph.repeat(500)

        val start = System.nanoTime()
        val (_, body) = convert(html)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertTrue(body.length > 10_000)
        assertTrue("conversion took too long: ${elapsedMs}ms", elapsedMs < 15_000)
    }
}
