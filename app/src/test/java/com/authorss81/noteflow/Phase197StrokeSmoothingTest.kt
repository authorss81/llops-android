package com.authorss81.noteflow

import com.authorss81.noteflow.services.BrushPresetFileCodec
import com.authorss81.noteflow.services.BrushPresetPack
import com.authorss81.noteflow.services.StrokeSmoothingPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 197 (PERF 1.2): per-brush stroke-stabilizer tuning + smoothing slider.
 *
 * Behavioral half: the [StrokeSmoothingPolicy] decision table (smoothing→window
 * mapping, slider trim, finger/stylus adjustment, sanitization) plus the pack
 * and codec contracts (every preset carries in-range smoothing; calligraphy
 * tighter than marker; `.inkbrush` bundles round-trip smoothing and old bundles
 * still decode).
 *
 * Source-pin half: pins the AnnotationCanvas wiring (input-source capture,
 * retune at stroke start from preset + slider + input source), the EditorScreen
 * slider + persistence path, and the SettingsManager pref key.
 */
class Phase197StrokeSmoothingTest {

    // ---- 1. Policy: smoothing → window mapping --------------------------------

    @Test
    fun `window bounds are 2 to 12`() {
        assertEquals(2, StrokeSmoothingPolicy.MIN_WINDOW_SIZE)
        assertEquals(12, StrokeSmoothingPolicy.MAX_WINDOW_SIZE)
    }

    @Test
    fun `smoothing maps linearly onto the window range`() {
        assertEquals(2, StrokeSmoothingPolicy.windowSizeForSmoothing(0.0f))
        assertEquals(4, StrokeSmoothingPolicy.windowSizeForSmoothing(0.15f))
        assertEquals(7, StrokeSmoothingPolicy.windowSizeForSmoothing(0.45f))
        assertEquals(8, StrokeSmoothingPolicy.windowSizeForSmoothing(0.60f))
        assertEquals(9, StrokeSmoothingPolicy.windowSizeForSmoothing(0.70f))
        assertEquals(11, StrokeSmoothingPolicy.windowSizeForSmoothing(0.90f))
        assertEquals(12, StrokeSmoothingPolicy.windowSizeForSmoothing(1.0f))
    }

    @Test
    fun `default smoothing reproduces the legacy window 8`() {
        assertEquals(
            "no-preset baseline must equal the pre-197 DEFAULT_WINDOW_SIZE",
            8,
            StrokeSmoothingPolicy.windowSizeForSmoothing(StrokeSmoothingPolicy.DEFAULT_SMOOTHING)
        )
    }

    @Test
    fun `sanitization clamps smoothing and percent`() {
        assertEquals(1f, StrokeSmoothingPolicy.sanitizeSmoothing(1.5f), 0f)
        assertEquals(0f, StrokeSmoothingPolicy.sanitizeSmoothing(-3f), 0f)
        assertEquals(
            StrokeSmoothingPolicy.DEFAULT_SMOOTHING,
            StrokeSmoothingPolicy.sanitizeSmoothing(null),
            0f
        )
        assertEquals(
            StrokeSmoothingPolicy.DEFAULT_SMOOTHING,
            StrokeSmoothingPolicy.sanitizeSmoothing(Float.NaN),
            0f
        )
        assertEquals(0, StrokeSmoothingPolicy.sanitizeSliderPercent(-5))
        assertEquals(37, StrokeSmoothingPolicy.sanitizeSliderPercent(37))
        assertEquals(100, StrokeSmoothingPolicy.sanitizeSliderPercent(250))
    }

    // ---- 2. Policy: effective window (preset × slider × input source) ----------

    @Test
    fun `golden stylus with no preset and default slider is legacy window 8`() {
        assertEquals(
            8,
            StrokeSmoothingPolicy.effectiveWindowSize(
                presetSmoothing = null,
                sliderPercent = StrokeSmoothingPolicy.DEFAULT_SLIDER_PERCENT,
                isStylus = true
            )
        )
    }

    @Test
    fun `golden minimum window 2 at zero smoothing or zero slider`() {
        assertEquals(
            2,
            StrokeSmoothingPolicy.effectiveWindowSize(presetSmoothing = 0.0f, sliderPercent = 100, isStylus = true)
        )
        assertEquals(
            "slider 0 always means raw input",
            2,
            StrokeSmoothingPolicy.effectiveWindowSize(presetSmoothing = 1.0f, sliderPercent = 0, isStylus = true)
        )
        assertEquals(
            2,
            StrokeSmoothingPolicy.effectiveWindowSize(presetSmoothing = null, sliderPercent = 0, isStylus = false)
        )
    }

    @Test
    fun `golden maximum window 12 at full smoothing`() {
        assertEquals(
            12,
            StrokeSmoothingPolicy.effectiveWindowSize(presetSmoothing = 1.0f, sliderPercent = 100, isStylus = true)
        )
    }

    @Test
    fun `finger input smooths more than stylus for the same settings`() {
        val stylus = StrokeSmoothingPolicy.effectiveWindowSize(presetSmoothing = 0.6f, sliderPercent = 100, isStylus = true)
        val finger = StrokeSmoothingPolicy.effectiveWindowSize(presetSmoothing = 0.6f, sliderPercent = 100, isStylus = false)
        assertTrue("finger ($finger) must exceed stylus ($stylus)", finger > stylus)
        assertEquals(10, finger)
    }

    @Test
    fun `finger boost never exceeds the window ceiling`() {
        assertEquals(
            12,
            StrokeSmoothingPolicy.effectiveWindowSize(presetSmoothing = 1.0f, sliderPercent = 100, isStylus = false)
        )
    }

    @Test
    fun `slider interpolates between raw and full brush smoothing`() {
        assertEquals(
            5,
            StrokeSmoothingPolicy.effectiveWindowSize(presetSmoothing = 0.6f, sliderPercent = 50, isStylus = true)
        )
        assertEquals(
            "100% must return the untouched brush baseline (window 8 here)",
            8,
            StrokeSmoothingPolicy.effectiveWindowSize(presetSmoothing = 0.6f, sliderPercent = 100, isStylus = true)
        )
        // Monotonicity across the whole dial.
        var prev = -1
        for (pct in 0..100 step 10) {
            val w = StrokeSmoothingPolicy.effectiveWindowSize(presetSmoothing = 0.45f, sliderPercent = pct, isStylus = true)
            assertTrue("window must never decrease as the slider rises ($pct)", w >= prev)
            prev = w
        }
    }

    @Test
    fun `prediction stays the pre-197 constant`() {
        assertEquals(StrokeSmoothingPolicy.PREDICTION, com.authorss81.noteflow.services.StrokeStabilizer.DEFAULT_PREDICTION, 0f)
    }

    // ---- 3. Brush presets ------------------------------------------------------

    @Test
    fun `every pack preset carries in-range smoothing`() {
        assertTrue(BrushPresetPack.validatePresetSmoothing().isEmpty())
        BrushPresetPack.all().forEach { preset ->
            assertTrue(
                "${preset.id} smoothing ${preset.smoothing} out of range",
                preset.smoothing in 0f..1f && !preset.smoothing.isNaN()
            )
        }
    }

    @Test
    fun `calligraphy is tighter than marker`() {
        val chalk = BrushPresetPack.byId("chalk")!!
        val marker = BrushPresetPack.byId("marker")!!
        assertEquals(com.authorss81.noteflow.data.model.StrokeTool.CALLIGRAPHIC, chalk.tool)
        assertTrue(
            "fast calligraphy (${chalk.smoothing}) must be tighter than broad marker (${marker.smoothing})",
            chalk.smoothing < marker.smoothing
        )
        // And the precise pencil is tightest of all.
        val pencil = BrushPresetPack.byId("pencil_graphite")!!
        assertTrue(pencil.smoothing <= chalk.smoothing)
    }

    @Test
    fun `pack windows stay inside the policy range for any input`() {
        BrushPresetPack.all().forEach { preset ->
            val stylus = StrokeSmoothingPolicy.effectiveWindowSize(preset.smoothing, 100, true)
            val finger = StrokeSmoothingPolicy.effectiveWindowSize(preset.smoothing, 100, false)
            assertTrue("${preset.id} stylus $stylus out of range", stylus in 2..12)
            assertTrue("${preset.id} finger $finger out of range", finger in 2..12)
        }
    }

    // ---- 4. .inkbrush codec carries smoothing -----------------------------------

    private fun samplePreset(smoothing: Float) = com.authorss81.noteflow.services.BrushPreset(
        id = "soft_watercolor",
        name = "Soft Watercolor",
        tool = com.authorss81.noteflow.data.model.StrokeTool.WATERCOLOR,
        brushParams = com.authorss81.noteflow.services.BrushStudioParams(
            dilution = 0.92f, charge = 0.45f, pull = 0.62f,
            impasto = 0.05f, paperGrain = 0.92f, splatterSpread = 0.35f
        ),
        size = 14f,
        colorHex = "#3B82F6",
        pressureCurveKey = "light",
        smoothing = smoothing
    )

    @Test
    fun `codec round-trips a custom smoothing value`() {
        val encoded = BrushPresetFileCodec.encode(samplePreset(smoothing = 0.25f))
        val result = BrushPresetFileCodec.decode(encoded)
        assertTrue(result is BrushPresetFileCodec.DecodeResult.Preset)
        assertEquals(0.25f, (result as BrushPresetFileCodec.DecodeResult.Preset).preset.smoothing, 0f)
    }

    @Test
    fun `pre-197 bundle without a smoothing key still decodes`() {
        // A bundle exactly as phase 155..196 wrote it (no smoothing field).
        val legacyJson = """
            {"format":"inkflow.brushpreset","version":1,"name":"Old Pen",
             "tool":"FOUNTAIN_PEN","size":4.0,"colorHex":"#1B365D",
             "pressureCurveKey":"heavy",
             "brushParams":{"dilution":0.3,"charge":0.92,"pull":0.45,
                            "impasto":0.1,"paperGrain":0.4,"splatterSpread":0.0}}
        """.trimIndent()
        val result = BrushPresetFileCodec.decode(legacyJson.toByteArray(Charsets.UTF_8))
        assertTrue(result is BrushPresetFileCodec.DecodeResult.Preset)
        assertEquals(
            StrokeSmoothingPolicy.DEFAULT_SMOOTHING,
            (result as BrushPresetFileCodec.DecodeResult.Preset).preset.smoothing,
            0f
        )
    }

    @Test
    fun `out-of-range smoothing in a bundle clamps into range instead of rejecting`() {
        val hostile = """
            {"format":"inkflow.brushpreset","version":1,"name":"Weird",
             "tool":"MARKER","size":9.0,"colorHex":"#9333EA",
             "pressureCurveKey":"linear","smoothing":42.0,
             "brushParams":{"dilution":0.18,"charge":0.96,"pull":0.5,
                            "impasto":0.0,"paperGrain":0.5,"splatterSpread":0.0}}
        """.trimIndent()
        val result = BrushPresetFileCodec.decode(hostile.toByteArray(Charsets.UTF_8))
        assertTrue(result is BrushPresetFileCodec.DecodeResult.Preset)
        assertEquals(
            "sanitizeSmoothing clamps 42.0 down to 1.0 (max smoothing), never rejects the file",
            1.0f,
            (result as BrushPresetFileCodec.DecodeResult.Preset).preset.smoothing,
            0f
        )
    }

    // ---- 5. Source pins: wiring --------------------------------------------------

    private fun source(rel: String): String = File(repoRoot(), rel).readText()

    private fun repoRoot(): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            val d: File = dir
            if (File(d, "app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt").isFile) return d
            dir = d.parentFile
        }
        return start
    }

    private fun canvasSource(): String =
        source("app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt")

    @Test
    fun `canvas captures the pointer tool type passively`() {
        val src = canvasSource()
        assertTrue(src.contains("motionEvent.getToolType(0)"))
        assertTrue(src.contains("TOOL_TYPE_STYLUS"))
        assertTrue(src.contains("TOOL_TYPE_ERASER"))
        assertTrue(src.contains("lastInputIsStylus"))
    }

    @Test
    fun `canvas retunes the stabilizer from preset + slider + input source`() {
        val src = canvasSource()
        assertTrue(src.contains("stabilizerFilter.retune("))
        assertTrue(src.contains("StrokeSmoothingPolicy.effectiveWindowSize("))
        assertTrue(src.contains("sliderPercent = stabilizerStrengthPercent"))
        assertTrue(src.contains("isStylus = lastInputIsStylus"))
        // Retune resolves the ACTIVE preset from both the pack and imports —
        // same resolver shape as the wet-engine preset wiring above it.
        assertTrue(src.contains("BrushPresetPack.byId(id)"))
        assertTrue(src.contains("importedBrushPresets.firstOrNull { it.id == id }"))
        // Tuning happens only while the stabilizer is enabled.
        val retuneIdx = src.indexOf("stabilizerFilter.retune(")
        val gateIdx = src.lastIndexOf("if (stabilizerEnabled)", retuneIdx)
        assertTrue("retune must sit behind the stabilizerEnabled gate", gateIdx >= 0)
        // New inputs join the drag-detector keys so stale closures are impossible.
        assertTrue(
            src.contains(".pointerInput(currentTool, currentColor, currentWidth, pdfPageFilter, isContinuousMode, activeRawBitmapMap, isLayerLocked, symmetryMode, stabilizerEnabled, eraserMode, activeLayerId, layers, stabilizerStrengthPercent, activeBrushPresetId, importedBrushPresets, rulerEnabled)")
        )
    }

    @Test
    fun `editor sheet exposes a persisted strength slider`() {
        val editor = source("app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt")
        assertTrue(editor.contains("\"Smoothing strength\""))
        assertTrue(editor.contains("onStabilizerStrengthChange(Math.round(it).toInt().coerceIn(0, 100))"))
        assertTrue(editor.contains("viewModel.settings.strokeStabilizerStrengthPercent = percent"))
        // Canvas receives the trimmed value.
        assertTrue(editor.contains("stabilizerStrengthPercent = stabilizerStrengthPercent"))
    }

    @Test
    fun `settings manager persists the sanitized percent`() {
        val settings = source("app/src/main/kotlin/com/authorss81/noteflow/services/SettingsManager.kt")
        assertTrue(settings.contains("stroke_stabilizer_strength_percent"))
        assertTrue(settings.contains("StrokeSmoothingPolicy.sanitizeSliderPercent"))
        assertTrue(settings.contains("StrokeSmoothingPolicy.DEFAULT_SLIDER_PERCENT"))
    }

    @Test
    fun `legacy stabilizer entry points remain intact`() {
        val src = canvasSource()
        assertTrue(src.contains("val stabilizerFilter = remember { StrokeStabilizer.create() }"))
        assertTrue(src.contains("stabilizerFilter.reset()"))
        // Phase 214: the capture path feeds the full-channel overload (raw
        // pressure + tilt + velocity + timestamp) so pressure is smoothed
        // BEFORE the curve remap; the legacy two-argument next(x, y) stays on
        // StrokeStabilizer itself for byte-parity (covered by StrokeStabilizerTest).
        assertTrue(src.contains("val s = stabilizerFilter.next("))
        assertTrue(src.contains("pressure = rawPressure,"))
        assertTrue(src.contains("velocityPxPerMs = velocity,"))
    }
}
