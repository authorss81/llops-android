package com.authorss81.noteflow.paparazzi

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenRatio
import com.android.resources.ScreenSize
import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke as ModelStroke
import com.authorss81.noteflow.data.model.StrokeSelection
import com.authorss81.noteflow.data.model.StrokeTool
import com.authorss81.noteflow.services.SelectionTransformPolicy
import com.authorss81.noteflow.services.StrokeSelectionActionPolicy
import com.authorss81.noteflow.theme.AppThemeMode
import com.authorss81.noteflow.theme.NoteflowTheme
import com.authorss81.noteflow.ui.components.StrokeSelectionOverlay
import org.junit.Rule
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Phase 226: renders the SELECT-tool selection transform overlay showing three
 * ink strokes that were scaled 1.5× and rotated 45° about their centre — the
 * visual DoD for phase 226 (selection scale + rotate handles).
 *
 * The geometry is baked with the real [SelectionTransformPolicy] (the same math
 * the corner/rotation handles use) and the overlay draws the per-stroke
 * highlight + dashed bounds + the four visible-at-rest handles.
 */
class SelectionTransformOverlayPaparazziTest {

    @get:Rule
    val paparazzi: Paparazzi = Paparazzi(
        // 360×800dp @ xxhdpi (density 3×) → 1080×2400 px.
        deviceConfig = DeviceConfig(
            screenWidth = 1080,
            screenHeight = 2400,
            xdpi = 480,
            ydpi = 480,
            density = Density.XXHIGH,
            ratio = ScreenRatio.NOTLONG,
            size = ScreenSize.NORMAL
        )
    )

    private fun freehand(
        id: String,
        tool: StrokeTool,
        pts: List<PointF>
    ): ModelStroke = ModelStroke(id = id, tool = tool, points = pts, width = 8f)

    /**
     * Three hand-drawn strokes (a horizontal line, a heart-ish arc, a vertical
     * zig-zag) grouped near a common centre, so their selection union is a
     * compact box we can scale + rotate.
     */
    private fun threeStrokes(): List<ModelStroke> {
        // centre ~ (180, 180); strokes span roughly x 120..240, y 100..260.
        val s1 = freehand("s1", StrokeTool.PEN, (0..20).map { i ->
            val t = i / 20f
            PointF(120f + t * 240f, 180f + sin(t * 4f) * 12f)
        })
        val s2 = freehand("s2", StrokeTool.MARKER, (0..24).map { i ->
            val a = Math.PI * i / 24
            PointF(180f + 70f * cos(a).toFloat(), 180f + 70f * sin(a).toFloat())
        })
        val s3 = freehand("s3", StrokeTool.PENCIL, (0..12).map { i ->
            PointF(160f + (i % 2) * 40f, 100f + i * 14f)
        })
        return listOf(s1, s2, s3)
    }

    @Test
    fun rendersThreeSelectedStrokesScaledAndRotated() {
        val originals = threeStrokes()
        // Bake scale 1.5x + rotate 45° about the selection centre.
        val ids = originals.map { it.id }.toSet()
        val centre = SelectionTransformPolicy.centerOf(
            StrokeSelectionActionPolicy.recomputeBounds(originals, ids)
        )
        val transformed = SelectionTransformPolicy.transformSelected(
            strokes = originals,
            selectedIds = ids,
            centerX = centre.first,
            centerY = centre.second,
            sx = 1.5f,
            sy = 1.5f,
            degrees = 45f,
            pageStride = 1592f
        )
        val selection = StrokeSelection(
            ids = ids,
            bounds = StrokeSelectionActionPolicy.recomputeBounds(transformed, ids)
        )

        paparazzi.snapshot(name = "phase226_selection_3_strokes_scaled_1.5x_rotated_45") {
            NoteflowTheme(themeMode = AppThemeMode.LIGHT) {
                Box(Modifier.fillMaxSize()) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        drawRect(Color(0xFFFAFAFA))
                        val inkW = 8f
                        for (stroke in transformed) {
                            if (stroke.points.size > 1) {
                                val path = Path().apply {
                                    moveTo(stroke.points.first().x, stroke.points.first().y)
                                    for (i in 1 until stroke.points.size) lineTo(stroke.points[i].x, stroke.points[i].y)
                                }
                                drawPath(path, Color(0xFF1B365D), style = Stroke(inkW))
                            }
                        }
                    }
                    // The overlay itself occupies the full canvas in world space.
                    StrokeSelectionOverlay(
                        modifier = Modifier.fillMaxSize(),
                        accentColor = MaterialTheme.colorScheme.primary,
                        strokesProvider = { transformed },
                        selectedIds = selection.ids,
                        selectionBounds = selection.bounds,
                        lassoPointsProvider = { emptyList() },
                        lassoVisible = false,
                        zoomScale = 1f,
                        transformLocked = true,
                        onSelectionScale = { _, _, _, _ -> },
                        onSelectionRotate = { _, _, _ -> }
                    )
                }
            }
        }
    }
}
