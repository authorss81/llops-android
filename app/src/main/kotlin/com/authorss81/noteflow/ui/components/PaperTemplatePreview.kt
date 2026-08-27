package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.authorss81.noteflow.services.PerspectiveGridPolicy

/**
 * Phase 223 — small paper-template thumbnail used by the template picker. Renders
 * ONLY the drafting grids (perspective 1-pt / 2-pt / isometric) through the SAME
 * [PerspectiveGridPolicy] line families the full-page renderer uses, so the picker
 * thumbnail is never a hand-drawn fake — it is the real geometry scaled down.
 * Non-drafting templates are left blank (they have no such shared line math).
 */
@Composable
fun PaperTemplatePreview(
    template: String,
    modifier: Modifier = Modifier,
    isDarkPaper: Boolean = false
) {
    Canvas(modifier = modifier) {
        val base = if (isDarkPaper) Color(0xFF94A3B8) else Color(0xFF64748B)
        val gridColor = base.copy(alpha = 0.55f)
        val w = size.width
        val h = size.height
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) {
            drawLine(gridColor, Offset(x0, y0), Offset(x1, y1), strokeWidth = 1f)
        }
        when (template) {
            "perspective_1pt" -> {
                val g = PerspectiveGridPolicy.onePoint(w, h)
                for (l in PerspectiveGridPolicy.depthLines(w, h, g.horizonY)) {
                    line(l.first.first, l.first.second, l.second.first, l.second.second)
                }
                for (l in PerspectiveGridPolicy.onePointRays(g)) {
                    line(l.first.first, l.first.second, l.second.first, l.second.second)
                }
            }
            "perspective_2pt" -> {
                val g = PerspectiveGridPolicy.twoPoint(w, h)
                for (l in PerspectiveGridPolicy.depthLines(w, h, g.horizonY)) {
                    line(l.first.first, l.first.second, l.second.first, l.second.second)
                }
                for (l in PerspectiveGridPolicy.twoPointRays(g)) {
                    val d = kotlin.math.abs(l.first.first - l.second.first) + kotlin.math.abs(l.first.second - l.second.second)
                    if (d > 1f) line(l.first.first, l.first.second, l.second.first, l.second.second)
                }
            }
            "isometric" -> {
                val g = PerspectiveGridPolicy.isometric(w, h)
                for (l in PerspectiveGridPolicy.isometricDiagonals(g)) {
                    line(l.first.first, l.first.second, l.second.first, l.second.second)
                }
            }
            else -> Unit
        }
    }
}
