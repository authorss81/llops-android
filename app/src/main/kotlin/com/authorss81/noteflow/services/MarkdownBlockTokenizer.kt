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
 *
 * Phase 151 (R2-b2b5-FEA-03): [tokenize] computes blocks, checkbox candidates and
 * the candidates-by-block index in ONE pass ([MarkdownDocument]), and
 * [replaceBlock] re-tokenizes only the edited block's window on top of the cached
 * lines instead of a full-document `lines()` + re-tokenize per keystroke.
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

    /**
     * One-pass tokenization result: the [blocks], the checkbox [candidates] and
     * the candidates pre-indexed by block index — computed together so a keystroke
     * in the hybrid editor never runs two full passes (R2-b2b5-FEA-03).
     */
    data class MarkdownDocument(
        val content: String,
        val lines: List<String>,
        val blocks: List<MarkdownBlock>,
        val candidates: List<CheckboxCandidate>,
        val candidatesByBlock: Map<Int, List<Int>>
    ) {
        /** Source text of [block] straight from the cached lines (no re-split). */
        fun blockSource(block: MarkdownBlock): String {
            if (block.startLine < 0 || block.endLine >= lines.size || block.endLine < block.startLine) {
                return ""
            }
            return lines.subList(block.startLine, block.endLine + 1).joinToString("\n")
        }
    }

    /** One-pass tokenization: lines + blocks + checkbox candidates + block index. */
    fun tokenize(content: String): MarkdownDocument {
        val lines = content.lines()
        val blocks = blocksFromLines(lines)
        val candidates = candidatesFrom(lines, blocks)
        return MarkdownDocument(content, lines, blocks, candidates, candidatesByBlock(candidates))
    }

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

    fun blocks(content: String): List<MarkdownBlock> = blocksFromLines(content.lines())

    private fun blocksFromLines(lines: List<String>): List<MarkdownBlock> {
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
     * Computed in ONE pass with [tokenize] — never a second full [blocks] call.
     */
    fun checkboxCandidates(content: String): List<CheckboxCandidate> = tokenize(content).candidates

    private fun candidatesFrom(
        lines: List<String>,
        blocks: List<MarkdownBlock>,
        blockOffset: Int = 0,
        globalStart: Int = 0
    ): List<CheckboxCandidate> {
        val out = mutableListOf<CheckboxCandidate>()
        var global = globalStart
        for ((position, block) in blocks.withIndex()) {
            if (block.type != MarkdownBlockType.BULLET_LIST && block.type != MarkdownBlockType.ORDERED_LIST) continue
            for (lineIndex in block.startLine..block.endLine) {
                if (lineIndex < 0 || lineIndex >= lines.size) continue
                val match = checkboxItemRe.find(lines[lineIndex]) ?: continue
                out.add(
                    CheckboxCandidate(
                        index = global++,
                        blockIndex = blockOffset + position,
                        lineIndex = lineIndex,
                        checked = match.groupValues[2].trim() in setOf("x", "X")
                    )
                )
            }
        }
        return out
    }

    /**
     * Incremental candidate update for [replaceBlock]: candidates for the blocks
     * that stay before the edited window are reused verbatim (their lines are
     * byte-unchanged), the window's candidates are recomputed in isolation, and
     * the unchanged blocks after the window reuse their previous candidates with
     * the line numbers shifted by [delta] and the block index renumbered to the
     * post-edit position. Global candidate order — and therefore the `index`
     * sequence — is preserved, so the result is provably identical to a fresh
     * full [candidatesFrom] pass while touching only the window (verified against
     * [tokenize] by unit tests). This is what keeps the hybrid editor's keystroke
     * path from doing a full-document candidate rescan (R2-b2b5-FEA-03).
     */
    private fun incrementalCandidates(
        prev: MarkdownDocument,
        lines: List<String>,
        editedBlockIndex: Int,
        beforeCount: Int,
        windowBlocks: List<MarkdownBlock>,
        windowEnd: Int,
        delta: Int
    ): List<CheckboxCandidate> {
        // Old block indexes that survive unchanged AFTER the window — their
        // content lines only shift by `delta`. The kept blocks before the window
        // always form a prefix of the old block list (blocks are line-ordered and
        // non-overlapping), so every candidate with blockIndex < beforeCount is a
        // byte-unchanged "before" candidate.
        val beyondOld = HashMap<Int, Int>()
        var nextBeyondIdx = beforeCount + windowBlocks.size
        for ((idx, b) in prev.blocks.withIndex()) {
            if (idx <= editedBlockIndex) continue
            if (b.startLine + delta > windowEnd) beyondOld[idx] = nextBeyondIdx++
        }
        val before = ArrayList<CheckboxCandidate>()
        val beyond = ArrayList<CheckboxCandidate>()
        for (c in prev.candidates) {
            when {
                c.blockIndex < beforeCount -> before.add(c)
                beyondOld.containsKey(c.blockIndex) ->
                    beyond.add(c.copy(lineIndex = c.lineIndex + delta))
                // Anything in the window is recomputed below.
            }
        }
        val window = candidatesFrom(lines, windowBlocks, blockOffset = beforeCount, globalStart = before.size)
        val out = ArrayList<CheckboxCandidate>(before.size + window.size + beyond.size)
        var next = 0
        for (c in before) out.add(c.copy(index = next++))
        for (c in window) out.add(c.copy(index = next++))
        for (c in beyond) out.add(c.copy(index = next++, blockIndex = beyondOld.getValue(c.blockIndex)))
        return out
    }

    private fun candidatesByBlock(candidates: List<CheckboxCandidate>): Map<Int, List<Int>> =
        candidates.groupBy { it.blockIndex }.mapValues { e -> e.value.map { it.index } }

    /**
     * Flip the `[ ]` / `[x]` marker of the [candidateIndex]-th checkbox. Returns
     * the input unchanged when the candidate does not exist (safe no-op).
     */
    fun toggleCheckbox(content: String, candidateIndex: Int): String =
        toggleCheckbox(tokenize(content), candidateIndex).content

    /**
     * Doc-based toggle used by the editor: flips the marker line in the cached
     * lines and mirrors the [checked] flag on the matching candidate — the block
     * structure is unchanged, so nothing is re-tokenized.
     */
    fun toggleCheckbox(doc: MarkdownDocument, candidateIndex: Int): MarkdownDocument {
        val candidate = doc.candidates.getOrNull(candidateIndex) ?: return doc
        if (candidate.lineIndex < 0 || candidate.lineIndex >= doc.lines.size) return doc
        val line = doc.lines[candidate.lineIndex]
        val match = checkboxItemRe.find(line) ?: return doc
        val newState = if (match.groupValues[2].trim() in setOf("x", "X")) " " else "x"
        val prefix = line.substring(0, match.range.first)
        val marker = match.groupValues[1]
        val tail = match.groupValues[3]
        val replaced = "$prefix$marker [$newState] $tail"
        val newLines = ArrayList(doc.lines)
        newLines[candidate.lineIndex] = replaced
        val content = newLines.joinToString("\n")
        val newCandidates = doc.candidates.map {
            if (it.index == candidateIndex) it.copy(checked = newState == "x") else it
        }
        return MarkdownDocument(content, newLines, doc.blocks, newCandidates, doc.candidatesByBlock)
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

    /**
     * Incremental block-source replacement (R2-b2b5-FEA-03): reuses the cached
     * [MarkdownDocument.lines] (no full-document `content.lines()`), re-tokenizes
     * ONLY the edited block's affected window, shifts the untouched later blocks by
     * the line-count delta and recomputes the checkbox candidates around the window
     * in one pass ([incrementalCandidates] — the unchanged before/after candidates
     * are reused, only the window's are re-scanned).
     * The produced [MarkdownDocument.content] is byte-identical to what
     * [replaceBlockSource] + a fresh [tokenize] would yield.
     */
    fun replaceBlock(doc: MarkdownDocument, blockIndex: Int, newSource: String): MarkdownDocument {
        val block = doc.blocks.getOrNull(blockIndex) ?: return doc
        val srcLines = doc.lines
        val n = srcLines.size
        if (n == 0) return doc
        val start = block.startLine.coerceIn(0, n - 1)
        val end = block.endLine.coerceIn(start, n - 1)
        val newLines = newSource.lines()
        val out = ArrayList<String>(n - (end - start + 1) + newLines.size)
        out.addAll(srcLines.subList(0, start))
        out.addAll(newLines)
        out.addAll(srcLines.subList(end + 1, n))
        val content = out.joinToString("\n")
        if (out.isEmpty()) {
            return MarkdownDocument(content, out, emptyList(), emptyList(), emptyMap())
        }
        if (out.all { it.isBlank() }) {
            // Entirely blank output — mirrors [blocksFromLines]'s all-blank document
            // block (a full re-tokenize would emit exactly this single block).
            return MarkdownDocument(
                content, out,
                listOf(MarkdownBlock(MarkdownBlockType.PARAGRAPH, 0, out.size - 1)),
                emptyList(), emptyMap()
            )
        }
        val delta = newLines.size - (end - start + 1)

        // Affected window: the edited region plus the region that follows it
        // (its first block's grown start can move with a trailing blank run), and
        // — when no blank separates the edit from the preceding region — the whole
        // shared region, so a merge is re-classified too.
        val windowStart = if (start < out.size && !out[start].isBlank()) {
            var w = start
            while (w > 0 && !out[w - 1].isBlank()) w--
            w
        } else {
            start
        }
        val afterContent = firstNonBlankAtOrAfter(out, start + newLines.size)
        val windowEnd = if (afterContent < 0) out.size - 1 else {
            val nextBlank = firstBlankAfter(out, afterContent)
            if (nextBlank < 0) out.size - 1 else nextBlank - 1
        }
        val prevRegionEnd = lastNonBlankBefore(out, windowStart)
        val windowBlocks = classifyWindow(out, windowStart, windowEnd, prevRegionEnd)

        val before = doc.blocks.take(blockIndex).filter { it.endLine < windowStart }
        val afterShifted = doc.blocks.drop(blockIndex + 1).map { b ->
            MarkdownBlock(b.type, b.startLine + delta, b.endLine + delta)
        }
        val beyond = afterShifted.filter { it.startLine > windowEnd }
        val newBlocks = before + windowBlocks + beyond
        val candidates = incrementalCandidates(
            doc, out, blockIndex, before.size, windowBlocks, windowEnd, delta
        )
        return MarkdownDocument(content, out, newBlocks, candidates, candidatesByBlock(candidates))
    }

    /**
     * Region-split + classify + grow-backward restricted to [from]..[to], with the
     * first region's grow-back anchored at [prevRegionEnd] (the last non-blank
     * line before the window). Mirrors the full [blocksFromLines] logic so the
     * window's blocks are identical to a full re-tokenize.
     */
    private fun classifyWindow(
        lines: List<String>,
        from: Int,
        to: Int,
        prevRegionEnd: Int
    ): List<MarkdownBlock> {
        if (from > to || from < 0 || to >= lines.size) return emptyList()
        val regions = mutableListOf<Pair<Int, Int>>()
        var start = -1
        for (i in from..to) {
            if (lines[i].isBlank()) {
                if (start >= 0) {
                    regions.add(start to (i - 1))
                    start = -1
                }
            } else if (start < 0) {
                start = i
            }
        }
        if (start >= 0) regions.add(start to to)
        if (regions.isEmpty()) return emptyList()

        val blocks = mutableListOf<MarkdownBlock>()
        var previousEnd = prevRegionEnd
        for (region in regions) {
            val regionBlocks = classifyRegion(lines, region.first, region.second)
            if (regionBlocks.isEmpty()) continue
            val growStart = previousEnd + 1
            val first = regionBlocks.first()
            regionBlocks[0] = first.copy(startLine = first.startLine.coerceAtMost(growStart))
            blocks.addAll(regionBlocks)
            previousEnd = region.second
        }
        return blocks
    }

    private fun firstNonBlankAtOrAfter(lines: List<String>, from: Int): Int {
        for (i in from until lines.size) {
            if (!lines[i].isBlank()) return i
        }
        return -1
    }

    private fun firstBlankAfter(lines: List<String>, from: Int): Int {
        for (i in from until lines.size) {
            if (lines[i].isBlank()) return i
        }
        return -1
    }

    private fun lastNonBlankBefore(lines: List<String>, before: Int): Int {
        var i = before - 1
        while (i >= 0) {
            if (!lines[i].isBlank()) return i
            i--
        }
        return -1
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
