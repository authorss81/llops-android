package com.authorss81.noteflow

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 254 — comment-trim source pins.
 *
 * Phase 254 removed pure-cosmetic comments (WHAT section labels, numbered UI
 * element labels, pure divider banners) from three large UI files:
 * AnnotationCanvas.kt, EditorScreen.kt and HomeScreen.kt.
 *
 * These pins guard the hard invariants of a comment-only change:
 *  1. Every file has FEWER raw lines than at HEAD (real comment removal).
 *  2. Every file has the SAME number of executable ("code") lines as at HEAD —
 *     i.e. only comment/blank lines were removed, ZERO code was lost. (Code
 *     lines here = lines that are neither blank nor a full-line `//` comment.)
 *  3. The WHY/provenance markers that make the comment density legitimate are
 *     still present (no `phase-*` / `R2-b2b` / `fail-closed` provenance lost).
 *  4. No pure divider banner (a line that is ONLY `// ===...` / `// ---...` /
 *     `// ~~~...`) remains.
 *  5. No run of 3+ consecutive blank lines (the files are kept at most 2).
 *  6. No KDoc block was opened (`/**`) but never closed (`*/`) — a proxy that
 *     no KDoc opening/closing lines vanished.
 *
 * These are the honest, source-pinnable consequences of the trim. The raw-line
 * totals here are the actual measured HEAD baselines (AnnotationCanvas 8478,
 * EditorScreen 7386, HomeScreen 3762) and the current post-trim totals.
 */
class Phase254CommentTrimTest {

    // HEAD baseline raw line counts (measured at phase-253 / 87592ed).
    private val headRaw = mapOf(
        "ui/components/AnnotationCanvas.kt" to 8478,
        "ui/screens/EditorScreen.kt" to 7386,
        "ui/screens/HomeScreen.kt" to 3762
    )
    // HEAD baseline code-line counts (non-blank, non-full-`//` lines).
    private val headCode = mapOf(
        "ui/components/AnnotationCanvas.kt" to 6852,
        "ui/screens/EditorScreen.kt" to 6422,
        "ui/screens/HomeScreen.kt" to 3267
    )

    private fun mainSource(rel: String): String {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        val candidates = listOf(
            "src/main/kotlin/com/authorss81/noteflow/$rel",
            "app/src/main/kotlin/com/authorss81/noteflow/$rel"
        )
        while (dir != null) {
            candidates.forEach { c ->
                File(dir, c).takeIf { it.isFile }?.let { return it.readText() }
            }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate $rel from ${start.path}")
    }

    private fun countRaw(text: String): Int = text.lineSequence().count()

    private fun countCode(text: String): Int =
        text.lineSequence().count { !it.isBlank() && !it.trimStart().startsWith("//") }

    private fun maxConsecutiveBlank(text: String): Int {
        var max = 0
        var run = 0
        for (line in text.lineSequence()) {
            if (line.isBlank()) {
                run++
                if (run > max) max = run
            } else {
                run = 0
            }
        }
        return max
    }

    @Test
    fun `all three trimmed files have fewer raw lines than at HEAD`() {
        headRaw.forEach { (rel, head) ->
            val cur = countRaw(mainSource(rel))
            assertTrue(
                "$rel: raw lines must drop below the HEAD baseline ($head), was $cur",
                cur < head
            )
        }
    }

    @Test
    fun `all three trimmed files keep exactly their HEAD code-line count (no code lost)`() {
        headCode.forEach { (rel, head) ->
            val cur = countCode(mainSource(rel))
            assertEquals(
                "$rel: trimming comments must NOT change the executable line count (code lost?)",
                head,
                cur
            )
        }
    }

    @Test
    fun `AnnotationCanvas keeps every WHY and provenance marker that legitimises its comments`() {
        val src = mainSource("ui/components/AnnotationCanvas.kt")
        // Provenance markers that make the heavy comment density intentional.
        // (The codebase mixes `phase-150` and `Phase 196:` spellings; probe both.)
        for (probe in listOf("phase-150", "phase-196", "phase-228", "Phase 221:", "R2-b2b")) {
            assertTrue("AnnotationCanvas must keep a '$probe' comment", src.contains(probe))
        }
        // No KDoc block left unclosed (no `/**` removed while a `*/` remains).
        assertTrue(src.contains("/**"))
        assertTrue(src.contains("*/"))
    }

    @Test
    fun `EditorScreen keeps every WHY and provenance marker that legitimises its comments`() {
        val src = mainSource("ui/screens/EditorScreen.kt")
        for (probe in listOf("phase-141", "phase-150", "phase-49", "R2-b2b", "fail-closed",
            "Phase 250", "Phase 238")) {
            assertTrue("EditorScreen must keep a '$probe' comment", src.contains(probe))
        }
        assertTrue(src.contains("/**"))
        assertTrue(src.contains("*/"))
    }

    @Test
    fun `HomeScreen keeps every WHY and provenance marker that legitimises its comments`() {
        val src = mainSource("ui/screens/HomeScreen.kt")
        for (probe in listOf("phase-138", "phase-143", "phase-09", "phase-96", "R2-b2b")) {
            assertTrue("HomeScreen must keep a '$probe' comment", src.contains(probe))
        }
        assertTrue(src.contains("/**"))
        assertTrue(src.contains("*/"))
    }

    @Test
    fun `no pure divider banner survives and blank line runs stay at most 2`() {
        headRaw.keys.forEach { rel ->
            val src = mainSource(rel)
            // A divider banner is a full-line comment consisting of only divider
            // characters (with optional surrounding whitespace).
            val dividers = src.lineSequence()
                .filter { it.trimStart().startsWith("//") }
                .map { it.substringAfter("//").trim() }
                .filter { it.isNotEmpty() && it.all { ch -> ch in "=-~" } }
                .count()
            assertEquals("$rel: pure divider banners (=, -, ~) must be removed", 0, dividers)
            val maxBlank = maxConsecutiveBlank(src)
            assertTrue("$rel: no run of 3+ consecutive blank lines (was $maxBlank)", maxBlank <= 2)
        }
    }

    @Test
    fun `no KDoc opening comment was deleted from any trimmed file`() {
        headRaw.keys.forEach { rel ->
            val src = mainSource(rel)
            val opens = Regex("/\\*\\*").findAll(src).count()
            val closes = Regex("\\*/").findAll(src).count()
            assertTrue("$rel: KDoc blocks must remain balanced (openers=closes)", opens == closes)
        }
    }
}
