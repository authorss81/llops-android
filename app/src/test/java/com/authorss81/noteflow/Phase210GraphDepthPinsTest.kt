package com.authorss81.noteflow

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 210 — wiring pins for the Knowledge Graph depth pass: the previously
 * dead [GraphSubgraphFilter] import is now live through the focus pipeline,
 * search auto-pans + Enter-cycles, and the canvas has a TalkBack semantics
 * bridge. Behavior lives in `GraphNeighborhoodFocusPolicyTest` +
 * `GraphSearchMatchPolicyTest`.
 */
class Phase210GraphDepthPinsTest {

    private fun mainSource(rel: String): String {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            val d: File = dir
            File(d, "src/main/kotlin/com/authorss81/noteflow/$rel").takeIf { it.isFile }?.let { return it.readText() }
            File(d, "src/main/kotlin/$rel").takeIf { it.isFile }?.let { return it.readText() }
            File(d, "app/src/main/kotlin/com/authorss81/noteflow/$rel").takeIf { it.isFile }?.let { return it.readText() }
            dir = d.parentFile
        }
        throw AssertionError("could not locate app/src/main/kotlin/$rel from ${start.path}")
    }

    // ---------- Task 1: neighborhood focus ----------

    @Test
    fun `focus pipeline routes edge scoping through GraphSubgraphFilter`() {
        val policy = mainSource("services/graph/GraphNeighborhoodFocusPolicy.kt")
        assertTrue(
            "the focused edge set must reuse GraphSubgraphFilter.edgesWithin (one both-endpoints rule)",
            policy.contains("GraphSubgraphFilter.edgesWithin(")
        )
    }

    @Test
    fun `the graph screen consumes the focus pipeline instead of leaving the filter dead`() {
        val screen = mainSource("ui/screens/KnowledgeGraphScreen.kt")
        assertTrue(
            "selection must flow through GraphNeighborhoodFocusPolicy.focus",
            screen.contains("GraphNeighborhoodFocusPolicy.focus(selectedNodeId, focusEnabled, graphEdgeRefs, focusHops)")
        )
        assertTrue(
            "focus mode must draw only the focused edges, full set otherwise",
            screen.contains("val drawnEdges: List<GraphEdgeRef> =\n                            focusResult?.focusedEdges ?: graphEdgeRefs")
        )
        assertTrue(
            "out-of-focus nodes must ride the EXISTING dimming pipeline (pageFiltered)",
            Regex("isFilteredOut\\(node\\.page\\) \\|\\| outOfFocus\\(id\\)").containsMatchIn(screen)
        )
    }

    @Test
    fun `selected-node card carries Focus slash Clear-focus plus persisted hop chips`() {
        val screen = mainSource("ui/screens/KnowledgeGraphScreen.kt")
        val card = screen.indexOf("// Legend / floating node info.")
        assertTrue(card > 0)
        assertTrue(
            "a Focus button engages focus mode",
            screen.contains("TextButton(onClick = { focusEnabled = true })")
        )
        assertTrue(
            "a Clear focus button disengages it",
            screen.contains("TextButton(onClick = { focusEnabled = false })")
        )
        assertTrue(
            "hop chips persist through SettingsManager",
            screen.contains("viewModel.settings.graphFocusHopCount = hops")
        )
        val chipBlock = screen.indexOf("listOf(1, 2, 3).forEach { hops ->", card)
        val backlinksButton = screen.indexOf("backlinksTargetId = node.page.id", card)
        assertTrue(chipBlock > 0 && backlinksButton > 0)
    }

    @Test
    fun `SettingsManager persists the hop depth sanitized on read AND write`() {
        val src = mainSource("services/SettingsManager.kt")
        assertTrue(src.contains("\"graph_focus_hop_count\""))
        assertEquals(
            "both accessors must sanitize through GraphNeighborhoodFocusPolicy.sanitizeHops",
            2,
            Regex("GraphNeighborhoodFocusPolicy\\.sanitizeHops").findAll(src).count()
        )
    }

    @Test
    fun `hop depth defaults to 1 and is capped by the policy`() {
        val policy = mainSource("services/graph/GraphNeighborhoodFocusPolicy.kt")
        assertTrue(policy.contains("const val DEFAULT_HOPS = 1"))
        assertTrue(policy.contains("const val MAX_HOPS = 3"))
    }

    // ---------- Task 2: search auto-pan ----------

    @Test
    fun `search results auto-pan to the active match at the current zoom`() {
        val screen = mainSource("ui/screens/KnowledgeGraphScreen.kt")
        assertTrue(
            "the pan effect must key on the active match id",
            screen.contains("LaunchedEffect(activeMatchId)")
        )
        assertTrue(
            "pan targets must come from the pure-JVM policy",
            screen.contains("GraphSearchMatchPolicy.panToCenter(")
        )
    }

    @Test
    fun `Enter cycles matches and never inserts a newline`() {
        val screen = mainSource("ui/screens/KnowledgeGraphScreen.kt")
        val field = screen.indexOf("OutlinedTextField(")
        assertTrue(field > 0)
        val handler = screen.indexOf(".onPreviewKeyEvent { e ->", field)
        assertTrue("the search field owns the Enter handler", handler > 0)
        assertTrue(
            "Enter cycles via the policy (main + numpad)",
            screen.contains("(e.key == Key.Enter || e.key == Key.NumPadEnter)") &&
                screen.contains("GraphSearchMatchPolicy.nextIndex(matchIndex, matchIds.size)")
        )
    }

    @Test
    fun `matches are ranked by the pure-JVM policy in node order`() {
        val screen = mainSource("ui/screens/KnowledgeGraphScreen.kt")
        assertTrue(
            screen.contains(
                "GraphSearchMatchPolicy.orderedMatches(\n            searchQuery,\n            nodes.map { it.page.id to it.page.title }"
            )
        )
    }

    // ---------- Task 3: TalkBack bridge ----------

    @Test
    fun `decorative canvas is emptied out of the semantics tree`() {
        val screen = mainSource("ui/screens/KnowledgeGraphScreen.kt")
        val canvas = screen.indexOf("Canvas(")
        assertTrue(canvas > 0)
        val cleared = screen.indexOf(".clearAndSetSemantics { }", canvas)
        assertTrue("the raw Canvas must be marked decorative", cleared > canvas)
        assertTrue(
            "clearing happens before any overlay composes",
            cleared < screen.indexOf("GraphSemanticNodeOverlay(", canvas)
        )
    }

    @Test
    fun `overlay nodes announce title, connections and the open action`() {
        val screen = mainSource("ui/screens/KnowledgeGraphScreen.kt")
        assertTrue(
            screen.contains("contentDescription = \"Note \${node.page.title}, \$connectionCount connections\"")
        )
        assertTrue(screen.contains("onClick(label = \"Open note\")"))
        assertTrue(screen.contains("role = Role.Button"))
    }

    @Test
    fun `overlay targets are semantics-only - they can never steal touches`() {
        val screen = mainSource("ui/screens/KnowledgeGraphScreen.kt")
        val start = screen.indexOf("private fun GraphSemanticNodeOverlay(")
        assertTrue(start > 0)
        val body = screen.substring(start)
        assertFalse(
            "no pointer-consuming modifier may guard an a11y target",
            Regex("\\.clickable|\\.pointerInput|\\.toggleable").containsMatchIn(body)
        )
        assertTrue(body.contains(".offset {"))
        assertTrue(body.contains(".size(sideDp)"))
        assertTrue(body.contains(".semantics {"))
    }

    @Test
    fun `overlay coverage caps at 50 most-connected nodes then re-sorts stably`() {
        val screen = mainSource("ui/screens/KnowledgeGraphScreen.kt")
        assertTrue(screen.contains("private const val SEMANTIC_NODE_CAP = 50"))
        assertTrue(screen.contains(".take(SEMANTIC_NODE_CAP)"))
        assertTrue(
            "traversal order must be stable across physics relayouts (title sort after the cap)",
            screen.indexOf(".sortedWith(compareBy({ it.page.title.lowercase() }, { it.page.id }))") >
                screen.indexOf(".take(SEMANTIC_NODE_CAP)")
        )
    }

    @Test
    fun `backlinks inspector is composed from the selected-node card`() {
        val screen = mainSource("ui/screens/KnowledgeGraphScreen.kt")
        val call = screen.indexOf("BacklinksInspectorBottomSheet(")
        assertTrue(call > 0)
        assertTrue(
            "it must be an actual composition site, not just the import line",
            call > screen.indexOf("import com.authorss81.noteflow.ui.components.BacklinksInspectorBottomSheet")
        )
        assertTrue(screen.contains("var backlinksTargetId by remember { mutableStateOf<String?>(null) }"))
    }
}
