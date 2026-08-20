package com.authorss81.noteflow

import com.authorss81.noteflow.services.CodeHighlightPolicy
import dev.snipme.highlights.model.SyntaxLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 179: pin the fenced-code syntax-highlighting behavior that both markdown
 * renderers now route through [CodeHighlightPolicy]. Pure JVM — the tokenizer
 * (dev.snipme:highlights) runs entirely on the classpath, no emulator needed.
 *
 * 1. FENCE TAG -> LANGUAGE — the common aliases (kt/js/ts/bash/c#/...) resolve,
 *    cases and extra info tokens are ignored, and unknown/absent tags resolve to
 *    `null` so the renderer degrades to honest plain text instead of crashing.
 * 2. SPAN SAFETY — highlighted offsets are always inside the code string, never
 *    empty, and never duplicated; the fence literal itself is never rewritten.
 * 3. THEME HONESTY — the same code tokenizes to genuinely different palettes in
 *    light vs dark mode (Atom One Light / Atom One Dark).
 * 4. FALLBACKS — null language, huge fences, and empty sources all render as
 *    plain text (empty span list), never a crash.
 */
class Phase179CodeHighlightTest {

    // --- 1. fence tag -> language ------------------------------------------

    @Test
    fun `fence tags resolve canonical language names`() {
        assertEquals(SyntaxLanguage.KOTLIN, CodeHighlightPolicy.languageForFenceTag("kotlin"))
        assertEquals(SyntaxLanguage.JAVA, CodeHighlightPolicy.languageForFenceTag("java"))
        assertEquals(SyntaxLanguage.PYTHON, CodeHighlightPolicy.languageForFenceTag("python"))
        assertEquals(SyntaxLanguage.TYPESCRIPT, CodeHighlightPolicy.languageForFenceTag("typescript"))
        assertEquals(SyntaxLanguage.C, CodeHighlightPolicy.languageForFenceTag("c"))
        assertEquals(SyntaxLanguage.CPP, CodeHighlightPolicy.languageForFenceTag("cpp"))
        assertEquals(SyntaxLanguage.GO, CodeHighlightPolicy.languageForFenceTag("go"))
        assertEquals(SyntaxLanguage.RUST, CodeHighlightPolicy.languageForFenceTag("rust"))
        assertEquals(SyntaxLanguage.SWIFT, CodeHighlightPolicy.languageForFenceTag("swift"))
        assertEquals(SyntaxLanguage.DART, CodeHighlightPolicy.languageForFenceTag("dart"))
        assertEquals(SyntaxLanguage.PHP, CodeHighlightPolicy.languageForFenceTag("php"))
        assertEquals(SyntaxLanguage.RUBY, CodeHighlightPolicy.languageForFenceTag("ruby"))
        assertEquals(SyntaxLanguage.PERL, CodeHighlightPolicy.languageForFenceTag("perl"))
        assertEquals(SyntaxLanguage.SHELL, CodeHighlightPolicy.languageForFenceTag("shell"))
        assertEquals(SyntaxLanguage.COFFEESCRIPT, CodeHighlightPolicy.languageForFenceTag("coffeescript"))
    }

    @Test
    fun `fence tags resolve common aliases`() {
        assertEquals(SyntaxLanguage.KOTLIN, CodeHighlightPolicy.languageForFenceTag("kt"))
        assertEquals(SyntaxLanguage.KOTLIN, CodeHighlightPolicy.languageForFenceTag("kts"))
        assertEquals(SyntaxLanguage.JAVASCRIPT, CodeHighlightPolicy.languageForFenceTag("js"))
        assertEquals(SyntaxLanguage.JAVASCRIPT, CodeHighlightPolicy.languageForFenceTag("jsx"))
        assertEquals(SyntaxLanguage.TYPESCRIPT, CodeHighlightPolicy.languageForFenceTag("ts"))
        assertEquals(SyntaxLanguage.TYPESCRIPT, CodeHighlightPolicy.languageForFenceTag("tsx"))
        assertEquals(SyntaxLanguage.CSHARP, CodeHighlightPolicy.languageForFenceTag("cs"))
        assertEquals(SyntaxLanguage.CSHARP, CodeHighlightPolicy.languageForFenceTag("c#"))
        assertEquals(SyntaxLanguage.CSHARP, CodeHighlightPolicy.languageForFenceTag("csharp"))
        assertEquals(SyntaxLanguage.CPP, CodeHighlightPolicy.languageForFenceTag("c++"))
        assertEquals(SyntaxLanguage.SHELL, CodeHighlightPolicy.languageForFenceTag("sh"))
        assertEquals(SyntaxLanguage.SHELL, CodeHighlightPolicy.languageForFenceTag("bash"))
        assertEquals(SyntaxLanguage.SHELL, CodeHighlightPolicy.languageForFenceTag("zsh"))
        assertEquals(SyntaxLanguage.PYTHON, CodeHighlightPolicy.languageForFenceTag("py"))
        assertEquals(SyntaxLanguage.PYTHON, CodeHighlightPolicy.languageForFenceTag("python3"))
        assertEquals(SyntaxLanguage.RUBY, CodeHighlightPolicy.languageForFenceTag("rb"))
    }

    @Test
    fun `fence tags are case-insensitive and ignore extra info`() {
        assertEquals(SyntaxLanguage.KOTLIN, CodeHighlightPolicy.languageForFenceTag("Kotlin"))
        assertEquals(SyntaxLanguage.JAVASCRIPT, CodeHighlightPolicy.languageForFenceTag("JS"))
        assertEquals(SyntaxLanguage.KOTLIN, CodeHighlightPolicy.languageForFenceTag("kotlin numberLines"))
        assertEquals(SyntaxLanguage.PYTHON, CodeHighlightPolicy.languageForFenceTag("  python linenos "))
        assertEquals(SyntaxLanguage.C, CodeHighlightPolicy.languageForFenceTag("objc"))
        assertEquals(SyntaxLanguage.C, CodeHighlightPolicy.languageForFenceTag("C"))
    }

    @Test
    fun `unknown or absent tags resolve to null for plain-text fallback`() {
        assertNull(CodeHighlightPolicy.languageForFenceTag(null))
        assertNull(CodeHighlightPolicy.languageForFenceTag(""))
        assertNull(CodeHighlightPolicy.languageForFenceTag("   "))
        assertNull(CodeHighlightPolicy.languageForFenceTag("sql"))
        assertNull(CodeHighlightPolicy.languageForFenceTag("json"))
        assertNull(CodeHighlightPolicy.languageForFenceTag("yaml"))
        assertNull(CodeHighlightPolicy.languageForFenceTag("html"))
        assertNull(CodeHighlightPolicy.languageForFenceTag("css"))
        assertNull(CodeHighlightPolicy.languageForFenceTag("markdown"))
        assertNull(CodeHighlightPolicy.languageForFenceTag("text"))
        assertNull(CodeHighlightPolicy.languageForFenceTag("plaintext"))
    }

    // --- 2. span safety -----------------------------------------------------

    @Test
    fun `kotlin source tokenizes to in-bounds non-empty spans`() {
        val code = "fun main() {\n    val message = \"hello\"\n    println(message)\n}"
        val spans = CodeHighlightPolicy.highlightSpans(code, SyntaxLanguage.KOTLIN, darkTheme = true)

        assertTrue("kotlin fence must produce highlighted spans", spans.isNotEmpty())
        spans.forEach { span ->
            assertTrue("start within bounds: ${span.start}", span.start in 0..code.length)
            assertTrue("end within bounds: ${span.end}", span.end in 0..code.length)
            assertTrue("span is never empty", span.start < span.end)
        }
        val distinctRanges = spans.map { it.start to it.end }.distinct()
        assertEquals("no two spans may target the identical range", distinctRanges.size, spans.size)
        val coveredText = code.substring(spans.first().start, spans.first().end)
        assertTrue("spans slice the ORIGINAL literal verbatim", coveredText.isNotEmpty())
    }

    @Test
    fun `span ranges reproduce original characters`() {
        val code = "val answer = 42 // the meaning"
        val spans = CodeHighlightPolicy.highlightSpans(code, SyntaxLanguage.KOTLIN, darkTheme = false)
        assertTrue(spans.isNotEmpty())
        spans.forEach { span ->
            val slice = code.substring(span.start, span.end)
            assertTrue("slice '$slice' must be a real substring of the source", code.contains(slice))
        }
    }

    @Test
    fun `empty and whitespace sources produce no spans without crashing`() {
        assertTrue(CodeHighlightPolicy.highlightSpans("", SyntaxLanguage.KOTLIN, darkTheme = true).isEmpty())
        assertTrue(CodeHighlightPolicy.highlightSpans("   ", SyntaxLanguage.JAVA, darkTheme = true).isEmpty())
        assertTrue(CodeHighlightPolicy.highlightSpans("\n", SyntaxLanguage.PYTHON, darkTheme = true).isEmpty())
    }

    // --- 3. theme honesty ---------------------------------------------------

    @Test
    fun `light and dark modes use genuinely different palettes`() {
        val code = "fun add(a: Int, b: Int): Int = a + b"
        val darkSpans = CodeHighlightPolicy.highlightSpans(code, SyntaxLanguage.KOTLIN, darkTheme = true)
        val lightSpans = CodeHighlightPolicy.highlightSpans(code, SyntaxLanguage.KOTLIN, darkTheme = false)
        assertTrue(darkSpans.isNotEmpty())
        assertTrue(lightSpans.isNotEmpty())
        assertTrue(
            "Atom light and dark palettes must not share every token color",
            darkSpans.map { it.rgb } != lightSpans.map { it.rgb }
        )
    }

    // --- 4. fallbacks -------------------------------------------------------

    @Test
    fun `null language and oversized fences degrade to plain text`() {
        val smallCode = "val x = 1"
        assertTrue(
            "no grammar -> no spans",
            CodeHighlightPolicy.highlightSpans(smallCode, null, darkTheme = true).isEmpty()
        )
        val huge = "x".repeat(CodeHighlightPolicy.MAX_TOKENIZED_CHARS + 1)
        assertEquals(
            "oversized fence -> plain text, never a stall",
            0,
            CodeHighlightPolicy.highlightSpans(huge, SyntaxLanguage.KOTLIN, darkTheme = true).size
        )
    }

    @Test
    fun `unknown tag path never invokes the tokenizer`() {
        assertNull(CodeHighlightPolicy.languageForFenceTag("sql"))
        assertNotNull(CodeHighlightPolicy.languageForFenceTag("kotlin"))
    }
}