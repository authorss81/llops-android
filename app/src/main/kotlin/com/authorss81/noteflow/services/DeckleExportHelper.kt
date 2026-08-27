package com.authorss81.noteflow.services

/**
 * Phase 227 — deckled silhouette builder for EXPORT canvases (flattened raster
 * AND every layered PSD bitmap). The on-canvas card clones the same pure-JVM
 * node stream, so the exported sheet is geometrically identical to the editor.
 *
 * [pxPerDp] keeps the torn wave proportional to the page size regardless of the
 * export resolution: the canvases draw at 1080px nominal pages, which is the
 * 360dp sheet at a 3× pixel ratio — so callers pass `width / 360f` to get the
 * same ±3dp tooth a device would show. Dark-paper seed matching is unnecessary
 * for exports (the raster canvas renders against a white page), so this helper
 * uses the light-paper stock exclusively — still deterministic.
 */
object DeckleExportHelper {

    /** Closed sheet outline clipped (pre-draw) into an export canvas. */
    fun sheetPath(width: Float, height: Float, pxPerDp: Float): android.graphics.Path {
        val density = if (pxPerDp.isFinite() && pxPerDp > 0f) pxPerDp else 1f
        val ampPx = PaperEdgePolicy.amplitudePx(density)
        val nodes = PaperEdgePolicy.deckleNodes(0f, 0f, width, height, ampPx, PaperEdgePolicy.seedFor(isDarkPaper = false))
        val midpoints = PaperEdgePolicy.smoothedDeckleMidpoints(nodes)
        val path = android.graphics.Path()
        if (midpoints.isEmpty()) return path
        path.moveTo(midpoints[0].first, midpoints[0].second)
        for (i in 1 until midpoints.size) {
            path.lineTo(midpoints[i].first, midpoints[i].second)
        }
        path.close()
        return path
    }

    /**
     * The user's current edge preference. The clip is applied ONLY for the
     * deckled style; RECT/ROUNDED exports stay fully rectangular (legacy).
     */
    fun deckledEnabled(context: android.content.Context): Boolean =
        SettingsManager(context).paperEdgeKey == PaperEdgePolicy.KEY_DECKLED
}