package com.authorss81.noteflow.paparazzi

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenRatio
import com.android.resources.ScreenSize
import com.authorss81.noteflow.theme.AppThemeMode
import com.authorss81.noteflow.theme.NoteflowTheme
import org.junit.Rule
import org.junit.Test

/**
 * Phase 195: Paparazzi pipeline smoke test. Renders one trivial screen in
 * light + dark to prove the JVM screenshot renderer is wired end-to-end
 * (layoutlib native runtime, AGSL-free composables, theme parameterization)
 * before the full screen × state × theme matrix lands in Step 2.
 */
class PaparazziSmokeTest {

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

    @Test
    fun rendersLightTheme() {
        paparazzi.snapshot(name = "smoke_light") {
            NoteflowTheme(themeMode = AppThemeMode.LIGHT) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Paparazzi light",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }

    @Test
    fun rendersDarkTheme() {
        paparazzi.snapshot(name = "smoke_dark") {
            NoteflowTheme(themeMode = AppThemeMode.DARK) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Paparazzi dark",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}