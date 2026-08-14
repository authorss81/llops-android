package com.authorss81.noteflow.plugins.outline

import com.authorss81.noteflow.plugins.OutlineStyle

/**
 * PURE JVM outline/checklist generator core for the Outline & Checklist plugin
 * (Phase 26). Takes a selection/note and produces a structured Markdown outline
 * or a checkbox checklist. Deterministic, no ML, no network. Grouping/indent
 * logic is unit-tested.
 */
object OutlineGeneratorCore {

    /** Strips leading Markdown list/heading decoration from a raw line. */
    private val decorationRegex = Regex("""^(\s*)(#{1,6}\s+|\d+[.)]\s+|[-*+]\s+|>\s+)*""")

    private fun plainLine(line: String): String = decorationRegex.replace(line, "").trim()

    /**
     * Generate [style] from [text].
     * @return the generated Markdown, or null when there is nothing to structure.
     */
    fun generate(text: String, style: OutlineStyle): String? {
        val lines = text.lines().map { it.trim() }
        if (lines.none { it.isNotEmpty() }) return null

        return when (style) {
            OutlineStyle.CHECKLIST -> buildChecklist(lines)
            OutlineStyle.OUTLINE -> buildOutline(lines)
        }
    }

    private fun buildChecklist(lines: List<String>): String {
        val out = mutableListOf<String>()
        lines.filter { it.isNotEmpty() }.forEach { raw ->
            val trimmed = raw.trim()
            val text = plainLine(trimmed)
            // Pass an already-checkboxed line through untouched.
            if (Regex("""^[-*+]\s*\[[ xX]\]""").containsMatchIn(trimmed)) {
                out.add(trimmed)
            } else if (text.isNotEmpty()) {
                out.add("- [ ] $text")
            }
        }
        return out.joinToString("\n")
    }

    private fun buildOutline(lines: List<String>): String {
        // Group consecutive non-blank lines into blocks.
        val blocks = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        lines.forEach { raw ->
            if (raw.isEmpty()) {
                if (current.isNotEmpty()) {
                    blocks.add(current.toList())
                    current = mutableListOf()
                }
            } else {
                current.add(raw)
            }
        }
        if (current.isNotEmpty()) blocks.add(current.toList())

        val out = mutableListOf<String>()
        blocks.forEach { block ->
            val cleaned = block.map { plainLine(it) }.filter { it.isNotEmpty() }
            if (cleaned.isEmpty()) return@forEach
            out.add(buildString {
                append("## ").append(cleaned.first())
                cleaned.drop(1).forEach { line -> append("\n- ").append(line) }
            })
        }
        // Blank line between sections keeps the Markdown readable.
        return out.joinToString("\n\n")
    }
}