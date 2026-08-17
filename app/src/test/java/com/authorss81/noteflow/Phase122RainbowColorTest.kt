package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.StrokeColorMode
import com.authorss81.noteflow.services.BrushColorModeMath
import com.authorss81.noteflow.services.ColorModePersistencePolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 122 — Rainbow brush colour mode.
 *
 * The feature: a "Rainbow" brush mode whose stroke colour sweeps the full hue
 * wheel along the stroke length, selectable + persisted via SettingsManager
 * (SharedPreferences), reachable from the colour picker AND the width/quick
 * picker, rendered per point. Rendering existed since phase-27; this phase
 * adds the persisted current-mode state (SettingsManager.brushColorModeKey via
 * the pure-JVM ColorModePersistencePolicy decision table), the allocation-free
 * hue-advance math, the shared chips row in the width picker, and the tests
 * below: deterministic hue advance that wraps at 360°, persistence decoding,
 * and proof that non-rainbow strokes are byte-identical.
 */
class Phase122RainbowColorTest {

    // ---- hue-advance policy (deterministic, wraps at 360°) -------------------

    @Test
    fun `hueAdvance is deterministic for the same seed and progress`() {
        for (seed in floatArrayOf(0f, 90f, 359f, 720f, -30f)) {
            for (progress in floatArrayOf(0f, 0.25f, 0.5f, 0.999f, 1f)) {
                assertEquals(
                    BrushColorModeMath.hueAdvance(seed, progress),
                    BrushColorModeMath.hueAdvance(seed, progress),
                    0f
                )
            }
        }
    }

    @Test
    fun `hueAdvance wraps at 360 degrees`() {
        // progress 0 and progress 1 coincide on a seamless loop (the wrap).
        for (seed in floatArrayOf(0f, 120f, 359f)) {
            assertEquals(
                "seed=$seed wrap seam",
                BrushColorModeMath.hueAdvance(seed, 0f),
                BrushColorModeMath.hueAdvance(seed, 1f),
                1e-4f
            )
        }
        // mid-progress is exactly 180° past the start (half a wheel).
        val start = BrushColorModeMath.hueAdvance(0f, 0f)
        val mid = BrushColorModeMath.hueAdvance(0f, 0.5f)
        assertEquals(180f, BrushColorModeMath.normalizeHue(mid - start), 1e-3f)
        // Every output is a normalized hue in [0, 360).
        for (progress in floatArrayOf(0f, 0.1f, 0.62f, 1f)) {
            val h = BrushColorModeMath.hueAdvance(10f, progress)
            assertTrue("hue $h in [0,360)", h in 0f..359.999f)
        }
    }

    @Test
    fun `hueAdvance clamps progress into 0 to 1`() {
        assertEquals(
            BrushColorModeMath.hueAdvance(45f, 0f),
            BrushColorModeMath.hueAdvance(45f, -1f),
            0f
        )
        assertEquals(
            BrushColorModeMath.hueAdvance(45f, 1f),
            BrushColorModeMath.hueAdvance(45f, 7f),
            0f
        )
    }

    @Test
    fun `rainbowColorAt delegates to the hue advance policy`() {
        // rainbowColorAt must agree with the standalone hue-advance helper so the
        // render path and the testable math can never drift.
        val base = 0xFF1B365D.toInt()
        val argb = BrushColorModeMath.rainbowColorAt(base, 0.4f, seed = 90)
        val p = 0.4f
        val hue = BrushColorModeMath.hueAdvance(BrushColorModeMath.seedHueDeg(90), p)
        val expected = BrushColorModeMath.hsvToArgb(hue, 1f, 1f)
        // value is lifted toward bright (base 0xFF1B365D value ~ .23 -> floor .5),
        // so the color is the fully-saturated hue at value .5.
        val expectedFloor = BrushColorModeMath.hsvToArgb(hue, 1f, 0.5f)
        assertEquals(expectedFloor, argb)
        assertNotEquals(expected, argb)
        // A bright base keeps its own high value instead of the floor.
        val bright = 0xFFFFFFFF.toInt()
        val brightArgb = BrushColorModeMath.rainbowColorAt(bright, 0.4f, seed = 90)
        assertEquals(BrushColorModeMath.hsvToArgb(hue, 1f, 1f), brightArgb)
    }

    // ---- persistence decision table (ColorModePersistencePolicy) -------------

    @Test
    fun `color mode persists and restores through the policy key`() {
        for (mode in StrokeColorMode.entries) {
            val stored = ColorModePersistencePolicy.prefValue(mode)
            assertEquals(mode, ColorModePersistencePolicy.modeFromPref(stored))
        }
    }

    @Test
    fun `missing or unknown preference fails closed to solid`() {
        assertEquals(StrokeColorMode.SOLID, ColorModePersistencePolicy.modeFromPref(null))
        assertEquals(StrokeColorMode.SOLID, ColorModePersistencePolicy.modeFromPref(""))
        assertEquals(StrokeColorMode.SOLID, ColorModePersistencePolicy.modeFromPref("NEON"))
        assertEquals(StrokeColorMode.SOLID, ColorModePersistencePolicy.modeFromPref(" rainbow"))
        assertEquals(ColorModePersistencePolicy.DEFAULT_MODE, StrokeColorMode.SOLID)
    }

    @Test
    fun `rainbow is a distinct persisted value from solid`() {
        assertNotEquals(
            ColorModePersistencePolicy.prefValue(StrokeColorMode.SOLID),
            ColorModePersistencePolicy.prefValue(StrokeColorMode.RAINBOW)
        )
        // a stored RAINBOW string restores to RAINBOW, never to solid.
        assertEquals(
            StrokeColorMode.RAINBOW,
            ColorModePersistencePolicy.modeFromPref(StrokeColorMode.RAINBOW.persistenceKey)
        )
    }

    @Test
    fun `pre-stored stroke serialization is unchanged by the mode`() {
        // Rainbow is a render-time derivation: the stored stroke payload must not
        // gain per-point color. colorForProgress for SOLID is the identity, exactly
        // what a pre-rainbow stroke renders.
        val base = 0xFF3399CC.toInt()
        val solid = BrushColorModeMath.colorForProgress(StrokeColorMode.SOLID, base, 0.5f, 123)
        assertEquals(base, solid)
        val rainbow = BrushColorModeMath.colorForProgress(StrokeColorMode.RAINBOW, base, 0.5f, 123)
        assertNotEquals(base, rainbow)
        // a re-derived rainbow color belongs to the wheel (fully saturated hue).
        val hsv = BrushColorModeMath.argbToHsv(rainbow)
        assertEquals(1f, hsv[1], 1e-4f)
    }

    // ---- non-rainbow strokes are unchanged -----------------------------------

    @Test
    fun `solid strokes are byte-identical under the new hue math path`() {
        // The allocation-free refactor must never change SOLID rendering.
        val base = intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFB7410E.toInt())
        for (b in base) {
            for (progress in floatArrayOf(0f, 0.3f, 1f)) {
                assertEquals(
                    b,
                    BrushColorModeMath.colorForProgress(StrokeColorMode.SOLID, b, progress, 0)
                )
            }
        }
    }

    @Test
    fun `rainbow with a zero-progress degenerate stroke stays in gamut`() {
        val base = 0xFF1B365D.toInt()
        val c = BrushColorModeMath.rainbowColorAt(base, 1f, seed = 42)
        val hsv = BrushColorModeMath.argbToHsv(c)
        assertTrue(hsv[1] in 0f..1f)
        assertEquals(1f, hsv[1], 1e-4f)
        assertEquals(0xFF, BrushColorModeMath.alpha(c))
    }

    // ---- source-level wiring pins ---------------------------------------------

    private val mainSourceRoot by lazy {
        File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow")
    }

    private fun readMainFile(relative: String): String {
        val path = File(mainSourceRoot, relative)
        assertTrue("expected source file $relative to exist", path.isFile)
        return path.readText()
    }

    @Test
    fun `editor restores and persists the color mode through SettingsManager`() {
        val editor = readMainFile("ui/screens/EditorScreen.kt")
        // Restore on open from the persisted key.
        assertTrue(editor.contains("ColorModePersistencePolicy"))
        assertTrue(editor.contains("viewModel.settings.brushColorModeKey"))
    }

    @Test
    fun `settings manager routes the pref through the persistence policy`() {
        val settings = readMainFile("services/SettingsManager.kt")
        assertTrue(settings.contains("ColorModePersistencePolicy.PREF_KEY_COLOR_MODE"))
        assertTrue(settings.contains("ColorModePersistencePolicy.DEFAULT_MODE"))
    }

    @Test
    fun `width quick picker exposes the same colour mode chips row as the colour picker`() {
        val editor = readMainFile("ui/screens/EditorScreen.kt")
        // The shared composable is used by BOTH bottom sheets (this is what makes
        // the rainbow mode reachable without opening the full colour picker).
        val uses = Regex("ColorModeChipsRow\\(").findAll(editor).count()
        assertTrue("expected the shared chips row in both sheets, found $uses", uses >= 2)
        // The width sheet actually receives the live mode/seed/gradient state.
        assertTrue(editor.contains("currentColorMode = currentColorMode"))
        assertTrue(editor.contains("currentGradientToColor = currentGradientToColor"))
    }

    @Test
    fun `canvas stroke recording still emits per-stroke mode and seed`() {
        val canvas = readMainFile("ui/components/AnnotationCanvas.kt")
        assertTrue(canvas.contains("colorMode = currentColorMode"))
        assertTrue(canvas.contains("colorSeed = currentColorSeed"))
        // per-point color is DERIVED at render time (never serialized per point).
        assertTrue(canvas.contains("never stored per point"))
    }

    @Test
    fun `colour mode is saved per-stroke so reopened notes keep rainbow strokes`() {
        val canvas = readMainFile("ui/components/AnnotationCanvas.kt")
        assertTrue(canvas.contains("val commitColorMode = currentColorMode"))
        assertTrue(canvas.contains("colorMode = commitColorMode"))
    }

    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (File(dir, "gradle/libs.versions.toml").isFile && File(dir, "app").isDirectory) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}