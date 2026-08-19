package com.authorss81.noteflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 152 (R2-b2b5-FEA-01/05/06) — source-level wiring pins for the
 * feature-data bounds. The pure-JVM decision logic is exercised behaviorally in
 * [services.graph.KnowledgeGraphEdgePolicyTest], [services.graph.CommandPaletteMathTest]
 * and [WaveformPeakMathTest]; the Android-bound wiring (the JSONArray parse in
 * NoteRepository, the Compose canvas, the graph screen) is pinned here at source
 * level, mirroring the B2Dos03VoiceRecordingTest pattern:
 *
 *  - the rendered knowledge-graph edge set is built ONLY over survivor pairs via
 *    `KnowledgeGraphEdgePolicy.cullEdgesToSurvivors` (and the physics `edgeRefs`
 *    come from the SAME culled list, never the whole vault);
 *  - the per-frame tag filter is memoized per page (no re-split per edge);
 *  - the palette index lowercases the corpus once (fields on PaletteDoc) — no
 *    per-keystroke re-lowercase;
 *  - waveform non-finite samples are filtered at PARSE time
 *    (`NoteRepository.parseWaveformJson` both paths) and the renderer clamps via
 *    `WaveformPeakMath.renderAmp`.
 */
class Phase152FeatureDataBoundsWiringTest {

    // ---------- R2-b2b5-FEA-01: knowledge graph ----------

    @Test
    fun `the graph edge set is culled to survivor pairs with a top K cap`() {
        val source = readKnowledgeGraphScreen()
        assertTrue(
            "edges must be culled via KnowledgeGraphEdgePolicy.cullEdgesToSurvivors",
            source.contains("KnowledgeGraphEdgePolicy.cullEdgesToSurvivors(")
        )
        assertTrue(
            "the edge budget must be the tiered top-K cap",
            source.contains("KnowledgeGraphEdgePolicy.edgeCapFor(lowEnd, kept.size)")
        )
        assertTrue(
            "the physics edgeRefs must come from the SAME culled edge list",
            source.contains("val edgeRefs = culledEdges")
        )
        assertFalse(
            "the whole-vault `wikiEdges.map { GraphEdge(...) }` materialization must be gone",
            source.contains("val graphEdges = wikiEdges.map { GraphEdge")
        )
        assertFalse(
            "edgeRefs must never be derived from the unculled whole-vault edge list",
            source.contains("val edgeRefs = graphEdges.map { GraphEdgeRef")
        )
    }

    @Test
    fun `the per frame tag filter is memoized per page not per edge`() {
        val source = readKnowledgeGraphScreen()
        assertTrue(
            "the draw block must memoize the filter verdict per page",
            source.contains("val filteredById = HashMap<String, Boolean>(nodes.size)")
        )
        assertTrue(
            "the edge loop must consult the memoized verdict",
            source.contains("fun pageFiltered(id: String, node: GraphNode)")
        )
        assertFalse(
            "the edge loop must not re-split tags per edge via isFilteredOut(src.page)",
            source.contains("val srcFiltered = isFilteredOut(src.page)")
        )
    }

    @Test
    fun `the edge index build is per page capped and total capped without whole set distinct`() {
        val source = readWikiLinkParser()
        assertTrue("the per-page link cap constant must exist",
            source.contains("MAX_LINKS_PER_PAGE"))
        assertTrue("the total-edge budget constant must exist",
            source.contains("MAX_TOTAL_EDGES"))
        assertTrue(
            "dedup must move inline (HashSet during discovery)",
            source.contains("val edgeSet = HashSet<WikiLinkEdge>()")
        )
        assertFalse(
            "the whole-edge-set `.distinct()` materialization must be gone",
            source.contains("edgeList.distinct()")
        )
        assertTrue(
            "extractWikiLinks must cap at construction",
            source.contains("if (out.size >= MAX_LINKS_PER_PAGE) break")
        )
    }

    // ---------- R2-b2b5-FEA-05: command palette ----------

    @Test
    fun `the palette index lowercases the corpus once and rank never re-lowercases`() {
        val source = readCommandPaletteMath()
        assertTrue("PaletteDoc must precompute lowerTitle",
            source.contains("val lowerTitle: String = title.lowercase()"))
        assertTrue("PaletteDoc must precompute lowerBody",
            source.contains("val lowerBody: String = body.lowercase()"))
        assertFalse("score must not re-lowercase the title",
            source.contains("doc.title.lowercase()"))
        assertFalse("score must not re-lowercase the body",
            source.contains("doc.body.lowercase()"))
        assertFalse("the per-keystroke lowerLog cache must be gone",
            source.contains("lowerLog"))
    }

    // ---------- R2-b2b5-FEA-06: waveform ----------

    @Test
    fun `waveform parse filters non finite samples on both parse paths`() {
        val source = readNoteRepository()
        assertTrue(
            "the JSONArray path must route each sample through finiteOrZero",
            source.contains("WaveformPeakMath.finiteOrZero(arr.getDouble(index).toFloat())")
        )
        assertTrue(
            "the fallback split path must route each sample through finiteOrZero",
            source.contains(".map { WaveformPeakMath.finiteOrZero(it) }")
        )
        assertFalse(
            "the raw getDouble toFloat without the gate must be gone",
            source.contains("List(n) { index -> arr.getDouble(index).toFloat() }")
        )
    }

    @Test
    fun `the playback renderer clamps through renderAmp not bare coerceIn`() {
        val source = readAudioPlaybackCard()
        assertTrue(
            "the bar amplitude must route through WaveformPeakMath.renderAmp",
            source.contains("val amp = WaveformPeakMath.renderAmp(amplitudes[i])")
        )
    }

    // ---------- source readers ----------

    private fun readKnowledgeGraphScreen(): String =
        readSource("ui/screens/KnowledgeGraphScreen.kt")

    private fun readWikiLinkParser(): String =
        readSource("services/WikiLinkParser.kt")

    private fun readCommandPaletteMath(): String =
        readSource("services/graph/CommandPaletteMath.kt")

    private fun readNoteRepository(): String =
        readSource("data/repository/NoteRepository.kt")

    private fun readAudioPlaybackCard(): String =
        readSource("ui/components/AudioPlaybackCard.kt")

    private fun readSource(relative: String): String {
        val file = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/$relative")
        assertTrue("$relative must exist for the wiring pin", file.isFile)
        // Comment-stripped so a prose KDoc that *describes* a removed pattern
        // (e.g. "the old `edgeList.distinct()` is gone") can never trip a pin.
        return stripComments(file.readText())
    }

    /** Removes `//` line comments and `/* ... */` block comments before pinning. */
    private fun stripComments(source: String): String {
        val sb = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                c == '/' && i + 1 < source.length && source[i + 1] == '/' -> {
                    while (i < source.length && source[i] != '\n') i++
                }
                c == '/' && i + 1 < source.length && source[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < source.length && !(source[i] == '*' && source[i + 1] == '/')) i++
                    i += 2
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    private fun repoRoot(): java.io.File {
        val cwd = java.io.File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (java.io.File(dir, "gradle/libs.versions.toml").isFile &&
                java.io.File(dir, "app").isDirectory
            ) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}
