package com.authorss81.noteflow.paparazzi

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenRatio
import com.android.resources.ScreenSize
import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeTool
import com.authorss81.noteflow.theme.AppThemeMode
import com.authorss81.noteflow.theme.NoteflowTheme
import com.authorss81.noteflow.ui.components.TimelapsePlayer
import org.junit.Rule
import org.junit.Test

/**
 * Phase 224 — Paparazzi frame of the [TimelapsePlayer] preview UI (the MP4
 * timelapse dialog's player: preview canvas + scrub slider + Play/Pause + Export).
 * Renders the player's structural frame with a small stroke set so the layout is
 * screenshot-reviewed across Devs. The preview bitmap itself may not populate in
 * the JVM layoutlib snapshot (coroutine-timed raster), which is expected — this
 * pins the player's layout/no-crash behavior.
 */
class TimelapsePlayerPaparazziTest {

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

    private val strokes: List<Stroke> = listOf(
        Stroke(
            id = "s0",
            tool = StrokeTool.PEN,
            points = listOf(PointF(100f, 300f), PointF(400f, 600f)),
            timestampMs = 0L
        ),
        Stroke(
            id = "s1",
            tool = StrokeTool.PEN,
            points = listOf(PointF(200f, 100f), PointF(700f, 800f)),
            timestampMs = 3_000L
        )
    )

    @Test
    fun rendersLightTheme() {
        paparazzi.snapshot(name = "timelapse_player_light") {
            NoteflowTheme(themeMode = AppThemeMode.LIGHT) {
                Surface(modifier = Modifier.padding(16.dp)) {
                    TimelapsePlayer(
                        strokes = strokes,
                        isExporting = false,
                        exportProgress = 0f,
                        onExport = {}
                    )
                }
            }
        }
    }

    @Test
    fun rendersDarkTheme() {
        paparazzi.snapshot(name = "timelapse_player_dark") {
            NoteflowTheme(themeMode = AppThemeMode.DARK) {
                Surface(modifier = Modifier.padding(16.dp)) {
                    TimelapsePlayer(
                        strokes = strokes,
                        isExporting = false,
                        exportProgress = 0f,
                        onExport = {}
                    )
                }
            }
        }
    }
}
