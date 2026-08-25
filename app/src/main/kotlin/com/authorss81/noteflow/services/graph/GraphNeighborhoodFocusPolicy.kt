package com.authorss81.noteflow.services.graph

/**
 * Phase 210 — neighborhood focus for the Knowledge Graph. Selecting a node on a
 * big vault used to leave the whole hairball on screen; focus keeps ONLY the
 * selected node plus its N-hop neighborhood fully visible and dims everything
 * else through the screen's existing dimming pipeline.
 *
 * Pure JVM + deterministic (BFS over the undirected edge list, adjacency built
 * in stable order) so the focused set is unit-testable. The edge scoping of the
 * focused view reuses [GraphSubgraphFilter.edgesWithin] — the same
 * both-endpoints-inside rule the notebook subgraph view uses — so there is ONE
 * implementation of "which edges survive a kept-id set".
 */
object GraphNeighborhoodFocusPolicy {

    /** Default exploration radius: direct neighbors only. */
    const val DEFAULT_HOPS = 1

    /** Upper bound — 4 hops on a hub node can re-create the hairball. */
    const val MAX_HOPS = 3

    /**
     * Defensive frontier cap: bounds ONE focus request so a pathological hub
     * node cannot pull in an unbounded BFS frontier. BFS itself stays O(E+V);
     * this cap exists to protect the render budget on low-RAM devices.
     */
    const val MAX_FOCUSED_NODES = 400

    fun sanitizeHops(hops: Int): Int = when {
        hops < 1 -> DEFAULT_HOPS
        hops > MAX_HOPS -> MAX_HOPS
        else -> hops
    }

    /** The focused-view render inputs a single selection derives from. */
    data class GraphFocusResult(
        /** Selected id + every page within [hops] hops of it. */
        val focusedIds: Set<String>,
        /** Edges whose BOTH endpoints are inside [focusedIds] (deduped, no loops). */
        val focusedEdges: List<GraphEdgeRef>
    )

    /**
     * Full focus pipeline:
     *
     *  1. disabled or no selection → `null` (the caller renders unfiltered);
     *  2. BFS from the selected node over undirected edges, bounded by
     *     [hops] ([sanitizeHops] applied) and [MAX_FOCUSED_NODES];
     *  3. scope the edges to that set via [GraphSubgraphFilter.edgesWithin].
     *
     * A selected node with no incident edges focuses to itself alone — its
     * edges list is empty and every other node dims out.
     */
    fun focus(
        selectedId: String?,
        enabled: Boolean,
        edges: List<GraphEdgeRef>,
        hops: Int
    ): GraphFocusResult? {
        if (!enabled || selectedId.isNullOrBlank()) return null
        if (edges.isEmpty()) {
            return GraphFocusResult(setOf(selectedId), emptyList())
        }
        val depth = sanitizeHops(hops)
        val focused = HashSet<String>()
        focused.add(selectedId)
        var frontier = setOf(selectedId)
        var remaining = MAX_FOCUSED_NODES - 1
        var d = 0
        while (d < depth && frontier.isNotEmpty() && remaining > 0) {
            val next = HashSet<String>()
            for (e in edges) {
                val inside =
                    (e.sourceId in frontier && e.targetId !in frontier) ||
                        (e.targetId in frontier && e.sourceId !in frontier)
                if (!inside) continue
                // The endpoint OUTSIDE the current frontier is one hop further.
                val candidate = if (e.sourceId in frontier) e.targetId else e.sourceId
                if (candidate in focused) continue
                next.add(candidate)
            }
            if (next.size > remaining) {
                // Deterministic trim so a pathological hub can never blow the cap.
                val trimmed = next.sorted().take(remaining)
                focused.addAll(trimmed)
                frontier = trimmed.toSet()
                remaining = 0
            } else {
                focused.addAll(next)
                remaining -= next.size
                frontier = next
            }
            d++
        }
        val focusedEdges = GraphSubgraphFilter.edgesWithin(focused, edges)
        return GraphFocusResult(focusedIds = focused, focusedEdges = focusedEdges)
    }

    /** Non-alarming one-line copy for the focused state. */
    fun focusNotice(focusedCount: Int): String =
        "Focused on $focusedCount connected notes."
}
