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
 *      except inside a finite bound-provider (AlertDialog / Dialog / ModalBottomSheet
 *      / BoxWithConstraints), where the parent already bounds the measure.
 *   2. No LazyColumn appears directly nested inside an unbounded verticalScroll
 *      parent (10-line window heuristic). LazyRow / horizontalScroll are excluded
 *      (horizontal containers are never the vertical-Infinity concern).
 *
 * The scan is deliberately shallow (regex + line heuristics, like a Detekt rule) —
 * it is a regression canary, not a layout engine.
 */
class Phase232NestedScrollSourceScanTest {

    // ------------------------------------------------------------------
    // Scan engine (pure JVM — reads source text, no Compose/Robolectric)
    // ------------------------------------------------------------------

    internal object NestedScrollSourceScan {

        /** Height bounds that, applied too late, fail to bound the scroll's measure. */
        internal val BOUND_ELEMENTS: Set<String> =
            setOf("heightIn", "fillMaxHeight", "fillMaxSize", "weight")

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
                                c == '/' && i + 1 < n && text[i + 1] == '*' -> { state[i] = BLOCK_COMMENT; mode = BLOCK_COMMENT; i++ }
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
                                if (c == '*' && i + 1 < n && text[i + 1] == '/') { state[i + 1] = BLOCK_COMMENT; i += 2; mode = CODE; continue }
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
         * chain head. Comment lines are transparent in both directions.
         */
        internal fun chainLineRange(lines: List<String>, scrollLineIndex: Int): IntRange {
            val n = lines.size
            var head = scrollLineIndex
            var i = scrollLineIndex - 1
            while (i >= 0) {
                val trimmed = lines[i].trim()
                if (isCommentOnlyLine(trimmed)) { i--; continue }
                if (trimmed.startsWith(".")) { head = i; i--; continue }
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

        /**
         * Whether [pos] lies inside the body (argument parens OR trailing lambda) of a
         * bound-provider call — such a parent already bounds the scroll's measure.
         */
        internal fun isInsideBoundProvider(text: String, state: CodeState, pos: Int): Boolean {
            for ((_, re) in BOUND_PROVIDER_NAMES) {
                for (m in re.findAll(text)) {
                    if (m.range.first >= pos) break
                    if (!state.isCode(m.range.first)) continue
                    val end = callBodyEnd(text, state, m.range.last)
                    if (end != null && pos <= end) return true
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
                    "(put heightIn/fillMaxHeight/fillMaxSize/weight BEFORE verticalScroll)."
            }
            return out
        }

        /** Nested-scroll violations: a LazyColumn directly under an unbounded verticalScroll parent. */
        private fun lazyNestedViolations(text: String, state: CodeState, rel: String): List<String> {
            val lines = text.split("\n")
            val lineStart = lineStartOffsets(lines)
            val out = mutableListOf<String>()
            for (m in LAZY_COLUMN.findAll(text)) {
                if (!state.isCode(m.range.first)) continue
                val lazyLine = lineIndexOf(lineStart, m.range.first)
                val windowStart = maxOf(0, lazyLine - 10)
                var scrollLine = -1
                for (li in windowStart until lazyLine) {
                    if (lineHasCodeToken(lines[li], lineStart[li], state, "verticalScroll")) { scrollLine = li; break }
                }
                if (scrollLine < 0) continue
                val range = chainLineRange(lines, scrollLine)
                val parentBounded = range.any { li -> lineHasBound(lines[li], lineStart[li], state) }
                if (parentBounded) continue
                val lineNo = lazyLine + 1
                out += "$rel:$lineNo LazyColumn appears directly nested inside an unbounded verticalScroll " +
                    "parent (10-line window) with no height bound on the scroll chain; bound the parent " +
                    "(heightIn/weight/fillMaxHeight/fillMaxSize BEFORE verticalScroll) or remove the nesting."
            }
            return out
        }

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

        /** Full scan of one source file. rel = path relative to app/src/main/kotlin. */
        fun scan(text: String, rel: String): List<String> {
            val state = CodeState.build(text)
            return orderingViolations(text, state, rel) + lazyNestedViolations(text, state, rel)
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

    private fun scanSourceTree(): List<String> {
        val root = mainSourcesRoot()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .flatMap { file ->
                val rel = root.toPath().relativize(file.toPath()).toString()
                NestedScrollSourceScan.scan(file.readText(), rel)
            }
            .toList()
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

    @Test
    fun `whole tree has no bound-after-scroll ordering violations`() {
        val violations = scanSourceTree()
        assertTrue(
            "found verticalScroll() chains whose height bound appears AFTER the scroll:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun `whole tree has no LazyColumn nested inside an unbounded verticalScroll`() {
        val root = mainSourcesRoot()
        val lazyViolations = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .flatMap { file ->
                val rel = root.toPath().relativize(file.toPath()).toString()
                val text = file.readText()
                val state = NestedScrollSourceScan.CodeState.build(text)
                NestedScrollSourceScan.scan(text, rel).filter { it.contains("LazyColumn") }.map { "$rel -> $it" }
            }
            .toList()
        assertTrue(
            "found LazyColumn nested inside an unbounded verticalScroll parent:\n" +
                lazyViolations.joinToString("\n"),
            lazyViolations.isEmpty()
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
        // LazyColumn (e.g. note lists), so the 10-line window has something to check.
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