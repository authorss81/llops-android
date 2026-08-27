package com.authorss81.noteflow.paparazzi

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenRatio
import com.android.resources.ScreenSize
import com.authorss81.noteflow.services.EyedropperSamplingMath
import com.authorss81.noteflow.theme.AppThemeMode
import com.authorss81.noteflow.theme.NoteflowTheme
import org.junit.Rule
import org.junit.Test

/**
 * Phase 225 — Paparazzi snapshot of an eyedropper-sampled swatch.
 *
 * Drives the PURE-JVM sampling math over a synthetic photo gradient: the tap
 * maps to a reference pixel, that pixel's color is lifted, and the snapshot
 * renders the picked color as a swatch with its hex label — proving the
 * sampled-pixel → swatch path visually (golden image) end to end.
 */
class Phase225EyedropperSamplingSnapshotTest {

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

    /**
     * Synthetic "photo" a user would trace: an RG corner gradient. This is the
     * stand-in for the decoded reference bitmap the canvas would read via
     * BitmapRegionDecoder.
     */
    private fun photoColorAt(px: Int, py: Int, bmpW: Int, bmpH: Int): Int {
        val r = (px * 255 / bmpW.coerceAtLeast(1)).coerceIn(0, 255)
        val g = (py * 255 / bmpH.coerceAtLeast(1)).coerceIn(0, 255)
        val b = 128
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    @Test
    fun sampledSwatchFromReference() {
        // Reference placed centred in a 2000x1600 page; its bitmap is 400x300.
        val refX = 500f
        val refY = 400f
        val refW = 1000f
        val refH = 800f
        val bmpW = 400
        val bmpH = 300
        val pageTopY = 0f

        // A tap near the photo's centre.
        val px = EyedropperSamplingMath.referencePixel(
            canvasX = 1000f, canvasY = 800f, pageTopY = pageTopY,
            refX = refX, refY = refY, refWidth = refW, refHeight = refH,
            bitmapWidth = bmpW, bitmapHeight = bmpH
        )!!
        val argb = photoColorAt(px.first, px.second, bmpW, bmpH)

        paparazzi.snapshot(name = "phase225_eyedropper_sampled_swatch") {
            NoteflowTheme(themeMode = AppThemeMode.LIGHT) {
                SwatchCard(argb, px.first, px.second)
            }
        }
    }

    @Composable
    private fun SwatchCard(argb: Int, px: Int, py: Int) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.padding(48.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Eyedropper sampled pixel", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "reference pixel ($px, $py)",
                    style = MaterialTheme.typography.bodyMedium
                )
                // The picked-color swatch.
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .size(220.dp)
                        .background(Color(argb))
                )
                Spacer(Modifier.height(8.dp))
                val hex = "#%06X".format(java.util.Locale.US, argb and 0xFFFFFF)
                Text(hex, style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
}
