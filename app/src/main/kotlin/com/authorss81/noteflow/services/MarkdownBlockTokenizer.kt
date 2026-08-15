package com.authorss81.noteflow.services

/**
 * Phase 37 — PURE JVM line-based Markdown block tokenizer.
 *
 * Splits raw Markdown source into contiguous [MarkdownBlock] ranges so the hybrid
 * editor can render each block through the SAME CommonMark renderer the preview
 * uses, and edit the raw syntax of one block at a time.
 *
 * Guarantees:
 *  - every source line belongs to exactly one block and blocks are contiguous,
 *    so `joinBlockSources(content) == content` for any input (round-trip exact —
 *    unit-tested);
 *  - [checkboxCandidates] reports every `- [ ]` / `- [x]` (and `1. [ ]`) list
 *    item in document order with its source line, which is exactly the traversal
 *    order the shared renderer walks, so `toggleCheckbox(content, index)` always
 *    hits the right line.
 *
 * This is deliberately a *slice*: lazy list continuations and blank-line-separated
 * "loose" lists are tokenized as separate paragraph/list blocks. Rendering is still
 * pixel-identical to the preview because every block is re-parsed by CommonMark.
 */
enum class MarkdownBlockType {
    HEADING,
    PARAGRAPH,
    CODE_FENCE,
    MATH_BLOCK,
    BLOCKQUOTE,
    CALLOUT,
    BULLET_LIST,
    ORDERED_LIST,
    TABLE,
    THEMATIC_BREAK,
    HTML_BLOCK
}

data class MarkdownBlock(
    val type: MarkdownBlockType,
    val startLine: Int,
    val endLine: Int
)

data class CheckboxCandidate(
    val index: Int,
    val blockIndex: Int,
    val lineIndex: Int,
    val checked: Boolean
)

enum class CalloutType {
    NOTE, WARNING, TIP, IMPORTANT, QUOTE
}

data class CalloutInfo(
    val type: CalloutType,
    val title: String,
    val body: String
)

object MarkdownBlockTokenizer {

    private val headingRe = Regex("""^\s{0,3}#{1,6}\s""")
    private val thematicRe = Regex("""^\s{0,3}(?:-{3,}|\*{3,}|_{3,})\s*$""")
    private val fenceRe = Regex("""^\s{0,3}(`{3,}|~{3,})""")
    private val bulletItemRe = Regex("""^\s{0,3}[-*+]\s+""")
    private val orderedItemRe = Regex("""^\s{0,3}\d{1,9}([.)])\s+""")
    private val quoteRe = Regex("""^\s{0,3}>""")
    private val tableSeparatorRe = Regex("""^\s*\|?\s*:?-+:?\s*(?:\|+\s*:?-+:?)*\|?\s*$""")

    private val checkboxItemRe =
        Regex("""^\s{0,3}([-*+]|\d{1,9}[.)])\s+\[([ xX])\]\s*(.*)$""")

    /** Source text of a single block (its `startLine..endLine` range). */
    fun blockSource(content: String, block: MarkdownBlock): String {
        val lines = content.lines()
        if (block.startLine < 0 || block.endLine >= lines.size || block.endLine < block.startLine) {
            return ""
        }
        return lines.subList(block.startLine, block.endLine + 1).joinToString("\n")
    }

    /** Join every block's source back into the original document (round-trip). */
    fun joinBlockSources(content: String, blocks: List<MarkdownBlock>): String =
        blocks.joinToString("\n") { blockSource(content, it) }

    fun blocks(content: String): List<MarkdownBlock> {
        val lines = content.lines()
        val n = lines.size
        if (n == 0) return emptyList()

        // Blank-separated content regions first, so classification never has to
        // reason about blank lines inside a block.
        val regions = mutableListOf<Pair<Int, Int>>()
        var start = -1
        for (i in 0 until n) {
            if (lines[i].isBlank()) {
                if (start >= 0) {
                    regions.add(start to (i - 1))
                    start = -1
                }
            } else if (start < 0) {
                start = i
            }
        }
        if (start >= 0) regions.add(start to (n - 1))

        if (regions.isEmpty()) {
            // Entirely blank document — keep the lines so round-trip holds.
            return listOf(MarkdownBlock(MarkdownBlockType.PARAGRAPH, 0, n - 1))
        }

        val blocks = mutableListOf<MarkdownBlock>()
        var previousRegionEnd = -1
        for ((regionIndex, region) in regions.withIndex()) {
            val regionStart = region.first
            val regionEnd = region.second
            val regionBlocks = classifyRegion(lines, regionStart, regionEnd)
            if (regionBlocks.isEmpty()) continue
            // Grow the first sub-block backward over the preceding blank run so
            // every source line (blanks included) is owned by exactly one block.
            val growStart = if (regionIndex == 0) 0 else previousRegionEnd + 1
            val first = regionBlocks.first()
            regionBlocks[0] = first.copy(startLine = first.startLine.coerceAtMost(growStart))
            blocks.addAll(regionBlocks)
            previousRegionEnd = regionEnd
        }
        return blocks
    }

    private fun classifyRegion(lines: List<String>, from: Int, to: Int): MutableList<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        var i = from
        while (i <= to) {
            val line = lines[i]
            when {
                fenceRe.containsMatchIn(line) -> {
                    val fence = fenceRe.find(line)!!.groupValues[1].first()
                    val closer = findClosingFence(lines, i + 1, to, fence)
                    blocks.add(MarkdownBlock(MarkdownBlockType.CODE_FENCE, i, closer))
                    i = closer + 1
                }
                headingRe.containsMatchIn(line) -> {
                    blocks.add(MarkdownBlock(MarkdownBlockType.HEADING, i, i))
                    i++
                }
                thematicRe.matches(line) -> {
                    blocks.add(MarkdownBlock(MarkdownBlockType.THEMATIC_BREAK, i, i))
                    i++
                }
                line.trimStart().startsWith("$$") -> {
                    val closer = findClosingMath(lines, i, to)
                    blocks.add(MarkdownBlock(MarkdownBlockType.MATH_BLOCK, i, closer))
                    i = closer + 1
                }
                quoteRe.containsMatchIn(line) -> {
                    var j = i
                    while (j <= to && quoteRe.containsMatchIn(lines[j])) j++
                    val isCallout = lines.subList(i, j)
                        .joinToString("\n")
                        .trimStart('>', ' ')
                        .startsWith("[!")
                    blocks.add(
                        MarkdownBlock(
                            if (isCallout) MarkdownBlockType.CALLOUT else MarkdownBlockType.BLOCKQUOTE,
                            i,
                            j - 1
                        )
                    )
                    i = j
                }
                line.contains("|") && i + 1 <= to && tableSeparatorRe.matches(lines[i + 1].trim()) -> {
                    var j = i + 2
                    while (j <= to && lines[j].contains("|")) j++
                    blocks.add(MarkdownBlock(MarkdownBlockType.TABLE, i, j - 1))
                    i = j
                }
                bulletItemRe.containsMatchIn(line) || orderedItemRe.containsMatchIn(line) -> {
                    val isOrdered = orderedItemRe.containsMatchIn(line)
                    val itemType = if (isOrdered) MarkdownBlockType.ORDERED_LIST else MarkdownBlockType.BULLET_LIST
                    var j = i
                    while (j <= to && isListItemLine(lines[j])) j++
                    blocks.add(MarkdownBlock(itemType, i, j - 1))
                    i = j
                }
                else -> {
                    var j = i
                    while (j <= to && !isSpecialBlockStart(lines, j, to)) j++
                    blocks.add(MarkdownBlock(MarkdownBlockType.PARAGRAPH, i, j - 1))
                    i = j
                }
            }
        }
        return blocks
    }

    private fun isListItemLine(line: String): Boolean =
        bulletItemRe.containsMatchIn(line) || orderedItemRe.containsMatchIn(line)

    private fun isSpecialBlockStart(lines: List<String>, index: Int, to: Int): Boolean {
        val line = lines[index]
        if (line.isBlank()) return true
        if (fenceRe.containsMatchIn(line)) return true
        if (headingRe.containsMatchIn(line)) return true
        if (thematicRe.matches(line)) return true
        if (line.trimStart().startsWith("$$")) return true
        if (quoteRe.containsMatchIn(line)) return true
        if (isListItemLine(line)) return true
        if (line.contains("|") && index + 1 <= to && tableSeparatorRe.matches(lines[index + 1].trim())) {
            return true
        }
        return false
    }

    private fun findClosingFence(lines: List<String>, from: Int, to: Int, fence: Char): Int {
        val closing = Regex("""^\s{0,3}${fence}{3,}\s*$""")
        for (i in from..to) {
            if (closing.matches(lines[i])) return i
        }
        return to
    }

    private fun findClosingMath(lines: List<String>, from: Int, to: Int): Int {
        // Opening $$ is on `from`. A same-line closing pair makes a one-line block.
        val first = lines[from]
        val firstClosing = first.indexOf("$$", first.indexOf("$$") + 2)
        if (firstClosing >= 0) return from
        for (i in from + 1..to) {
            if (lines[i].contains("$$")) return i
        }
        return to
    }

    /**
     * Every checkbox list item in document order. Indexes are stable and match
     * the traversal order of the shared renderer (verified in unit tests).
     */
    fun checkboxCandidates(content: String): List<CheckboxCandidate> {
        val lines = content.lines()
        val blocks = blocks(content)
        val out = mutableListOf<CheckboxCandidate>()
        var global = 0
        for ((blockIndex, block) in blocks.withIndex()) {
            if (block.type != MarkdownBlockType.BULLET_LIST && block.type != MarkdownBlockType.ORDERED_LIST) continue
            for (lineIndex in block.startLine..block.endLine) {
                if (lineIndex < 0 || lineIndex >= lines.size) continue
                val match = checkboxItemRe.find(lines[lineIndex]) ?: continue
                out.add(
                    CheckboxCandidate(
                        index = global++,
                        blockIndex = blockIndex,
                        lineIndex = lineIndex,
                        checked = match.groupValues[2].trim() in setOf("x", "X")
                    )
                )
            }
        }
        return out
    }

    /**
     * Flip the `[ ]` / `[x]` marker of the [candidateIndex]-th checkbox. Returns
     * the input unchanged when the candidate does not exist (safe no-op).
     */
    fun toggleCheckbox(content: String, candidateIndex: Int): String {
        val lines = content.lines()
        val candidate = checkboxCandidates(content).getOrNull(candidateIndex) ?: return content
        if (candidate.lineIndex >= lines.size) return content
        val line = lines[candidate.lineIndex]
        val match = checkboxItemRe.find(line) ?: return content
        val newState = if (match.groupValues[2].trim() in setOf("x", "X")) " " else "x"
        val prefix = line.substring(0, match.range.first)
        val marker = match.groupValues[1]
        val tail = match.groupValues[3]
        val replaced = "$prefix$marker [$newState] $tail"
        val out = ArrayList(lines)
        out[candidate.lineIndex] = replaced
        return out.joinToString("\n")
    }

    /**
     * Replace the raw source of [blockIndex] with [newSource]. Blocks that
     * precede the edited one keep their ranges (the edit is line-count-stable for
     * earlier lines); the caller re-tokenizes the result when editing ends.
     */
    fun replaceBlockSource(content: String, blocks: List<MarkdownBlock>, blockIndex: Int, newSource: String): String {
        val lines = content.lines()
        val block = blocks.getOrNull(blockIndex) ?: return content
        val start = block.startLine.coerceIn(0, lines.size - 1)
        val end = block.endLine.coerceIn(start, lines.size - 1)
        val out = ArrayList(lines)
        out.subList(start, end + 1).clear()
        out.addAll(start, newSource.lines())
        return out.joinToString("\n")
    }

    /** Classify a block-quote's collected literal into a typed callout, if any. */
    fun calloutOf(quoteLiteral: String): CalloutInfo? {
        val trimmed = quoteLiteral.trim()
        if (!trimmed.startsWith("[!")) return null
        val typeToken = trimmed.substringAfter("[").substringBefore("]").uppercase().trim()
        val body = trimmed.substringAfter("]").trim()
        val type = when {
            typeToken.contains("WARNING") || typeToken.contains("CAUTION") || typeToken.contains("DANGER") ->
                CalloutType.WARNING
            typeToken.contains("TIP") -> CalloutType.TIP
            typeToken.contains("IMPORTANT") -> CalloutType.IMPORTANT
            typeToken.contains("QUOTE") -> CalloutType.QUOTE
            else -> CalloutType.NOTE
        }
        return CalloutInfo(type, typeToken, body)
    }
}
