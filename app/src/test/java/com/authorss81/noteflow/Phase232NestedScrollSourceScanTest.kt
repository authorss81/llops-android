package com.authorss81.noteflow

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 232 (2026-08-28): compile-time source scan that flags the nested-scroll
 * anti-pattern — the Detekt-rule equivalent for a repo with no Detekt.
 *
 * `CheckScrollableContainerConstraints` throws "Vertically scrollable component was
 * measured with infinity maximum height constraints" when a vertical scrollable is
 * measured with infinite maxHeight. Compose applies Modifier elements in ORDER, so a
 * height bound (heightIn/fillMaxHeight/fillMaxSize/weight) placed AFTER verticalScroll
 * in the same chain does **not** bound the scroll's measure:
 *
 *     Modifier.verticalScroll(s).heightIn(max = 400.dp)  // bound is INNER, too late
 *     Modifier.heightIn(max = 400.dp).verticalScroll(s)  // bound is OUTER, safe
 *
 * This suite walks every `app/src/main/kotlin` file and asserts:
 *   1. No modifier chain places a height bound AFTER its verticalScroll element —
 *      except inside a finite bound-provider call (AlertDialog / BasicAlertDialog /
 *      Dialog / ModalBottomSheet / BoxWithConstraints), where a brace-lambda/sheet
 *      parent already bounds the measure.
 *   2. No LazyColumn appears directly nested inside an unbounded verticalScroll
 *      parent. "Directly nested" is decided with BRACE-DEPTH lexical containment
 *      (not a fixed line window): a scrollable ancestor is only the parent when its
 *      enclosing `{` block is still open at the child site. LazyRow /
 *      horizontalScroll are excluded (horizontal containers are never the
 *      vertical-Infinity concern).
 *   3. No height-bound-less verticalScroll chain is nested (same brace-depth rule)
 *      inside another scrollable (verticalScroll or LazyColumn) outside a
 *      bound-provider — the phase-229 "nested scrollable without any bound" crash
 *      form.
 *
 * The scan is deliberately shallow (char lexer + line heuristics, like a Detekt
 * rule) — it is a regression canary, not a layout engine. Known simplifications:
 * the brace-depth parenter is purely lexical, so it cannot model runtime windowing
 * (dialog call sites lexically inside a root scroll are exempted via the
 * bound-provider check, and matches rely on the runtime phase-231 guard); the bound
 * vocabulary below is the fixed set the ordering rule cares about; and a chain head
 * is still inferred by Modifier-mention heuristics.
 *
 * Phase-232 review fixes (2026-08-28): the bound vocabulary now also covers the
 * fixed exact-height modifiers `height`/`requiredHeight` (same too-late ordering
 * applies); the lazy/canary nesting checks use brace-depth lexical containment
 * instead of a positional 10-line window (sibling blocks no longer false-positive,
 * deep nesting no longer false-negatives); `isInsideBoundProvider` now requires a
 * brace lambda (a plain parenthesised argument is not a bounded layout surface);
 * and the lexer understands nested block comments.
 */
class Phase232NestedScrollSourceScanTest {

    // ------------------------------------------------------------------
    // Scan engine (pure JVM — reads source text, no Compose/Robolectric)
    // ------------------------------------------------------------------

    internal object NestedScrollSourceScan {

        /**
         * Height bounds that, applied too late, fail to bound the scroll's measure.
         * Includes the fixed exact-height modifiers: a `height(300.dp)` AFTER the
         * scroll is just as ineffective as `heightIn` (it only clamps what the scroll
         * already measured with Infinity).
         */
        internal val BOUND_ELEMENTS: Set<String> =
            setOf("heightIn", "fillMaxHeight", "fillMaxSize", "weight", "height", "requiredHeight")

        /** Call sites whose own constraint system already bounds a scrollable child. */
        internal val BOUND_PROVIDERS: Set<String> =
            setOf("AlertDialog", "BasicAlertDialog", "Dialog", "ModalBottomSheet", "BoxWithConstraints")

        private val VERTICAL_SCROLL = Regex("""\bverticalScroll\s*\(""")
        private val LAZY_COLUMN = Regex("""\bLazyColumn\b""")
        private val ELEMENT = Regex("""\.\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        private val BOUND_WORDS = BOUND_ELEMENTS.map { name -> name to Regex("""\b${Regex.escape(name)}\b""") }

        /** Char-at-index lexer state: whether the index is inside a string/comment. */
        internal class CodeState private constructor(val text: String, val state: ByteArray) {
            fun isCode(pos: Int): Boolean = pos in state.indices && state[pos] == CODE

            companion object {
                private const val CODE = 0.toByte()
                private const val STRING = 1.toByte()
                private const val TRIPLE = 2.toByte()
                private const val CHAR = 3.toByte()
                private const val LINE_COMMENT = 4.toByte()
                private const val BLOCK_COMMENT = 5.toByte()

                fun build(text: String): CodeState {
                    val n = text.length
                    val state = ByteArray(n)
                    var mode = CODE
                    var blockDepth = 0
                    var i = 0
                    while (i < n) {
                        val c = text[i]
                        when (mode) {
                            CODE -> when {
                                c == '"' && i + 2 < n && text[i + 1] == '"' && text[i + 2] == '"' -> {
                                    state[i] = TRIPLE; state[i + 1] = TRIPLE; state[i + 2] = TRIPLE; mode = TRIPLE; i += 3
                                }
                                c == '"' -> { state[i] = STRING; mode = STRING; i++ }
                                c == '\'' -> { state[i] = CHAR; mode = CHAR; i++ }
                                c == '/' && i + 1 < n && text[i + 1] == '/' -> { state[i] = LINE_COMMENT; mode = LINE_COMMENT; i++ }
                                c == '/' && i + 1 < n && text[i + 1] == '*' -> { state[i] = BLOCK_COMMENT; mode = BLOCK_COMMENT; blockDepth = 1; i++ }
                                else -> i++
                            }
                            STRING -> {
                                state[i] = STRING
                                if (c == '\\' && i + 1 < n) { state[i + 1] = STRING; i += 2; continue }
                                if (c == '"') mode = CODE
                                i++
                            }
                            TRIPLE -> {
                                state[i] = TRIPLE
                                if (c == '\\') {
                                    if (i + 1 < n) state[i + 1] = TRIPLE
                                    i += 2
                                } else if (i + 2 < n && text[i] == '"' && text[i + 1] == '"' && text[i + 2] == '"') {
                                    state[i] = TRIPLE; state[i + 1] = TRIPLE; state[i + 2] = TRIPLE
                                    mode = CODE
                                    i += 3
                                } else {
                                    i++
                                }
                            }
                            CHAR -> {
                                state[i] = CHAR
                                if (c == '\\' && i + 1 < n) { state[i + 1] = CHAR; i += 2; continue }
                                if (c == '\'') mode = CODE
                                i++
                            }
                            LINE_COMMENT -> {
                                state[i] = LINE_COMMENT
                                if (c == '\n') mode = CODE
                                i++
                            }
                            BLOCK_COMMENT -> {
                                state[i] = BLOCK_COMMENT
                                // Kotlin block comments nest. An inner `/*` re-enters a
                                // comment level; only the matching outer `*/` exits to CODE.
                                if (c == '/' && i + 1 < n && text[i + 1] == '*') {
                                    state[i + 1] = BLOCK_COMMENT
                                    blockDepth++
                                    i += 2
                                    continue
                                }
                                if (c == '*' && i + 1 < n && text[i + 1] == '/') {
                                    state[i + 1] = BLOCK_COMMENT
                                    blockDepth--
                                    i += 2
                                    if (blockDepth == 0) mode = CODE
                                    continue
                                }
                                i++
                            }
                        }
                    }
                    return CodeState(text, state)
                }
            }
        }

        private val BOUND_PROVIDER_NAMES =
            BOUND_PROVIDERS.map { name -> name to Regex("""\b${Regex.escape(name)}\s*\(""") }

        private fun isCommentOnlyLine(trimmed: String): Boolean =
            trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")

        private fun lineElements(line: String, lineOffset: Int, state: CodeState): List<String> =
            ELEMENT.findAll(line)
                .filter { state.isCode(lineOffset + it.range.first) }
                .map { it.groupValues[1] }
                .toList()

        /**
         * Line range head..end of the modifier chain containing scrollLineIndex.
         * Walk-back: continuation lines start with `.`; a line mentioning `Modifier`
         * (i.e. `modifier = Modifier...`, `Modifier...`, `modifier.<elem>...`) is the
         * chain head. Comment lines are transparent in both directions. A line that
         * starts `val ` / `var ` is a standalone modifier alias, never the head —
         * walking up past it would swallow a DIFFERENT chain's bound tokens.
         */
        internal fun chainLineRange(lines: List<String>, scrollLineIndex: Int): IntRange {
            val n = lines.size
            var head = scrollLineIndex
            var i = scrollLineIndex - 1
            while (i >= 0) {
                val trimmed = lines[i].trim()
                if (isCommentOnlyLine(trimmed)) { i--; continue }
                if (trimmed.startsWith(".")) { head = i; i--; continue }
                if (trimmed.startsWith("val ") || trimmed.startsWith("var ")) break
                if (trimmed.contains("Modifier") || trimmed.startsWith("modifier")) { head = i; break }
                break
            }
            var end = scrollLineIndex
            i = scrollLineIndex + 1
            while (i < n) {
                val trimmed = lines[i].trim()
                if (isCommentOnlyLine(trimmed)) { i++; continue }
                if (trimmed.startsWith(".")) { end = i; i++; continue }
                break
            }
            return head..end
        }

        internal fun chainElements(text: String, state: CodeState, scrollPos: Int): List<String> {
            val lines = text.split("\n")
            val lineStart = lineStartOffsets(lines)
            val scrollLine = lineIndexOf(lineStart, scrollPos)
            val range = chainLineRange(lines, scrollLine)
            return range.flatMap { li -> lineElements(lines[li], lineStart[li], state) }
        }

        /**
         * The bound element placed after verticalScroll in the same chain, if any.
         * Exposed for direct matcher testing.
         */
        internal fun boundElementAfterScroll(elems: List<String>): String? {
            val scroll = elems.indexOf("verticalScroll")
            if (scroll < 0) return null
            return elems.subList(scroll + 1, elems.size).firstOrNull { it in BOUND_ELEMENTS }
        }

        private fun lineStartOffsets(lines: List<String>): IntArray {
            val out = IntArray(lines.size)
            var acc = 0
            for (i in lines.indices) { out[i] = acc; acc += lines[i].length + 1 }
            return out
        }

        private fun lineIndexOf(lineStart: IntArray, pos: Int): Int {
            var lo = 0
            var hi = lineStart.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (lineStart[mid] <= pos && (mid == lineStart.size - 1 || lineStart[mid + 1] > pos)) return mid
                if (lineStart[mid] > pos) hi = mid - 1 else lo = mid + 1
            }
            return 0
        }

        private fun lineHasCodeToken(line: String, lineOffset: Int, state: CodeState, name: String): Boolean =
            Regex("""\b${Regex.escape(name)}\b""").findAll(line)
                .any { state.isCode(lineOffset + it.range.first) }

        /** Whether a source line carries any of the height-bound tokens (word-bounded). */
        internal fun lineHasBound(line: String, lineOffset: Int, state: CodeState): Boolean =
            BOUND_WORDS.any { (_, re) -> re.findAll(line).any { state.isCode(lineOffset + it.range.first) } }

        /**
         * End index (exclusive) of the whole call body for a bound-provider opener,
         * i.e. through its argument parens AND an optional trailing lambda. Null if the
         * call never closes (malformed or beyond budget).
         */
        private fun callBodyEnd(text: String, state: CodeState, openParenPos: Int): Int? {
            var paren = 1
            var brace = 0
            var i = openParenPos + 1
            val n = text.length
            while (i < n) {
                if (state.isCode(i)) {
                    when (text[i]) {
                        '(' -> paren++
                        ')' -> {
                            paren--
                            if (paren == 0 && brace == 0) {
                                val lambda = trailingLambdaOpen(text, state, i + 1)
                                if (lambda < 0) return i
                                brace = 1
                                i = lambda + 1
                                continue
                            }
                        }
                        '{' -> brace++
                        '}' -> {
                            if (brace > 0) brace--
                            if (paren == 0 && brace == 0) return i
                        }
                    }
                }
                i++
            }
            return null
        }

        private fun trailingLambdaOpen(text: String, state: CodeState, from: Int): Int {
            var i = from
            while (i < text.length) {
                if (state.isCode(i) && text[i] == '{') return i
                if (state.isCode(i) && !text[i].isWhitespace()) return -1
                i++
            }
            return -1
        }

        /** Brace-only depth at every code index; used for lexical containment checks. */
        internal fun braceDepthMap(text: String, state: CodeState): IntArray {
            val out = IntArray(text.length)
            var d = 0
            for (i in text.indices) {
                if (state.isCode(i)) {
                    when (text[i]) {
                        '{' -> d++
                        '}' -> if (d > 0) d--
                        else -> {}
                    }
                }
                out[i] = d
            }
            return out
        }

        /**
         * Whether [childPos] is lexically contained in [parentPos]'s open `{` block:
         * the depth at the child is strictly deeper than at the parent, and the
         * parent's depth is never dropped BELOW in between (an off-by-one would be
         * wrong here: the region from the parent up to the child's own opening `{` is
         * naturally at the parent's depth). Put plainly — the leaf is nested in the
         * parent's subtree iff the parent's enclosing block stays open until the
         * child, i.e. no close brace ever takes the depth below the parent's depth.
         * Sibling blocks and already-closed calls (which do dip below) are never
         * parents.
         */
        internal fun nestedUnder(depth: IntArray, parentPos: Int, childPos: Int): Boolean {
            if (childPos <= parentPos) return false
            val parentDepth = depth[parentPos]
            if (depth[childPos] <= parentDepth) return false
            var i = parentPos + 1
            while (i < childPos) {
                if (depth[i] < parentDepth) return false
                i++
            }
            return true
        }

        private fun matchingBrace(text: String, state: CodeState, open: Int, limit: Int): Int? {
            var depth = 0
            var i = open
            while (i < limit) {
                if (state.isCode(i)) {
                    when (text[i]) {
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) return i
                        }
                    }
                }
                i++
            }
            return null
        }

        /**
         * Whether [pos] lies inside a BRACE LAMBDA of a bound-provider call (the
         * `text = { … }` slot of an AlertDialog, the trailing lambda of a
         * ModalBottomSheet, the content lambda of a Dialog, …) — such a slot is
         * parent-bounded, so a height-bound-after-scroll (or a chain with no bound at
         * all) inside it is safe. Positions directly inside the call's plain argument
         * parens (not inside any lambda) are NOT shielded: a plain parenthesised
         * argument is not a bounded layout surface. Hook lambdas
         * (`onDismissRequest = {}`, `confirmButton = {}`) are shielded too — a
         * deliberate over-broad simplification (they are `() -> Unit` hooks, but a
         * scrollable there is pathological; the runtime phase-231 guard still fires).
         */
        internal fun isInsideBoundProvider(text: String, state: CodeState, pos: Int): Boolean {
            for ((_, re) in BOUND_PROVIDER_NAMES) {
                for (m in re.findAll(text)) {
                    if (m.range.first >= pos) break
                    if (!state.isCode(m.range.first)) continue
                    val end = callBodyEnd(text, state, m.range.last) ?: continue
                    if (pos > end) continue
                    var i = m.range.last + 1
                    while (i < end) {
                        if (state.isCode(i) && text[i] == '{') {
                            val close = matchingBrace(text, state, i, end)
                            if (close != null) {
                                if (pos <= close) return true
                                i = close + 1
                            } else return false
                        } else i++
                    }
                }
            }
            return false
        }

        /** Ordering violations: a height bound placed AFTER verticalScroll in one chain. */
        private fun orderingViolations(text: String, state: CodeState, rel: String): List<String> {
            val lines = text.split("\n")
            val lineStart = lineStartOffsets(lines)
            val out = mutableListOf<String>()
            for (m in VERTICAL_SCROLL.findAll(text)) {
                if (!state.isCode(m.range.first)) continue
                val elems = chainElements(text, state, m.range.first)
                if (elems.contains("horizontalScroll")) continue
                val after = boundElementAfterScroll(elems) ?: continue
                if (isInsideBoundProvider(text, state, m.range.first)) continue
                val lineNo = lineIndexOf(lineStart, m.range.first) + 1
                out += "$rel:$lineNo verticalScroll() has height bound '$after' AFTER it in the same " +
                    "Modifier chain; a bound that late does not cap the scroll's measure " +
                    "(put heightIn/fillMaxHeight/fillMaxSize/weight/height BEFORE verticalScroll)."
            }
            return out
        }

        /**
         * The nearest scrollable site (verticalScroll, optionally LazyColumn) that
         * lexically contains [pos] via the brace-depth rule. Null when [pos] is at the
         * same depth as every candidate (or no candidate precedes it) — i.e. it is a
         * root-level / sibling scrollable, not a nested one.
         */
        private fun nearestAncestorScrollable(
            text: String,
            state: CodeState,
            depth: IntArray,
            pos: Int,
            includeLazy: Boolean
        ): Int? {
            val candidates = mutableListOf<Int>()
            for (m in VERTICAL_SCROLL.findAll(text)) {
                if (m.range.first < pos && state.isCode(m.range.first)) candidates += m.range.first
            }
            if (includeLazy) {
                for (m in LAZY_COLUMN.findAll(text)) {
                    if (m.range.first < pos && state.isCode(m.range.first)) candidates += m.range.first
                }
            }
            candidates.sort()
            for (i in candidates.indices.reversed()) {
                if (nestedUnder(depth, candidates[i], pos)) return candidates[i]
            }
            return null
        }

        /** Nested-scroll violations: a LazyColumn lexically contained in an unbounded verticalScroll parent. */
        private fun lazyNestedViolations(text: String, state: CodeState, rel: String, depth: IntArray): List<String> {
            val lines = text.split("\n")
            val lineStart = lineStartOffsets(lines)
            val out = mutableListOf<String>()
            for (m in LAZY_COLUMN.findAll(text)) {
                if (!state.isCode(m.range.first)) continue
                val lazyPos = m.range.first
                val parentScroll = nearestAncestorScrollable(text, state, depth, lazyPos, includeLazy = false)
                    ?: continue
                val range = chainLineRange(lines, lineIndexOf(lineStart, parentScroll))
                val parentBounded = range.any { li -> lineHasBound(lines[li], lineStart[li], state) }
                if (parentBounded) continue
                val lineNo = lineIndexOf(lineStart, lazyPos) + 1
                out += "$rel:$lineNo LazyColumn appears directly nested inside an unbounded verticalScroll " +
                    "parent with no height bound on the scroll chain; bound the parent " +
                    "(heightIn/fillMaxHeight/fillMaxSize/weight START the chain BEFORE verticalScroll) " +
                    "or remove the nesting."
            }
            return out
        }

        /**
         * The phase-229 "nested scrollable without any bound" crash form: a
         * verticalScroll chain with no height bound anywhere that is lexically nested
         * inside another scrollable (verticalScroll or LazyColumn). Chains inside a
         * bound-provider (dialog/sheet window bounds) are exempt — they measure
         * against the window, not the lexical ancestor.
         */
        private fun unboundedNestedViolations(text: String, state: CodeState, rel: String, depth: IntArray): List<String> {
            val lines = text.split("\n")
            val lineStart = lineStartOffsets(lines)
            val out = mutableListOf<String>()
            for (m in VERTICAL_SCROLL.findAll(text)) {
                if (!state.isCode(m.range.first)) continue
                val pos = m.range.first
                if (isInsideBoundProvider(text, state, pos)) continue
                val elems = chainElements(text, state, pos)
                if (elems.contains("horizontalScroll")) continue
                if (elems.any { it in BOUND_ELEMENTS }) continue
                val lineNo = lineIndexOf(lineStart, pos) + 1
                val parentScroll = nearestAncestorScrollable(text, state, depth, pos, includeLazy = true)
                    ?: continue
                out += "$rel:$lineNo verticalScroll() has no height bound anywhere on its modifier chain " +
                    "yet is nested inside another scrollable (verticalScroll/LazyColumn); an inner " +
                    "scrollable is measured with Infinity — put heightIn/fillMaxHeight/fillMaxSize/weight " +
                    "/height BEFORE the verticalScroll, or remove the nesting."
            }
            return out
        }

        internal class ScanKinds(
            val ordering: List<String>,
            val lazy: List<String>,
            val unboundedNested: List<String>
        ) {
            fun all(): List<String> = ordering + lazy + unboundedNested
        }

        internal fun scanKinds(text: String, rel: String): ScanKinds {
            val state = CodeState.build(text)
            val depth = braceDepthMap(text, state)
            return ScanKinds(
                orderingViolations(text, state, rel),
                lazyNestedViolations(text, state, rel, depth),
                unboundedNestedViolations(text, state, rel, depth)
            )
        }

        /** Full scan of one source file. rel = path relative to app/src/main/kotlin. */
        fun scan(text: String, rel: String): List<String> = scanKinds(text, rel).all()

        /** Number of verticalScroll call sites that are in real code (not strings/comments). */
        internal fun codeVerticalScrollCount(text: String): Int {
            val state = CodeState.build(text)
            return VERTICAL_SCROLL.findAll(text).count { state.isCode(it.range.first) }
        }

        /** Number of LazyColumn identifiers that are in real code (not strings/comments). */
        internal fun codeLazyColumnCount(text: String): Int {
            val state = CodeState.build(text)
            return LAZY_COLUMN.findAll(text).count { state.isCode(it.range.first) }
        }
    }

    // ------------------------------------------------------------------
    // Locating the source tree
    // ------------------------------------------------------------------

    private fun mainSourcesRoot(): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            File(dir, "app/src/main/kotlin").takeIf { it.isDirectory }?.let { return it }
            File(dir, "src/main/kotlin").takeIf { it.isDirectory }?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate app/src/main/kotlin from ${start.path}")
    }

    private fun fakeSource(body: String): String = body.trimIndent()

    // ------------------------------------------------------------------
    // Matcher unit tests (prove the detector catches the anti-pattern)
    // ------------------------------------------------------------------

    @Test
    fun `matcher detects the bad verticalScroll-then-heightIn ordering`() {
        val src = fakeSource(
            """
            @Composable
            fun Bad(list: List<String>) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .heightIn(max = 400.dp)
                ) {
                    list.forEach { Text(it) }
                }
            }
            """
        )
        val elems = NestedScrollSourceScan.chainElements(
            src,
            NestedScrollSourceScan.CodeState.build(src),
            src.indexOf("verticalScroll(")
        )
        assertEquals("heightIn", NestedScrollSourceScan.boundElementAfterScroll(elems))
        assertTrue(
            "the bad ordering must be reported as a violation",
            NestedScrollSourceScan.scan(src, "fake/bad.kt").any { it.contains("heightIn") }
        )
    }

    @Test
    fun `matcher does not flag the safe bound-before-scroll ordering`() {
        val src = fakeSource(
            """
            @Composable
            fun Good(list: List<String>) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    list.forEach { Text(it) }
                }
            }
            """
        )
        val elems = NestedScrollSourceScan.chainElements(
            src,
            NestedScrollSourceScan.CodeState.build(src),
            src.indexOf("verticalScroll(")
        )
        assertNull("bound before scroll must not be flagged", NestedScrollSourceScan.boundElementAfterScroll(elems))
        assertTrue("safe ordering must yield no violations", NestedScrollSourceScan.scan(src, "fake/good.kt").isEmpty())
    }

    @Test
    fun `matcher flags weight placed after verticalScroll too`() {
        val src = fakeSource(
            """
            @Composable
            fun WeightAfterScroll() {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f)) {
                    Text("x")
                }
            }
            """
        )
        val elems = NestedScrollSourceScan.chainElements(
            src,
            NestedScrollSourceScan.CodeState.build(src),
            src.indexOf("verticalScroll(")
        )
        assertEquals("weight", NestedScrollSourceScan.boundElementAfterScroll(elems))
    }

    @Test
    fun `matcher flags fixed-height bound placed after verticalScroll too`() {
        val src = fakeSource(
            """
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).height(300.dp)) {
                Text("x")
            }
            """
        )
        val elems = NestedScrollSourceScan.chainElements(
            src,
            NestedScrollSourceScan.CodeState.build(src),
            src.indexOf("verticalScroll(")
        )
        assertEquals("height", NestedScrollSourceScan.boundElementAfterScroll(elems))
        assertTrue(
            "fixed height() after scroll is the same too-late ordering",
            NestedScrollSourceScan.scan(src, "fake/fixed.kt").any { it.contains("'height'") }
        )

        val req = fakeSource(
            """
            Column(modifier = Modifier.requiredHeight(300.dp).verticalScroll(rememberScrollState())) {
                Text("x")
            }
            """
        )
        val reqElems = NestedScrollSourceScan.chainElements(
            req,
            NestedScrollSourceScan.CodeState.build(req),
            req.indexOf("verticalScroll(")
        )
        assertNull(
            "requiredHeight BEFORE scroll is the safe ordering",
            NestedScrollSourceScan.boundElementAfterScroll(reqElems)
        )
        assertTrue(
            "requiredHeight before scroll must yield no violations",
            NestedScrollSourceScan.scan(req, "fake/req.kt").isEmpty()
        )
    }

    @Test
    fun `matcher excludes single-line horizontalScroll chains`() {
        val src = fakeSource(
            """
            Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Row { Text("no vertical concern here") }
            }
            """
        )
        assertTrue(
            "a pure horizontalScroll chain is never a violation",
            NestedScrollSourceScan.scan(src, "fake/horiz.kt").isEmpty()
        )
    }

    @Test
    fun `matcher ignores verticalScroll mentioned inside strings and comments`() {
        val src = fakeSource(
            """
            // NEVER do: Modifier.verticalScroll(s).heightIn(max=400.dp)
            val hint = "use .verticalScroll(rememberScrollState()) .heightIn(max = 400.dp) carefully"
            @Composable
            fun Doc(list: List<String>) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    text = hint
                ) {
                    list.forEach { Text(it) }
                }
            }
            """
        )
        assertTrue(
            "string/comment mentions must not create violations",
            NestedScrollSourceScan.scan(src, "fake/doc.kt").isEmpty()
        )
    }

    @Test
    fun `lexer keeps nested block comments from leaking code tokens`() {
        val src = fakeSource(
            """
            /* phase-232 review fix: /* inner */ verticalScroll(rememberScrollState()) is still outer-comment text */
            @Composable
            fun Clean() {
                Column(modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    Text("safe")
                }
            }
            """
        )
        val state = NestedScrollSourceScan.CodeState.build(src)
        val commented = src.indexOf("verticalScroll", src.indexOf("/* phase-232"))
        val real = src.indexOf("verticalScroll", src.indexOf("heightIn"))
        assertTrue("the commented mention must be masked by the still-open outer comment", !state.isCode(commented))
        assertTrue("the real call site must be code", state.isCode(real))
        assertEquals(
            "exactly one real verticalScroll call site",
            1,
            NestedScrollSourceScan.codeVerticalScrollCount(src)
        )
        assertTrue(
            "the safe chain must yield no violations",
            NestedScrollSourceScan.scan(src, "fake/nestedcmt.kt").isEmpty()
        )
    }

    @Test
    fun `lineHasBound counts fixed-height bounds with word boundaries`() {
        val plain = "Box(Modifier.height(300.dp).fillMaxWidth())"
        assertTrue(
            "height() must count as a height bound",
            NestedScrollSourceScan.lineHasBound(plain, 0, NestedScrollSourceScan.CodeState.build(plain))
        )
        val unrelated = "Text(style = MaterialTheme.typography.bodyMedium)"
        assertFalse(
            "unrelated tokens must not fire the height bound",
            NestedScrollSourceScan.lineHasBound(unrelated, 0, NestedScrollSourceScan.CodeState.build(unrelated))
        )
    }

    @Test
    fun `matcher does not flag the dialog-bound bound-after-scroll`() {
        val src = fakeSource(
            """
            AlertDialog(
                onDismissRequest = {},
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .nestedScrollGuard()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 4.dp)
                            .heightIn(max = 640.dp)
                    ) {
                        Text("the dialog bounds this scroll")
                    }
                },
                confirmButton = {}
            )
            """
        )
        assertTrue(
            "a bound-after-scroll inside an AlertDialog text slot is parent-bounded and safe",
            NestedScrollSourceScan.scan(src, "fake/dialog.kt").isEmpty()
        )
    }

    @Test
    fun `matcher does not flag the sheet-bound bound-after-scroll in a trailing lambda`() {
        val src = fakeSource(
            """
            ModalBottomSheet(onDismissRequest = {}) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 430.dp)
                        .nestedScrollGuard()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("the sheet bounds this scroll")
                }
            }
            """
        )
        assertTrue(
            "a scrollable inside a ModalBottomSheet trailing lambda is sheet-bounded and safe",
            NestedScrollSourceScan.scan(src, "fake/sheet.kt").isEmpty()
        )
    }

    @Test
    fun `matcher detects a LazyColumn nested inside an unbounded verticalScroll`() {
        val src = fakeSource(
            """
            @Composable
            fun NestedLazy(items: List<String>) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("header")
                    LazyColumn { items(items) { Text(it) } }
                }
            }
            """
        )
        assertTrue(
            "an unbounded LazyColumn under verticalScroll must be flagged",
            NestedScrollSourceScan.scan(src, "fake/nested.kt").any { it.contains("LazyColumn") }
        )
    }

    @Test
    fun `matcher does not flag a LazyColumn under a dimension-bounded verticalScroll`() {
        val src = fakeSource(
            """
            @Composable
            fun BoundedLazy(items: List<String>) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("header")
                    LazyColumn { items(items) { Text(it) } }
                }
            }
            """
        )
        assertTrue(
            "a bounded scroll parent makes the nested LazyColumn safe",
            NestedScrollSourceScan.scan(src, "fake/bounded.kt").isEmpty()
        )
    }

    @Test
    fun `brace-depth finds nesting that a fixed 10-line window would have missed`() {
        val src = fakeSource(
            """
            @Composable
            fun DeepNest(items: List<String>) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("a")
                    Text("b")
                    Text("c")
                    Text("d")
                    Text("e")
                    Text("f")
                    Text("g")
                    Text("h")
                    Text("i")
                    Text("j")
                    Text("k")
                    Text("l")
                    Text("m")
                    Text("n")
                    LazyColumn { items(items) { Text(it) } }
                }
            }
            """
        )
        assertTrue(
            "the LazyColumn is >10 lines below its scroll parent yet lexically nested",
            NestedScrollSourceScan.scan(src, "fake/deep.kt").any { it.contains("LazyColumn") }
        )
    }

    @Test
    fun `matcher never flags LazyRow (horizontal-only, never the infinity concern)`() {
        val src = fakeSource(
            """
            @Composable
            fun LazyRowUnderScroll(chips: List<String>) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LazyRow { items(chips) { Text(it) } }
                }
            }
            """
        )
        assertTrue(
            "LazyRow is horizontal-only and must be excluded",
            NestedScrollSourceScan.scan(src, "fake/row.kt").isEmpty()
        )
    }

    @Test
    fun `canary detects a height-bound-less verticalScroll nested under another scroll`() {
        val src = fakeSource(
            """
            @Composable
            fun NestedScrolls(list: List<String>) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("outer header")
                    Column(Modifier.nestedScrollGuard().verticalScroll(rememberScrollState())) {
                        Text("inner")
                    }
                }
            }
            """
        )
        assertTrue(
            "an unbound inner scroll nested under a scroll is the crash signature",
            NestedScrollSourceScan.scan(src, "fake/nestedscrolls.kt").any { it.contains("no height bound") }
        )
    }

    @Test
    fun `canary flags an unbound inner scroll even when the outer scroll is bounded`() {
        val src = fakeSource(
            """
            @Composable
            fun BoundedOuterUnboundInner() {
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Column(Modifier.nestedScrollGuard().verticalScroll(rememberScrollState())) {
                        Text("inner")
                    }
                }
            }
            """
        )
        assertTrue(
            "a scrollable measures its content with Infinity regardless of its own bound, " +
                "so an unbound 2nd-level scroll is still the crash",
            NestedScrollSourceScan.scan(src, "fake/boui.kt").any { it.contains("no height bound") }
        )
    }

    @Test
    fun `canary does not flag a top-level unbound scroll with no scrollable ancestor`() {
        val src = fakeSource(
            """
            @Composable
            fun Standalone(content: String) {
                Column(Modifier.fillMaxWidth().nestedScrollGuard().verticalScroll(rememberScrollState())) {
                    Text(content)
                }
            }
            """
        )
        assertTrue(
            "a lone unbound scroll in a bounded parent is a dead-scroll risk, not a crash",
            NestedScrollSourceScan.scan(src, "fake/standalone.kt").isEmpty()
        )
    }

    @Test
    fun `canary exempts a bound-provider chain even when its call site is lexically under a scroll`() {
        val src = fakeSource(
            """
            @Composable
            fun DialogUnderScroll() {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AlertDialog(
                        onDismissRequest = {},
                        text = {
                            Column(Modifier.nestedScrollGuard().verticalScroll(rememberScrollState())) {
                                Text("dialog content is window-bounded, not scroll-bounded")
                            }
                        },
                        confirmButton = {}
                    )
                }
            }
            """
        )
        assertTrue(
            "a dialog-text scroll is bounded by the dialog window even when the call sits in a root scroll",
            NestedScrollSourceScan.scan(src, "fake/dialunder.kt").isEmpty()
        )
    }

    @Test
    fun `canary treats a LazyColumn ancestor as a scrollable parent`() {
        val src = fakeSource(
            """
            @Composable
            fun ScrollInLazy(items: List<String>) {
                LazyColumn {
                    items(items) { it ->
                        Column(Modifier.nestedScrollGuard().verticalScroll(rememberScrollState())) {
                            Text(it)
                        }
                    }
                }
            }
            """
        )
        assertTrue(
            "a LazyColumn measures items with unbounded height — an unbound scroll item must be flagged",
            NestedScrollSourceScan.scan(src, "fake/lazyitem.kt").any { it.contains("no height bound") }
        )
    }

    @Test
    fun `brace-depth ignores a scroll in a sibling block at the same depth`() {
        val src = fakeSource(
            """
            @Composable
            fun Siblings() {
                Box { Column(Modifier.verticalScroll(rememberScrollState())) { Text("a") } }
                Box { Column(Modifier.nestedScrollGuard().verticalScroll(rememberScrollState())) { Text("b") } }
            }
            """
        )
        assertTrue(
            "a closed sibling scope can never be the parent (the old 10-line window would misfire here)",
            NestedScrollSourceScan.scan(src, "fake/sibling.kt").isEmpty()
        )
    }

    @Test
    fun `matcher is not fooled by a closed earlier AlertDialog`() {
        val src = fakeSource(
            """
            AlertDialog(onDismissRequest = {}, text = { Text("a") }, confirmButton = {})
            @Composable
            fun AfterDialog(list: List<String>) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .heightIn(max = 400.dp)
                ) {
                    list.forEach { Text(it) }
                }
            }
            """
        )
        assertTrue(
            "a previously-closed AlertDialog must not shield a later bad ordering",
            NestedScrollSourceScan.scan(src, "fake/afterdia.kt").any { it.contains("heightIn") }
        )
    }

    // ------------------------------------------------------------------
    // Whole-tree scan (the actual guard)
    // ------------------------------------------------------------------

    private fun scanAllFiles(): List<Pair<String, NestedScrollSourceScan.ScanKinds>> {
        val root = mainSourcesRoot()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .map { file ->
                val rel = root.toPath().relativize(file.toPath()).toString()
                rel to NestedScrollSourceScan.scanKinds(file.readText(), rel)
            }
            .toList()
    }

    @Test
    fun `whole tree has no bound-after-scroll ordering violations`() {
        val ordering = scanAllFiles()
            .flatMap { (rel, kinds) -> kinds.ordering.map { "$rel -> $it" } }
        assertTrue(
            "found verticalScroll() chains whose height bound appears AFTER the scroll:\n" +
                ordering.joinToString("\n"),
            ordering.isEmpty()
        )
    }

    @Test
    fun `whole tree has no LazyColumn nested inside an unbounded verticalScroll`() {
        val lazy = scanAllFiles()
            .flatMap { (rel, kinds) -> kinds.lazy.map { "$rel -> $it" } }
        assertTrue(
            "found LazyColumn nested inside an unbounded verticalScroll parent:\n" +
                lazy.joinToString("\n"),
            lazy.isEmpty()
        )
    }

    @Test
    fun `whole tree has no height-bound-less verticalScroll nested inside another scrollable`() {
        val nested = scanAllFiles()
            .flatMap { (rel, kinds) -> kinds.unboundedNested.map { "$rel -> $it" } }
        assertTrue(
            "found verticalScroll() chains with no height bound nested inside another scrollable:\n" +
                nested.joinToString("\n"),
            nested.isEmpty()
        )
    }

    @Test
    fun `whole-tree scan actually visits the verticalScroll call sites`() {
        // Non-vacuous guard: the scan must be visiting the site inventory (phase-229/230),
        // not silently passing because it matched zero sites. Currently 26 real sites.
        val root = mainSourcesRoot()
        val total = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { NestedScrollSourceScan.codeVerticalScrollCount(it.readText()) }
            .sum()
        assertTrue("expected >= 20 verticalScroll call sites, found $total", total >= 20)
    }

    @Test
    fun `whole-tree scan also visits the LazyColumn sites`() {
        // The LazyColumn guard must not be vacuous either — the tree really uses
        // LazyColumn (e.g. note lists), so the nesting check has something to examine.
        val root = mainSourcesRoot()
        val total = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { NestedScrollSourceScan.codeLazyColumnCount(it.readText()) }
            .sum()
        assertTrue("expected >= 5 LazyColumn sites, found $total", total >= 5)
    }

    @Test
    fun `BrushStudioDialog bound-after-scroll is the documented dialog-bound exception`() {
        // BrushStudioDialog.kt:63-65 places heightIn(max=640.dp) AFTER verticalScroll —
        // exactly the ordering the scan flags, but it is inside the AlertDialog text
        // slot (parent-bounded). Pin the exception explicitly so it can't silently drift.
        val root = mainSourcesRoot()
        val file = root.walkTopDown().firstOrNull { it.name == "BrushStudioDialog.kt" }
        assertNotNull("BrushStudioDialog.kt must exist in the source tree", file)
        val text = file!!.readText()
        val state = NestedScrollSourceScan.CodeState.build(text)
        val scrollPos = text.indexOf("verticalScroll(")
        assertTrue("BrushStudioDialog must still have a verticalScroll", scrollPos >= 0)
        val elems = NestedScrollSourceScan.chainElements(text, state, scrollPos)
        assertEquals(
            "BrushStudioDialog keeps its heightIn AFTER scroll (parent-bounded by AlertDialog)",
            "heightIn",
            NestedScrollSourceScan.boundElementAfterScroll(elems)
        )
        assertTrue(
            "BrushStudioDialog's scroll must sit inside a bound-provider call",
            NestedScrollSourceScan.isInsideBoundProvider(text, state, scrollPos)
        )
        val violations = NestedScrollSourceScan.scan(text, "BrushStudioDialog.kt")
        assertTrue("the dialog exception must keep the site unflagged: $violations", violations.isEmpty())
    }

    @Test
    fun `lineHasBound does not mistake fontWeight for the weight bound`() {
        // \bweight\b must not fire on fontWeight, or a chain whose only "bound" is a
        // fontWeight argument would wrongly suppress the LazyColumn-under-scroll flag.
        val stateful = fakeSource(
            """
            Text(style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            """
        )
        val state = NestedScrollSourceScan.CodeState.build(stateful)
        assertFalse(
            "fontWeight must not count as a height bound",
            NestedScrollSourceScan.lineHasBound(stateful, 0, state)
        )
        val plain = "Box(Modifier.weight(1f))"
        assertTrue(
            "an actual weight() must count as a height bound",
            NestedScrollSourceScan.lineHasBound(plain, 0, NestedScrollSourceScan.CodeState.build(plain))
        )
    }
}