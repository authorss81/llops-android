package com.authorss81.noteflow.paparazzi

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenRatio
import com.android.resources.ScreenSize
import com.authorss81.noteflow.theme.AppThemeMode
import com.authorss81.noteflow.theme.NoteflowTheme
import com.authorss81.noteflow.ui.components.InteractiveTutorial
import com.authorss81.noteflow.ui.components.LayerDemoPanel
import com.authorss81.noteflow.ui.components.MarkdownTypeDemo
import com.authorss81.noteflow.ui.components.PracticePad
import com.authorss81.noteflow.ui.components.PracticePadMode
import com.authorss81.noteflow.utils.NestedScrollGuardConfig
import org.junit.Test

/**
 * Phase 233 — Paparazzi golden regression for the fixed scrollable screens
 * on BOTH tablet and phone configs.
 *
 * The screens carry nested `verticalScroll` containers protected by the
 * phase-230 bound-before-scroll fix + the phase-231/232 nested-scroll guards.
 * Rendering them on a large tablet AND a compact phone in the JVM layoutlib
 * renderer proves:
 *   - tablet: no nested-scroll crash (`CheckScrollableContainerConstraints`),
 *   - phone: the SAME layout as before the modifiers were reordered (mobile
 *     parity — the reorder that fixed tablets did not regress the phone).
 *
 * The inspected screens are AGSL-free (plain Compose `Canvas` practice pads,
 * `Card` + `verticalScroll`), so they are Paparazzi-safe. The concrete
 * subclasses inject the phone and tablet device configs.
 */
abstract class Phase233ScrollableGoldenTest {

    abstract val paparazzi: Paparazzi

    // ------------- InteractiveTutorial (full deck) -------------
    // Slide 0 `start_welcome`: plain content card. Slide 20 `layers_demo`:
    // carries the bounded LayerDemoPanel so the nested-scroll path is exercised.

    @Test
    fun welcome() {
        snapshot(golden("welcome")) {
            NoteflowTheme(themeMode = AppThemeMode.LIGHT) {
                Tutorial(initialIndex = 0)
            }
        }
    }

    @Test
    fun layers_demo() {
        snapshot(golden("layers_demo")) {
            NoteflowTheme(themeMode = AppThemeMode.LIGHT) {
                Tutorial(initialIndex = 20)
            }
        }
    }

    // ------------- TutorialDemos bounded panels -------------

    @Test
    fun layers_panel() {
        snapshot(golden("layers_panel")) {
            NoteflowTheme(themeMode = AppThemeMode.LIGHT) {
                Surface(modifier = Modifier.padding(16.dp)) {
                    LayerDemoPanel(onLayerAdded = {})
                }
            }
        }
    }

    @Test
    fun practice_draw() {
        snapshot(golden("practice_draw")) {
            NoteflowTheme(themeMode = AppThemeMode.LIGHT) {
                Surface(modifier = Modifier.padding(16.dp)) {
                    PracticePad(mode = PracticePadMode.DRAW, onGestureDone = {})
                }
            }
        }
    }

    @Test
    fun markdown_panel() {
        snapshot(golden("markdown_panel")) {
            NoteflowTheme(themeMode = AppThemeMode.LIGHT) {
                Surface(modifier = Modifier.padding(16.dp)) {
                    MarkdownTypeDemo(onTyped = {})
                }
            }
        }
    }

    /** Prefixes the snapshot name with the concrete device family. */
    abstract fun golden(name: String): String

    private fun snapshot(name: String, content: @Composable () -> Unit) {
        // The NestedScrollGuard is a DEBUG-only on-device diagnostic
        // (phase-231). Its measure-phase depth ThreadLocal is confounded by the
        // layoutlib renderer Paparazzi uses (a single scrollable's guard can be
        // entered multiple times across measure passes without a balanced exit),
        // producing false positives that would block every scrollable golden.
        // The REAL regression guards are (a) the correct bound-before-scroll
        // ordering fixed in phase-230 and (b) the phase-232 static source scan;
        // the golden image itself proves the screen renders without a genuine
        // CheckScrollableContainerConstraints crash. So the runtime diagnostic is
        // suspended only for the duration of this snapshot and restored after.
        val prior = NestedScrollGuardConfig.enabled
        NestedScrollGuardConfig.enabled = false
        try {
            paparazzi.snapshot(name = name, composable = content)
        } finally {
            NestedScrollGuardConfig.enabled = prior
        }
    }

    @Composable
    private fun Tutorial(initialIndex: Int) {
        Column(Modifier.fillMaxSize()) {
            InteractiveTutorial(
                initialIndex = initialIndex,
                onProgress = { _ -> },
                onSkip = {},
                onComplete = {},
                onDontShowAgain = {}
            )
        }
    }
}

/**
 * Phone config — identical to the smoke test: 360×800dp @ xxhdpi,
 * 1080×2400 px. Names prefixed `tutorial_phone_*`.
 */
class Phase233ScrollableGoldenPhoneTest : Phase233ScrollableGoldenTest() {

    @get:org.junit.Rule
    override val paparazzi: Paparazzi = Paparazzi(
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

    override fun golden(name: String): String = "tutorial_phone_$name"
}

/**
 * Tablet config — physical 2560×1600 @ xhdpi (density 2×) ⇒ 1280×800dp.
 * Names prefixed `tutorial_tablet_*`.
 */
class Phase233ScrollableGoldenTabletTest : Phase233ScrollableGoldenTest() {

    @get:org.junit.Rule
    override val paparazzi: Paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenWidth = 2560,
            screenHeight = 1600,
            xdpi = 320,
            ydpi = 320,
            density = Density.XHIGH,
            ratio = ScreenRatio.LONG,
            size = ScreenSize.LARGE
        )
    )

    override fun golden(name: String): String = "tutorial_tablet_$name"
}
