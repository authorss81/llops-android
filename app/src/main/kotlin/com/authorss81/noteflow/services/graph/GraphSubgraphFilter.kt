package com.authorss81.noteflow.services.graph

/**
 * Phase 154 — pure-JVM decision table for the Knowledge Graph's *subgraph*
 * scope. The graph used to cull the WHOLE vault down to one 120-400-node
 * "most recent" window; on a genuinely large vault that window mixes a dozen
 * notebooks together and hides most of any single notebook. This policy owns:
 *
 *  - **notebook scoping** ([filterPagesByNotebook] / [sectionIdsForNotebook]):
 *    keep only the pages whose section belongs to one notebook, so a focused
 *    subgraph survives the cull instead of being swallowed by the global cap;
 *  - **edge scoping** ([edgesWithin]): keep only edges whose BOTH endpoints are
 *    inside the scoped page set (cross-notebook edges drop out of a notebook
 *    view), deduplicated, no self-loops;
 *  - **isolation detection** ([isolatedPageIds]): the subgraph's pages with
 *    ZERO surviving edges — the "isolated" toggle target;
 *  - **full pipeline** ([apply]): scope → cull-cap → edges-within → isolation,
 *    returning the single [GraphSubgraphResult] the UI renders from.
 *
 * Everything is pure JVM + deterministic so the caps are unit-testable and a
 * crafted vault can never freeze the graph. The tier/node-cap numbers stay in
 * [GraphTierSelector] / [KnowledgeGraphEdgePolicy]; this file only *scopes* the
 * inputs those policies consume.
 */
object GraphSubgraphFilter {

    /** The chip label for "no notebook filter" (whole vault). */
    const val ALL_NOTES_LABEL = "All Notes"

    /** Light page projection — enough to scope a node without Room/UI types. */
    data class GraphSubgraphPage(
        val id: String,
        val sectionId: String,
        val updatedAt: Long
    )

    /** The scoped render inputs a single graph build derives from. */
    data class GraphSubgraphResult(
        /** Page ids that survive the scope + cull-cap — the nodes actually drawn. */
        val keptPageIds: Set<String>,
        /** Edges whose both endpoints are inside [keptPageIds] (deduped, no loops). */
        val survivingEdges: List<GraphEdgeRef>,
        /** Pages inside [keptPageIds] with zero surviving edges (the "isolated" set). */
        val isolatedPageIds: Set<String>,
        /** True when the scope's page count was trimmed by the device node cap. */
        val didCull: Boolean
    ) {
        companion object {
            val EMPTY = GraphSubgraphResult(emptySet(), emptyList(), emptySet(), false)
        }
    }

    /**
     * Section ids that belong to [notebookId]. `null` notebook means "whole
     * vault" and yields an EMPTY section set (the sentinel the other helpers
     * treat as no-scope). A genuine notebook with zero sections yields empty
     * too — the full pipeline then produces an empty subgraph (fail closed,
     * never an accidental whole-vault fallback).
     */
    fun sectionIdsForNotebook(
        notebookId: String?,
        sections: List<Pair<String, String>>
    ): Set<String> {
        if (notebookId == null) return emptySet()
        if (sections.isEmpty()) return emptySet()
        val out = HashSet<String>(sections.size)
        for ((sectionId, ownerNotebookId) in sections) {
            if (ownerNotebookId == notebookId) out.add(sectionId)
        }
        return out
    }

    /**
     * Keeps the pages whose `sectionId` belongs to [notebookId]. A `null`
     * notebook (or an empty [sectionIdsForNotebook] result) means the scoped
     * set is the full page list — the "All Notes" mode.
     */
    fun filterPagesByNotebook(
        pages: List<GraphSubgraphPage>,
        notebookId: String?,
        sections: List<Pair<String, String>>
    ): List<GraphSubgraphPage> {
        if (notebookId == null) return pages
        val sectionIds = sectionIdsForNotebook(notebookId, sections)
        if (sectionIds.isEmpty()) return emptyList()
        return pages.filter { it.sectionId in sectionIds }
    }

    /**
     * Edges whose BOTH endpoints are inside [keptPageIds], self-loops removed,
     * deterministically deduplicated. This is what a notebook subgraph draws —
     * a cross-notebook wikilink disappears when its far endpoint is not in the
     * scope (it re-appears in the "All Notes" view).
     */
    fun edgesWithin(
        keptPageIds: Set<String>,
        edges: List<GraphEdgeRef>
    ): List<GraphEdgeRef> {
        if (keptPageIds.isEmpty() || edges.isEmpty()) return emptyList()
        val out = ArrayList<GraphEdgeRef>(minOf(edges.size, 1024))
        for (e in edges) {
            if (e.sourceId in keptPageIds && e.targetId in keptPageIds && e.sourceId != e.targetId) {
                out.add(e)
            }
        }
        return out.distinct()
    }

    /**
     * The pages inside [keptPageIds] touched by NO surviving edge — the
     * "isolated" nodes. Deterministic order is irrelevant (a set is returned);
     * the definition is "zero incident edges in the current subgraph".
     */
    fun isolatedPageIds(
        keptPageIds: Set<String>,
        survivingEdges: List<GraphEdgeRef>
    ): Set<String> {
        if (keptPageIds.isEmpty()) return emptySet()
        if (survivingEdges.isEmpty()) return keptPageIds
        val connected = HashSet<String>(survivingEdges.size * 2)
        for (e in survivingEdges) {
            connected.add(e.sourceId)
            connected.add(e.targetId)
        }
        val out = HashSet<String>(keptPageIds.size)
        for (id in keptPageIds) {
            if (id !in connected) out.add(id)
        }
        return out
    }

    /**
     * Full scoped-build pipeline:
     *
     *  1. scope [pages] to [notebookId] (null = whole vault);
     *  2. apply the device node cull-cap to that scoped set ([cap]);
     *  3. keep only the edges within the kept ids;
     *  4. derive the isolated set from step 3.
     *
     * [didCull] reports whether step 2 actually trimmed the scoped set, so the
     * UI can raise the non-alarming "showing the most recent N notes" notice
     * exactly when this device's cull applied and not otherwise.
     */
    fun apply(
        pages: List<GraphSubgraphPage>,
        notebookId: String?,
        sections: List<Pair<String, String>>,
        edges: List<GraphEdgeRef>,
        cap: Int
    ): GraphSubgraphResult {
        if (pages.isEmpty()) return GraphSubgraphResult.EMPTY
        val scoped = filterPagesByNotebook(pages, notebookId, sections)
        if (scoped.isEmpty()) return GraphSubgraphResult.EMPTY
        val keptIds = GraphTierSelector.cullToCap(scoped.map { it.id to it.updatedAt }, cap)
        val didCull = scoped.size > keptIds.size
        val surviving = edgesWithin(keptIds, edges)
        return GraphSubgraphResult(
            keptPageIds = keptIds,
            survivingEdges = surviving,
            isolatedPageIds = isolatedPageIds(keptIds, surviving),
            didCull = didCull
        )
    }

    /**
     * One-shot, non-alarming explanatory copy for the device-cull notice —
     * honours the AGENTS.md hardware-reality rule: never silent degradation.
     */
    fun cullNotice(nodeCount: Int): String =
        "Showing the $nodeCount most recent notes for this device's memory."
}