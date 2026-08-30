package com.authorss81.noteflow

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 254 — comment-trim source pins.
 *
 * Phase 254 removed pure-cosmetic comments (WHAT section labels, numbered UI
 * element labels, pure divider banners) and collapsed blank-line runs in three
 * large UI files: AnnotationCanvas.kt, EditorScreen.kt and HomeScreen.kt.
 *
 * These pins guard the hard invariants of a comment-only change:
 *  1. Every file has FEWER raw lines than at the phase-254 parent (real comment
 *     removal — the baselines are the verified pre-trim counts at `d703831^`).
 *  2. Every file has the SAME number of executable ("code") lines as at the
 *     parent — i.e. only comment/blank lines were removed, ZERO code was lost.
 *     (Code lines here = lines that are neither blank nor a full-line `//`
 *     comment.)
 *  3. The WHY/provenance markers that make the comment density legitimate were
 *     NOT deleted — for every real provenance marker the post-trim count must be
 *     >= the pre-trim count (the counts below are the verified parent counts).
 *  4. No KDoc opening delimiter (the two-char star-sequence that starts a KDoc
 *     block) was deleted — the post-trim count of that opener must equal the
 *     parent count for each file.
 *  5. No pure divider banner (a line that is ONLY `// ===...` / `// ---...` /
 *     `// ~~~...`) remains.
 *  6. No run of 2+ consecutive blank lines remains (the PROMPT's "at most 1
 *     consecutive blank line" target).
 *
 * NOTE on the PROMPT's literals: the PROMPT listed markers (`fail-closed`,
 * `phase-240`, `phase-242`, `phase-250`, `phase-238`, `phase-252`, `phase-22`)
 * that do NOT exist in these three files — they were absent at the parent too
 * (verified via `git show d703831^`), so nothing of that form was deleted. The
 * pins below therefore assert the REAL provenance markers present in each file,
 * whose counts are verified identical pre/post trim.
 */
class Phase254CommentTrimTest {

    // Parent-of-phase-254 baseline raw line counts (measured at 32bbfe8 / d703831^).
    private val headRaw = mapOf(
        "ui/components/AnnotationCanvas.kt" to 8479,
        "ui/screens/EditorScreen.kt" to 7386,
        "ui/screens/HomeScreen.kt" to 3762
    )
    // Parent baseline code-line counts (non-blank, non-full-`//` lines).
    private val headCode = mapOf(
        "ui/components/AnnotationCanvas.kt" to 6852,
        "ui/screens/EditorScreen.kt" to 6422,
        "ui/screens/HomeScreen.kt" to 3267
    )
    // Parent baseline KDoc `/**` opener counts (no KDoc opener may be deleted).
    private val headKdocOpeners = mapOf(
        "ui/components/AnnotationCanvas.kt" to 22,
        "ui/screens/EditorScreen.kt" to 20,
        "ui/screens/HomeScreen.kt" to 3
    )
    // Parent baseline counts of each REAL provenance marker per file.
    // Format: rel-path -> (marker -> parent count). Assert current >= parent.
    private val headProvenanceMarkers = mapOf(
        "ui/components/AnnotationCanvas.kt" to mapOf(
            "R2-b2b" to 8,
            "phase-228" to 2,
            "phase-196" to 4,
            "phase-150" to 8,
            "phase-198" to 2
        ),
        "ui/screens/EditorScreen.kt" to mapOf(
            "R2-b2b" to 6,
            "fail-closed" to 2,
            "phase-49" to 7,
            "phase-141" to 3,
            "phase-150" to 2
        ),
        "ui/screens/HomeScreen.kt" to mapOf(
            "R2-b2b" to 3,
            "phase-09" to 3,
            "phase-96" to 10,
            "phase-138" to 6,
            "phase-143" to 4
        )
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

    private fun countOccurrences(text: String, needle: String): Int = text.split(needle).size - 1

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
    fun `no WHY or provenance marker was deleted from any trimmed file`() {
        headProvenanceMarkers.forEach { (rel, markers) ->
            val src = mainSource(rel)
            markers.forEach { (marker, parentCount) ->
                val cur = countOccurrences(src, marker)
                assertTrue(
                    "$rel: provenance marker '$marker' was reduced (parent=$parentCount, cur=$cur) " +
                        "— a WHY comment was likely deleted",
                    cur >= parentCount
                )
            }
        }
    }

    @Test
    fun `no KDoc opening comment was deleted from any trimmed file`() {
        headKdocOpeners.forEach { (rel, parentOpeners) ->
            val src = mainSource(rel)
            val cur = countOccurrences(src, "/**")
            assertEquals(
                "$rel: KDoc openers must match the parent count ($parentOpeners)",
                parentOpeners,
                cur
            )
        }
    }

    @Test
    fun `no pure divider banner survives and blank-line runs stay at most 1`() {
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
            assertTrue("$rel: no run of 2+ consecutive blank lines (was $maxBlank)", maxBlank <= 1)
        }
    }
}
