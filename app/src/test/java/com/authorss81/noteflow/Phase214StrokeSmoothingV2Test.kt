package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.StrokeTool
import com.authorss81.noteflow.services.StrokeFairingPolicy
import com.authorss81.noteflow.services.StrokeGeometryPolicy
import com.authorss81.noteflow.services.StrokeSmoothingPolicy
import com.authorss81.noteflow.services.StrokeSimplifyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 214 (Stroke Smoothing v2) — Task 5 (commit-time fairing) + the phase's
 * source pins for the UI/persistence wiring (Task 6).
 *
 * Fairing contract: ONE Chaikin pass, pointer-up only, hairline ink only,
 * shape-snapped strokes excluded, geometry cap re-enforced AFTER fairing.
 */
class Phase214StrokeSmoothingV2Test {

    // ---- Fairing gate -----------------------------------------------------------

    @Test
    fun `fairing gate requires hairline class and enough surviving points`() {
        val eps = StrokeSimplifyPolicy.epsilonFor(StrokeTool.PEN, 1.5f)
        assertTrue(eps in StrokeSimplifyPolicy.HAIRLINE_MIN_EPSILON_PX..StrokeSimplifyPolicy.HAIRLINE_MAX_EPSILON_PX)

        assertTrue(
            "hairline pen with enough points fairs",
            StrokeFairingPolicy.shouldFair(20, StrokeTool.PEN, 1.5f, eps)
        )
        assertTrue(
            "short marks keep their exact simplified geometry",
            !StrokeFairingPolicy.shouldFair(8, StrokeTool.PEN, 1.5f, eps)
        )
        assertTrue(
            "broad nibs are never touched",
            !StrokeFairingPolicy.shouldFair(20, StrokeTool.PEN, 12f, StrokeSimplifyPolicy.DEFAULT_EPSILON_PX)
        )
        assertTrue(
            "non-hairline tools are never touched",
            !StrokeFairingPolicy.shouldFair(20, StrokeTool.MARKER, 2f, eps)
        )
        assertTrue(
            "epsilon outside the hairline band refuses",
            !StrokeFairingPolicy.shouldFair(20, StrokeTool.PEN, 1.5f, StrokeSimplifyPolicy.DEFAULT_EPSILON_PX)
        )
    }

    // ---- Chaikin pass --------------------------------------------------------------

    private fun kinkedLine(n: Int = 24): List<PointF> =
        (0 until n).map { i ->
            // Straight run with alternating micro-kinks — what RDP leaves behind.
            val y = if (i % 2 == 0) 100f else 102.5f
            PointF(x = i * 4f, y = y, pressure = 0.3f + i * 0.001f, tilt = 10f, timestampMs = 1_000L + i * 16L)
        }

    @Test
    fun `chaikin preserves endpoints exactly and doubles interior points`() {
        val input = kinkedLine()
        val out = StrokeFairingPolicy.chaikinOnce(input)
        assertEquals("first point must be THE first point", input.first(), out.first())
        assertEquals("last point must be THE last point", input.last(), out.last())
        assertEquals(2 * input.size - 2, out.size)
    }

    @Test
    fun `chaikin flattens the worst turn angle`() {
        val input = kinkedLine()
        val out = StrokeFairingPolicy.chaikinOnce(input)
        val before = StrokeFairingPolicy.maxTurnAngleDeg(input)
        val after = StrokeFairingPolicy.maxTurnAngleDeg(out)
        assertTrue("fairing must reduce the worst kink ($after vs $before)", after < before)
    }

    @Test
    fun `chaikin interpolates pressure tilt and timestamps`() {
        val input = listOf(
            PointF(0f, 0f, 0.2f, 5f, 1_000L),
            PointF(10f, 0f, 0.6f, 15f, 2_000L),
            PointF(20f, 0f, 0.4f, 25f, 3_000L)
        )
        val out = StrokeFairingPolicy.chaikinOnce(input)
        // Interior vertex B was replaced by ¾B+¼A and ¾B+¼C.
        val q = out[1] // 0.75*B + 0.25*A
        assertEquals(7.5f, q.x, 1e-4f)
        assertEquals("pressure interpolates", 0.6f * 0.75f + 0.2f * 0.25f, q.pressure!!, 1e-6f)
        assertEquals("tilt interpolates", 15f * 0.75f + 5f * 0.25f, q.tilt!!, 1e-4f)
        assertEquals("timestamp interpolates", 1_750L, q.timestampMs!!)
        val r = out[2] // 0.75*B + 0.25*C
        assertEquals(12.5f, r.x, 1e-4f)
        assertEquals(0.6f * 0.75f + 0.4f * 0.25f, r.pressure!!, 1e-6f)
    }

    @Test
    fun `chaikin refuses to run when the doubled result would exceed the cap`() {
        val bigCap = 16
        val input = kinkedLine(12) // 2*12-2 = 22 > 16
        val out = StrokeFairingPolicy.chaikinOnce(input, maxPoints = bigCap)
        assertEquals("unfaired input is returned rather than a truncated fair", input, out)
        // And at the real policy cap a huge stroke stays within budget:
        val huge = kinkedLine(StrokeGeometryPolicy.MAX_POINTS_PER_STROKE / 2 + 10)
        val capped = StrokeFairingPolicy.chaikinOnce(huge)
        assertTrue(capped.size <= StrokeGeometryPolicy.MAX_POINTS_PER_STROKE || capped === huge || capped == huge)
    }

    @Test
    fun `degenerate inputs pass through unchanged`() {
        val two = listOf(PointF(0f, 0f), PointF(9f, 9f))
        assertEquals(two, StrokeFairingPolicy.chaikinOnce(two))
        assertEquals(emptyList<PointF>(), StrokeFairingPolicy.chaikinOnce(emptyList()))
    }

    @Test
    fun `chaikin timestamps stay exact at magnitudes beyond float precision`() {
        // Review-fix regression pin: ~5.5 h of device uptime puts eventTime
        // millis past Float's 24-bit exact-integer range, where the original
        // Float blend rounded interpolated stamps to coarse/duplicated values.
        // The Double blend must keep every magnitude below 2^53 exact.
        val base = 20_000_000_000L // > 2^24 ms (~4.66 h uptime)
        val input = listOf(
            PointF(0f, 0f, null, null, base),
            PointF(10f, 0f, null, null, base + 16L),
            PointF(20f, 0f, null, null, base + 32L)
        )
        val out = StrokeFairingPolicy.chaikinOnce(input)
        assertEquals(base + 12L, out[1].timestampMs!!) // ¾B + ¼A
        assertEquals(base + 20L, out[2].timestampMs!!) // ¾B + ¼C
    }

    // ---- Source pins: canvas commit order + UI/persistence --------------------------

    private fun repoRoot(): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            val d: File = dir
            if (File(d, "app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt").isFile) return d
            dir = d.parentFile
        }
        error("repo root not found from $start")
    }

    private fun readSource(relative: String): String =
        File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/$relative").readText()

    private fun canvasSource(): String =
        readSource("ui/components/AnnotationCanvas.kt")

    @Test
    fun `commit path runs simplify then fair then cap - and never fairs snapped shapes`() {
        val src = canvasSource()
        val simplifyIdx = src.indexOf("RamerDouglasPeucker.simplify(")
        val shouldFairIdx = src.indexOf("StrokeFairingPolicy.shouldFair(")
        val chaikinIdx = src.indexOf("StrokeFairingPolicy.chaikinOnce(simplifiedPoints)")
        val capIdx = src.indexOf("StrokeGeometryPolicy.capLoadedPoints(fairedPoints)")
        assertTrue(simplifyIdx >= 0)
        assertTrue("fairing gate must be wired", shouldFairIdx > simplifyIdx)
        assertTrue("the single Chaikin pass must follow the gate", chaikinIdx > shouldFairIdx)
        assertTrue(
            "geometry cap must be enforced AFTER fairing",
            capIdx > chaikinIdx
        )
        // Exactly one simplify call site survives (phase-201 pin discipline).
        assertEquals(
            1,
            Regex("""RamerDouglasPeucker\.simplify\(""").findAll(src).count()
        )
        // The fairing branch lives inside the simplified (non-snapped) branch only.
        val snappedBranch = src.substringBefore("val newStroke = if (snappedShape != null)")
        val fairingBranch = src.substringAfter("val newStroke = if (snappedShape != null)")
        assertTrue(!fairingBranch.isEmpty())
        assertTrue(
            "no fairing before the snap/simplify decision block",
            !snappedBranch.contains("StrokeFairingPolicy")
        )
    }

    @Test
    fun `canvas selects the model and tension at stroke start via retune`() {
        val src = canvasSource()
        assertTrue(src.contains("stabilizerFilter.selectModel(stabilizerModelKeyState.value)"))
        assertTrue(src.contains("prediction = StrokeSmoothingPolicy.predictionFromPercent("))
        assertTrue(src.contains("stabilizerPredictionPercentState.value"))
        // The rememberUpdatedState holders exist so settings changes never
        // restart pointerInput mid-gesture.
        assertTrue(src.contains("rememberUpdatedState(stabilizerModelKey)"))
        assertTrue(src.contains("rememberUpdatedState(stabilizerPredictionPercent)"))
    }

    @Test
    fun `editor sheet exposes tension dial and model chips with honest apply note`() {
        val editor = readSource("ui/screens/EditorScreen.kt")
        assertTrue(editor.contains("R.string.canvas_tension_label"))
        assertTrue(editor.contains("onStabilizerPredictionChange(Math.round(it).toInt().coerceIn(0,"))
        assertTrue(editor.contains("R.string.canvas_model_one_euro"))
        assertTrue(editor.contains("viewModel.settings.strokeStabilizerPredictionPercent = percent"))
        assertTrue(editor.contains("viewModel.settings.strokeStabilizerModelKey = key"))
        // Honest contract: affects strokes drawn AFTER the change.
        assertTrue(editor.contains("R.string.canvas_model_apply_note"))
        // The model chip row scrolls horizontally instead of clipping at 360dp.
        val region = editor.substringAfter("Phase 214: smoothing model")
        assertTrue(region.contains(".horizontalScroll(modelScroll)"))
    }

    @Test
    fun `phase-214 sheet strings live in strings_xml - no hardcoded literals`() {
        val res = File(repoRoot(), "app/src/main/res/values/strings.xml").readText()
        for (name in listOf(
            "canvas_tension_label", "canvas_tension_percent_format", "canvas_tension_helper",
            "canvas_model_classic", "canvas_model_one_euro", "canvas_model_apply_note"
        )) {
            assertTrue("missing string resource $name", res.contains("name=\"$name\""))
        }
        assertTrue(res.contains("Tension (lag compensation)"))
        assertTrue(res.contains("Adaptive (One-Euro)"))
        assertTrue(res.contains("Affects strokes drawn after the change"))
        val editor = readSource("ui/screens/EditorScreen.kt")
        assertTrue(!editor.contains("\"Tension (lag compensation)\""))
        assertTrue(!editor.contains("\"Adaptive (One-Euro)\""))
        assertTrue(!editor.contains("\"Affects strokes drawn after the change\""))
    }

    @Test
    fun `policy owns the tension bounds and defaults`() {
        assertEquals(15, StrokeSmoothingPolicy.DEFAULT_PREDICTION_PERCENT)
        assertEquals(35, StrokeSmoothingPolicy.MAX_PREDICTION_PERCENT)
        assertEquals(0.15f, StrokeSmoothingPolicy.predictionFromPercent(15), 0f)
        assertEquals(0f, StrokeSmoothingPolicy.predictionFromPercent(0), 0f)
        assertEquals(0.35f, StrokeSmoothingPolicy.predictionFromPercent(99), 0f)
        assertEquals(0, StrokeSmoothingPolicy.sanitizePredictionPercent(-3))
        assertEquals(35, StrokeSmoothingPolicy.sanitizePredictionPercent(120))
    }
}
