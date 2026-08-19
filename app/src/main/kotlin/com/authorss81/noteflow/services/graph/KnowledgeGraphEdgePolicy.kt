package com.authorss81.noteflow.services.graph

/**
 * R2-b2b5-FEA-01 (phase-152) — single pure-JVM decision table for the Knowledge
 * Graph's rendered edge set.
 *
 * The pre-fix graph built edges from the ENTIRE vault (`wikiEdges.map {
 * GraphEdge(...) }`), culled NODES only, and iterated the unculled edge list
 * every draw frame (`for (edge in edges)` + a second pulse loop). A ~2,000-page
 * interlinked vault produced ~10⁶ edges → ~10⁶ drawLine/tag-split operations
 * per frame (frozen UI / near-ANR), and the parser's whole-edge-set
 * `edgeList.distinct()` materialization pinned it all in memory.
 *
 * This policy owns:
 *  - the EDGE WORK BUDGET (scaled to the node cap by tier, mirroring
 *    [GraphTierSelector]'s node caps);
 *  - [cullEdgesToSurvivors]: keep only edges whose BOTH endpoints survived the
 *    node cull, dedupe, then retain the top-K by endpoint recency (the most
 *    recently-updated pair wins; deterministic tie-break by ids).
 *
 * Everything is pure JVM + deterministic so the cap is unit-testable and a
 * crafted vault can never freeze the graph.
 */
object KnowledgeGraphEdgePolicy {

    /** Edge budget for the rendered/drawn set on the DEFAULT (>= mid) tier. */
    const val MAX_EDGES_DEFAULT = 1000
    /** Edge budget for the MID-RANGE tier. */
    const val MAX_EDGES_MID = 600
    /** Edge budget for the LOW-END tier. */
    const val MAX_EDGES_LOW = 300

    /**
     * Edge budget for a tier+node-count, mirroring [GraphTierSelector.profileFor]
     * (the same "low-end OR nodeCount > 240 → full profile" split) so the edge
     * work tracks the physics workload it feeds.
     */
    fun edgeCapFor(lowEnd: Boolean, nodeCount: Int): Int = when {
        lowEnd -> MAX_EDGES_LOW
        nodeCount > 240 -> MAX_EDGES_DEFAULT
        else -> MAX_EDGES_MID
    }

    /**
     * Culls [edges] to the set that can actually be drawn:
     *  1. drop any edge whose source OR target is not a [survivorIds] node
     *     (edges referencing culled pages are dead work in every per-frame loop);
     *  2. drop self-loops;
     *  3. deterministic dedup (stable order);
     *  4. if more than [maxEdges] remain, keep the top-K by the most-recent
     *     endpoint `updatedAt` (shared pairs are ranked by the LATER endpoint —
     *     the freshest, most "alive" links survive), then by source/target id so
     *     the result is fully deterministic for equal timestamps.
     *
     * Returns an empty list for an empty survivor set (nothing can be drawn).
     */
    fun cullEdgesToSurvivors(
        edges: List<GraphEdgeRef>,
        survivorIds: Set<String>,
        pageUpdatedAt: Map<String, Long>,
        maxEdges: Int
    ): List<GraphEdgeRef> {
        if (edges.isEmpty() || survivorIds.isEmpty() || maxEdges <= 0) return emptyList()
        val kept = ArrayList<GraphEdgeRef>(minOf(edges.size, maxEdges))
        for (e in edges) {
            if (e.sourceId !in survivorIds) continue
            if (e.targetId !in survivorIds) continue
            if (e.sourceId == e.targetId) continue
            kept.add(e)
        }
        val dedup = kept.distinct()
        if (dedup.size <= maxEdges) return dedup
        return dedup
            .map { e -> e to maxOf(pageUpdatedAt[e.sourceId] ?: 0L, pageUpdatedAt[e.targetId] ?: 0L) }
            .sortedWith(
                compareByDescending<Pair<GraphEdgeRef, Long>> { it.second }
                    .thenBy { it.first.sourceId }
                    .thenBy { it.first.targetId }
            )
            .take(maxEdges)
            .map { it.first }
    }
}