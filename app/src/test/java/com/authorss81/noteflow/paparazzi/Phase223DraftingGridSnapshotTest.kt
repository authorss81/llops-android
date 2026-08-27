package com.authorss81.noteflow.paparazzi

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenRatio
import com.android.resources.ScreenSize
import com.authorss81.noteflow.theme.AppThemeMode
import com.authorss81.noteflow.theme.NoteflowTheme
import com.authorss81.noteflow.ui.components.PaperTemplatePreview
import org.junit.Rule
import org.junit.Test

/**
 * Phase 223 — drafting-grid + rotated-canvas Paparazzi snapshots. Renders the
 * SAME perspective/isometric geometry (via [PaperTemplatePreview], which reuses
 * PerspectiveGridPolicy — identical to the full-page renderer's drawPaperTemplate
 * line families) on a white paper sheet.
 */
class Phase223DraftingGridSnapshotTest {

    @get:Rule
    val paparazzi: Paparazzi = Paparazzi(
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

    @Test
    fun perspectiveOnePoint() {
        paparazzi.snapshot(name = "phase223_perspective_1pt") {
            NoteflowTheme(themeMode = AppThemeMode.LIGHT) {
                GridSheet(template = "perspective_1pt")
            }
        }
    }

    @Test
    fun perspectiveTwoPoint() {
        paparazzi.snapshot(name = "phase223_perspective_2pt") {
            NoteflowTheme(themeMode = AppThemeMode.LIGHT) {
                GridSheet(template = "perspective_2pt")
            }
        }
    }

    @Test
    fun isometric() {
        paparazzi.snapshot(name = "phase223_isometric") {
            NoteflowTheme(themeMode = AppThemeMode.LIGHT) {
                GridSheet(template = "isometric")
            }
        }
    }

    @Test
    fun rotatedCanvas() {
        paparazzi.snapshot(name = "phase223_rotated_canvas") {
            NoteflowTheme(themeMode = AppThemeMode.LIGHT) {
                // A 20° rotationZ applied through graphicsLayer — the same mechanism
                // the canvas rotate feature uses for the whole world layer.
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                        .graphicsLayer {
                            rotationZ = 20f
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.5f)
                            clip = true
                        }
                ) {
                    GridSheet(template = "isometric")
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun GridSheet(template: String) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.White)
        ) {
            PaperTemplatePreview(template = template, modifier = Modifier.fillMaxSize())
        }
    }
}
