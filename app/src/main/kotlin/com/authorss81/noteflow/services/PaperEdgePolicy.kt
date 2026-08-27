package com.authorss81.noteflow.services

import kotlin.math.sin

/**
 * Phase 227 — paper-edge decision table. Pure JVM so the persistence key, the
 * enum mapping and the deckle wave math are unit-testable without Android.
 *
 * The paper card previously drew a perfect [BorderRect] (8dp round rect) as its
 * ONLY edge. This policy owns the three edge styles:
 *
 *  - [PaperEdge.RECT]      — sharp corners (rectangular notebook stock).
 *  - [PaperEdge.ROUNDED]   — the long-standing 8dp round rect (the default, so
 *                            untouched call sites keep the exact pre-227 look).
 *  - [PaperEdge.DECKLED]   — hand-cut "deckle" edge: the outline follows a
 *                            deterministic two-tone wave so the sheet reads as
 *                            real paper. Pure vector (Path.cubicTo + one
 *                            BlurMaskFilter shadow), NO bitmap, so the base-APK
 *                            size rule is untouched and the edge costs a single
 *                            cached Path per geometry.
 *
 * The wave is the prompt-specified `sin(x*0.08+seed)*2 + sin(x*0.15)*1`
 * (≈ 78px + 42px spatial periods, combined peak-to-peak deviation ±3px at any
 * density), scaled by the display density so the physical amplitude is a fixed
 * 2–3dp on every device. [seedFor] is deliberately paper-family only (not
 * per-page), so consecutive pages in one document share one consistent "sheet
 * stock" edge and the per-geometry Path memo in the renderer can hit.
 *
 * The deckle drop shadow reuses the Phase 213 per-stroke policy verbatim:
 * [BrushShadowPolicy.offset]/[blurRadius]/[shadowAlpha] against a nominal card
 * width, so one shadow vocabulary covers strokes AND the page edge.
 */
object PaperEdgePolicy {

    enum class PaperEdge { RECT, ROUNDED, DECKLED }

    /** Persisted default — preserves the pre-227 round-rect card exactly. */
    const val DEFAULT_KEY = "rounded"

    /** The only persistence vocabulary the app ever stores. */
    const val KEY_RECT = "rect"
    const val KEY_ROUNDED = "rounded"
    const val KEY_DECKLED = "deckled"
    private val VALID_KEYS: Set<String> = setOf(KEY_RECT, KEY_ROUNDED, KEY_DECKLED)

    /**
     * Disambiguates the legacy "rounded" 8dp card radius from the RECT style.
     * Today only used by the round-rect path (DECKLED has its own outline).
     */
    const val ROUNDED_CORNER_RADIUS_DP = 8f

    /** First (long) wave amplitude in px at density 1 — `*2` in the prompt wave. */
    const val WAVE_AMPLITUDE_1 = 2f

    /** Second (short) wave amplitude in px at density 1 — `*1` in the prompt wave. */
    const val WAVE_AMPLITUDE_2 = 1f

    /** Spatial frequency of the long wave (rad / px): period ≈ 78.5 px. */
    const val WAVE_FREQ_1 = 0.08f

    /** Spatial frequency of the short wave (rad / px): period ≈ 41.9 px. */
    const val WAVE_FREQ_2 = 0.15f

    /** Nominal stroke width the deckle shadow's offset/blur are derived from. */
    const val DECKLE_SHADOW_NOMINAL_WIDTH_PX = 12f

    /** Peak perpendicular deviation of the wave (±3 px at any density). */
    fun peakDeviationPx(): Float = WAVE_AMPLITUDE_1 + WAVE_AMPLITUDE_2

    /** Sanitizes a stored preference value; unknown/corrupt keys fall back to the default. */
    fun sanitizeKey(key: String?): String =
        if (key != null && key.lowercase() in VALID_KEYS) key.lowercase() else DEFAULT_KEY

    /** Persisted key for an edge, or [DEFAULT_KEY] for a degenerate input. */
    fun persistenceKey(edge: PaperEdge): String = when (edge) {
        PaperEdge.RECT -> KEY_RECT
        PaperEdge.ROUNDED -> KEY_ROUNDED
        PaperEdge.DECKLED -> KEY_DECKLED
    }

    /** Enum from a persisted (or free) key; unknown values resolve to [PaperEdge.ROUNDED]. */
    fun fromKey(key: String?): PaperEdge =
        when (sanitizeKey(key)) {
            KEY_RECT -> PaperEdge.RECT
            KEY_DECKLED -> PaperEdge.DECKLED
            else -> PaperEdge.ROUNDED
        }

    /**
     * Deterministic per-paper-family seed. Deliberately does NOT include the
     * page label/index — every page of a document shares one "stock" edge so
     * the memoized Path is hit per geometry and the deckle reads as a single
     * cut sheet (a per-page seed would flicker weirdly across fast scroll too).
     */
    fun seedFor(isDarkPaper: Boolean): Int =
        if (isDarkPaper) DARK_SEED else LIGHT_SEED

    private const val LIGHT_SEED = 0x1D6E81
    private const val DARK_SEED = 0x5A3A229

    /**
     * The wavy edge function from the phase spec, evaluated at the ALONG-EDGE
     * coordinate [xPx] (px). Range is [peakDeviationPx()] either side of the
     * nominal edge line for ANY input (sin is bounded by construction).
     */
    fun wavyOffsetAt(xPx: Float, seed: Int): Float {
        if (!xPx.isFinite()) return 0f
        return sin(xPx * WAVE_FREQ_1 + seed * 0.13f) * WAVE_AMPLITUDE_1 +
            sin(xPx * WAVE_FREQ_2) * WAVE_AMPLITUDE_2
    }

    /**
     * The physical peak deviation for the current density: ±[peakDeviationPx()]
     * dp in every unit — i.e. a fixed 2–3dp wave on every device.
     */
    fun amplitudePx(pxPerDp: Float): Float =
        peakDeviationPx() * (if (pxPerDp.isFinite() && pxPerDp > 0f) pxPerDp else 1f)

    /**
     * Phase 227: the DETERMINISTIC clockwise perimeter node list for a deckled
     * sheet of [width]x[height] at ([x],[y]). Every side gets nodes proportional
     * to its length (min 4); the perpendicular deviation is
     * [wavyOffsetAt] scaled to [ampPx] and faded near corners so adjacent sides
     * meet at a crisp sheet corner. Pure geometry (no Compose types) so BOTH the
     * on-canvas card (AnnotationCanvas) and the raster/PSD exporter share one
     * edge and one silhouette — the export never disagrees with the editor.
     *
     * Both consumers then smooth the nodes with the SAME quadratic-midpoint
     * technique (a closed `moveTo(midPoint) ; quadraticBezierTo(node, midPoint)`
     * loop), so the exporter reuses the exact deckled curvature.
     */
    fun deckleNodes(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        ampPx: Float,
        seed: Int
    ): List<Pair<Float, Float>> {
        val perimeter = width * 2f + height * 2f
        val total = ((perimeter / 44f).toInt()).coerceIn(16, 96)
        val topN = ((total * width / perimeter).toInt()).coerceAtLeast(4)
        val rightN = ((total * height / perimeter).toInt()).coerceAtLeast(4)
        val bottomN = ((total * width / perimeter).toInt()).coerceAtLeast(4)
        val leftN = ((total * height / perimeter).toInt()).coerceAtLeast(4)
        val nodes = ArrayList<Pair<Float, Float>>(topN + rightN + bottomN + leftN)
        val peak = peakDeviationPx()
        val cornerFadeZone = (ampPx * 1.5f).coerceAtLeast(6f)
        fun dev(along: Float, pxFromStart: Float, pxToEnd: Float): Float {
            val raw = wavyOffsetAt(along, seed)
            val fade = minOf(pxFromStart, pxToEnd) / cornerFadeZone
            return (raw / peak) * ampPx * fade.coerceIn(0f, 1f)
        }
        // Walks an edge from `base` each `tileLen` px along the rectangle side
        // `step`, sampling the wave at the ALONG-EDGE coordinate and nudging the
        // node perpendicularly (perp) so the corner-to-corner seams stay square.
        fun addEdge(
            stepX: Float,
            stepY: Float,
            baseX: Float,
            baseY: Float,
            alongBase: Float,
            tileLen: Float,
            perpX: Float,
            perpY: Float,
            count: Int
        ) {
            val stepAlong = if (stepX != 0f) stepX else stepY
            for (i in 0..count) {
                val t = tileLen * i / count
                val d = dev(alongBase + stepAlong * t, t, tileLen - t)
                nodes.add(
                    Pair(baseX + stepX * t + perpX * d, baseY + stepY * t + perpY * d)
                )
            }
        }
        // Top (L→R, outward = up / -y), Right (down, +x), Bottom (→L, +y), Left (up, -x).
        addEdge(1f, 0f, x, y, x, width, 0f, -1f, topN)
        addEdge(0f, 1f, x + width, y, y, height, 1f, 0f, rightN)
        addEdge(-1f, 0f, x + width, y + height, x + width, width, 0f, 1f, bottomN)
        addEdge(0f, -1f, x, y + height, y + height, height, -1f, 0f, leftN)
        return nodes
    }

    /**
     * Phase 227: the closed smooth polyline both consumers trace through the
     * deckled silhouette. [deckleNodes] defines the wave's PEAK deviations;
     * connecting the MIDPOINT between each consecutive node pair yields the same
     * outline the quadratic-midpoint curve would, but as plain moveTo/lineTo
     * operations — identical geometry whether the edge is drawn by the Compose
     * canvas card or clipped into an exported PNG/PSD layer.
     */
    fun smoothedDeckleMidpoints(nodes: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (nodes.size < 4) return nodes
        val out = ArrayList<Pair<Float, Float>>(nodes.size)
        for (i in nodes.indices) {
            val prev = nodes[(i - 1 + nodes.size) % nodes.size]
            val cur = nodes[i]
            out.add(
                Pair(
                    (prev.first + cur.first) / 2f,
                    (prev.second + cur.second) / 2f
                )
            )
        }
        return out
    }
}