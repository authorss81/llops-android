package com.authorss81.noteflow.services

import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.CodeHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes

/**
 * Phase 179 — syntax highlighting for fenced code blocks in the markdown
 * renderers, backed by the pure-Kotlin `dev.snipme:highlights` tokenizer
 * (highlight.js-style grammars, Apache-2.0). Everything the UI needs lives here
 * and is pure JVM so the behavior is unit-testable without an emulator:
 *
 *  1. markdown fence tags (``` kotlin, ```c++, ...) are normalized onto the
 *     tokenizer's supported [SyntaxLanguage] values, INCLUDING the common
 *     aliases (js/ts/kt/bash/c#/...) so the tag on the fence is honored on-device
 *     just like an IDE would;
 *  2. an unknown or absent tag (json, yaml, html, `text`, ...) resolves to
 *     `null` -> the renderer falls back to honest plain text. The tokenizer
 *     NEVER crashes: it simply contributes no spans;
 *  3. tokenized locations are bounds-clamped to the code string before they can
 *     reach the renderer, so a single out-of-range token can never crash a
 *     whole note render (the fence content is rendered verbatim either way);
 *  4. very large fences are rendered plain: tokenizing happens synchronously on
 *     the recomposition frame, so a pathological multi-megabyte block stays cheap.
 *
 * The span model only carries offsets + colors; the source text is never
 * rewritten, so copy/selection always sees the exact fence literal.
 */
object CodeHighlightPolicy {

    data class CodeSpan(
        val start: Int,
        val end: Int,
        val rgb: Int,
        val bold: Boolean = false
    )

    /**
     * Synchronous tokenization cap. Fences above this size render as plain text
     * (honest, zero-latency fallback) rather than stalling the frame.
     */
    const val MAX_TOKENIZED_CHARS = 40_000

    private const val RGB_MASK = 0x00FFFFFF

    private val FENCE_TAG_ALIASES: Map<String, SyntaxLanguage> = buildMap {
        SyntaxLanguage.entries.forEach { put(it.name.lowercase(), it) }
        // The ubiquitous short identifiers + shell/py/rb/c# forms people actually
        // write on fences. Anything else stays unresolved -> plain text.
        put("js", SyntaxLanguage.JAVASCRIPT)
        put("jsx", SyntaxLanguage.JAVASCRIPT)
        put("mjs", SyntaxLanguage.JAVASCRIPT)
        put("cjs", SyntaxLanguage.JAVASCRIPT)
        put("ts", SyntaxLanguage.TYPESCRIPT)
        put("tsx", SyntaxLanguage.TYPESCRIPT)
        put("kt", SyntaxLanguage.KOTLIN)
        put("kts", SyntaxLanguage.KOTLIN)
        put("cpp", SyntaxLanguage.CPP)
        put("c++", SyntaxLanguage.CPP)
        put("c", SyntaxLanguage.C)
        put("cs", SyntaxLanguage.CSHARP)
        put("c#", SyntaxLanguage.CSHARP)
        put("csharp", SyntaxLanguage.CSHARP)
        put("sh", SyntaxLanguage.SHELL)
        put("shell", SyntaxLanguage.SHELL)
        put("bash", SyntaxLanguage.SHELL)
        put("zsh", SyntaxLanguage.SHELL)
        put("py", SyntaxLanguage.PYTHON)
        put("python3", SyntaxLanguage.PYTHON)
        put("rb", SyntaxLanguage.RUBY)
        put("coffee", SyntaxLanguage.COFFEESCRIPT)
        put("objc", SyntaxLanguage.C)
    }

    /**
     * Resolves a fence info string ("kotlin", "cpp linenos", "{.js}"...) onto a
     * supported language. `null` means "no grammar": render plain text.
     */
    fun languageForFenceTag(tag: String?): SyntaxLanguage? {
        if (tag.isNullOrBlank()) return null
        val firstToken = tag.split(Regex("\\s+|\\{|\\}"))
            .mapNotNull { it.trim().ifEmpty { null } }
            .firstOrNull()
            ?.trim('.')
            ?: return null
        if (firstToken.isEmpty()) return null
        return FENCE_TAG_ALIASES[firstToken.lowercase()]
    }

    /**
     * Returns the tokenizer spans for [code] under [language], or `emptyList()`
     * when there is nothing to highlight. [darkTheme] selects the syntax theme
     * palette (light-on-dark vs dark-on-light). Rendering NEVER depends on this
     * call succeeding — the caller shows the raw [code] either way.
     */
    fun highlightSpans(code: String, language: SyntaxLanguage?, darkTheme: Boolean): List<CodeSpan> {
        if (language == null || code.length > MAX_TOKENIZED_CHARS) return emptyList()
        return try {
            // `atom` ships a REAL light palette (Atom One Light) AND a REAL dark
            // palette (Atom One Dark), so a light scheme does not inherit
            // dark-theme token colors (darcula/monokai reuse the same token
            // colors for both modes and their pinks are low-contrast on light
            // surfaces). Base frame matches the renderer's surfaceVariant.
            val theme = SyntaxThemes.atom(darkTheme)
            val highlights: List<CodeHighlight> = Highlights.Builder(
                language = language,
                code = code
            ).theme(theme).build().getHighlights()
            val clamped = highlights.mapNotNull { highlight ->
                val rawStart = highlight.location.start
                val rawEnd = highlight.location.end
                val start = rawStart.coerceIn(0, code.length)
                val end = rawEnd.coerceIn(start, code.length)
                if (start >= end) null else toSpan(highlight, start, end)
            }
            clamped.distinctBy { it.start to it.end }
        } catch (_: RuntimeException) {
            // A tokenizer edge case must degrade to plain text, never crash the note.
            emptyList()
        }
    }

    private fun toSpan(highlight: CodeHighlight, start: Int, end: Int): CodeSpan {
        return when (highlight) {
            is ColorHighlight -> CodeSpan(start, end, highlight.rgb and RGB_MASK)
            is BoldHighlight -> CodeSpan(start, end, rgb = 0, bold = true)
        }
    }
}