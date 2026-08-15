package com.authorss81.noteflow.services.graph

import kotlin.math.sqrt

/**
 * Force-directed graph math — deterministic, pure JVM.
 *
 * Every physics tuning constant lives in [GraphPhysicsConfig] (single source of
 * truth: the UI layer never hardcodes magic numbers). The layout is a pure
 * function of its inputs, so repeated runs with identical seeds produce
 * identical positions — which is what makes the layout unit-testable and the
 * on-screen animation deterministic.
 *
 * Physics model (Phase 38):
 *  1. Coulomb repulsion pushes every node pair apart.
 *  2. Hooke attraction pulls wiki-linked pairs to a rest distance.
 *  3. A weak centering gravity prevents drift while pan/zoom is idle.
 *  4. Velocity is damped and clamped so the sim settles instead of exploding.
 *  5. [resolveCollisionsAndBounds] keeps nodes from overlapping and pins every
 *     node inside the world box — physics-based collision bounding so nodes
 *     push apart and edges stay within bounds.
 *
 * Low-end fallback is expressed as iteration/page caps on [GraphTierProfile] —
 * the caller picks a profile by device tier and applies [cullToCap] BEFORE
 * layout (see `KnowledgeGraphScreen`); the decision to reduce is surfaced to
 * the user (a settings switch), never silent.
 */
object GraphPhysicsConfig {
    const val REPULSION_FORCE = 18000f
    /** sq-distance floor so forces never explode when two nodes coincide. */
    const val MIN_DIST_SQ = 100f
    const val SPRING_REST_LENGTH = 140f
    const val SPRING_STIFFNESS = 0.05f
    const val CENTER_GRAVITY = 0.02f
    const val VELOCITY_DAMPING = 0.9f
    const val MAX_VELOCITY = 60f

    /** Half-extent of the square world all nodes are bounded inside. */
    const val BOUNDS_HALF_EXTENT = 900f
    /** Extra separation applied beyond the sum of radii during collision. */
    const val COLLISION_PADDING = 3f
    /** Below this distance a pair is treated as "already separated". */
    const val COLLISION_MIN_DIST_SQ = 1f

    const val DEFAULT_ITERATIONS = 90
    const val MID_RAM_ITERATIONS = 60
    const val LOW_RAM_ITERATIONS = 35

    const val NODE_CAP_DEFAULT = 400
    const val NODE_CAP_MID = 220
    const val NODE_CAP_LOW = 120
}

/**
 * Capped physics workload per device tier. Iterations and node caps are the two
 * levers we pull on low-RAM/2-core hardware to keep the sim at 60fps.
 */
data class GraphTierProfile(
    val iterations: Int,
    val nodeCap: Int
) {
    companion object {
        val DEFAULT = GraphTierProfile(
            GraphPhysicsConfig.DEFAULT_ITERATIONS,
            GraphPhysicsConfig.NODE_CAP_DEFAULT
        )
        val MID_RANGE = GraphTierProfile(
            GraphPhysicsConfig.MID_RAM_ITERATIONS,
            GraphPhysicsConfig.NODE_CAP_MID
        )
        val LOW_END = GraphTierProfile(
            GraphPhysicsConfig.LOW_RAM_ITERATIONS,
            GraphPhysicsConfig.NODE_CAP_LOW
        )
    }
}

/** A graph node carrying physics state. Mutable during layout, copied out. */
data class GraphVertex(
    val id: String,
    var x: Float = 0f,
    var y: Float = 0f,
    var vx: Float = 0f,
    var vy: Float = 0f,
    val radius: Float = 28f,
    val clusterId: Int = 0
)

/** Directed edge reference (wikilink). Undirected in the force model. */
data class GraphEdgeRef(
    val sourceId: String,
    val targetId: String
)

object GraphLayoutMath {

    /**
     * Advance every node one physics step. Mutates [nodes] in place —
     * deterministic given the same starting state, edges and config.
     */
    fun step(
        nodes: MutableList<GraphVertex>,
        edges: List<GraphEdgeRef>,
        repulsion: Float = GraphPhysicsConfig.REPULSION_FORCE,
        springRest: Float = GraphPhysicsConfig.SPRING_REST_LENGTH,
        springK: Float = GraphPhysicsConfig.SPRING_STIFFNESS,
        gravity: Float = GraphPhysicsConfig.CENTER_GRAVITY,
        damping: Float = GraphPhysicsConfig.VELOCITY_DAMPING,
        maxVelocity: Float = GraphPhysicsConfig.MAX_VELOCITY
    ) {
        val index = HashMap<String, Int>(nodes.size * 2)
        nodes.forEachIndexed { i, n -> index[n.id] = i }

        // 1. Coulomb repulsion (O(n^2), the dominant cost).
        for (i in 0 until nodes.size) {
            val a = nodes[i]
            for (j in i + 1 until nodes.size) {
                val b = nodes[j]
                val dx = b.x - a.x
                val dy = b.y - a.y
                val distSq = (dx * dx + dy * dy).coerceAtLeast(GraphPhysicsConfig.MIN_DIST_SQ)
                val dist = sqrt(distSq)
                val force = repulsion / distSq
                val fx = (dx / dist) * force
                val fy = (dy / dist) * force
                a.vx -= fx
                a.vy -= fy
                b.vx += fx
                b.vy += fy
            }
        }

        // 2. Hooke attraction along edges.
        for (edge in edges) {
            val s = index[edge.sourceId] ?: continue
            val t = index[edge.targetId] ?: continue
            val a = nodes[s]
            val b = nodes[t]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
            val force = (dist - springRest) * springK
            val fx = (dx / dist) * force
            val fy = (dy / dist) * force
            a.vx += fx
            a.vy += fy
            b.vx -= fx
            b.vy -= fy
        }

        // 3. Centering gravity + damping + integration.
        for (n in nodes) {
            n.vx -= n.x * gravity
            n.vy -= n.y * gravity
            n.vx *= damping
            n.vy *= damping
            val speed = sqrt(n.vx * n.vx + n.vy * n.vy)
            if (speed > maxVelocity) {
                val scale = maxVelocity / speed
                n.vx *= scale
                n.vy *= scale
            }
            n.x += n.vx
            n.y += n.vy
        }
    }

    /**
     * Physics-based collision bounding: push overlapping nodes apart along the
     * line between them (plus a small padding), then clamp every node inside
     * the world box. Deterministic — pairs processed in stable order.
     */
    fun resolveCollisionsAndBounds(
        nodes: MutableList<GraphVertex>,
        boundsHalf: Float = GraphPhysicsConfig.BOUNDS_HALF_EXTENT,
        padding: Float = GraphPhysicsConfig.COLLISION_PADDING
    ) {
        for (i in 0 until nodes.size) {
            val a = nodes[i]
            for (j in i + 1 until nodes.size) {
                val b = nodes[j]
                val dx = b.x - a.x
                val dy = b.y - a.y
                val distSq = dx * dx + dy * dy
                if (distSq < GraphPhysicsConfig.COLLISION_MIN_DIST_SQ) continue
                val minDist = (a.radius + b.radius) * 0.5f + padding
                if (distSq >= minDist * minDist) continue
                val dist = sqrt(distSq)
                val overlap = (minDist - dist) / 2f
                val nx = dx / dist
                val ny = dy / dist
                a.x -= nx * overlap
                a.y -= ny * overlap
                b.x += nx * overlap
                b.y += ny * overlap
            }
        }
        for (n in nodes) {
            n.x = n.x.coerceIn(-boundsHalf, boundsHalf)
            n.y = n.y.coerceIn(-boundsHalf, boundsHalf)
        }
    }

    /**
     * Run a full deterministic layout for [iterations] steps. Returns a fresh
     * list of vertices (positions, not the input) so callers can animate into
     * the result without mutating state mid-render.
     */
    fun layout(
        startingPositions: List<GraphVertex>,
        edges: List<GraphEdgeRef>,
        iterations: Int,
        stepCountFn: (GraphLayoutMathProgress) -> Unit = {}
    ): List<GraphVertex> {
        val work = startingPositions.map { v -> v.copy() }.toMutableList()
        for (i in 0 until iterations) {
            step(work, edges)
            resolveCollisionsAndBounds(work)
            if (i % 10 == 0) stepCountFn(GraphLayoutMathProgress(i, iterations))
        }
        stepCountFn(GraphLayoutMathProgress(iterations, iterations))
        return work.toList()
    }

    /**
     * Deterministic cluster assignment: union-find over (a) pages sharing a tag
     * and (b) wikilink edges. Cluster ids are assigned by sorting roots by the
     * smallest member page id so the whole annotation is reproducible. Pages
     * with no tags and no edges each form their own cluster.
     */
    fun assignClusters(
        pages: List<Pair<String, Set<String>>>,
        edges: List<GraphEdgeRef>
    ): Map<String, Int> {
        val pageIds = pages.map { it.first }.toSet()
        val parent = HashMap<String, String>()
        pages.forEach { (id, _) -> parent[id] = id }

        fun find(x: String): String {
            var root = x
            while (parent[root] != root) root = parent[root]!!
            // path compression
            var cur = x
            while (parent[cur] != cur) {
                val next = parent[cur]!!
                parent[cur] = root
                cur = next
            }
            return root
        }
        fun union(a: String, b: String) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }

        // Tag communities: every page sharing a tag lands in the same cluster.
        val tagBuckets = HashMap<String, MutableList<String>>()
        pages.forEach { (id, tags) ->
            tags.sorted().forEach { tag -> tagBuckets.getOrPut(tag) { mutableListOf() }.add(id) }
        }
        tagBuckets.values.forEach { members ->
            for (i in 1 until members.size) union(members[0], members[i])
        }

        // Wikilink communities.
        edges.forEach { e ->
            if (pageIds.contains(e.sourceId) && pageIds.contains(e.targetId)) {
                union(e.sourceId, e.targetId)
            }
        }

        // Deterministic cluster numbering.
        val clusters = HashMap<String, MutableList<String>>()
        pages.forEach { (id, _) -> clusters.getOrPut(find(id)) { mutableListOf() }.add(id) }
        val roots = clusters.keys.sortedBy { clusters.getValue(it).min() }
        val clusterIndex = HashMap<String, Int>(roots.size)
        roots.forEachIndexed { i, root -> clusterIndex[root] = i }
        return pageIds.associateWith { id -> clusterIndex.getValue(find(id)) }
    }
}

/** Progress report handed to [GraphLayoutMath.layout]'s optional callback. */
data class GraphLayoutMathProgress(
    val step: Int,
    val totalSteps: Int
) {
    val fraction: Float get() = if (totalSteps <= 0) 1f else (step.toFloat() / totalSteps).coerceIn(0f, 1f)
}

/**
 * Picks a [GraphTierProfile] by the number of nodes (a cheap proxy for the
 * O(n^2) repulsion cost) — the KnowledgeGraphScreen passes its device tier in.
 */
object GraphTierSelector {
    fun profileFor(lowEnd: Boolean, nodeCount: Int): GraphTierProfile {
        return if (lowEnd) GraphTierProfile.LOW_END
        else if (nodeCount > 240) GraphTierProfile.DEFAULT
        else GraphTierProfile.MID_RANGE
    }

    /** Deterministic node cull: keep the most recently updated [cap] pages. */
    fun cullToCap(
        pages: List<Pair<String, Long>>,
        cap: Int
    ): Set<String> {
        if (pages.size <= cap) return pages.map { it.first }.toSet()
        return pages.sortedByDescending { it.second }.take(cap).map { it.first }.toSet()
    }
}