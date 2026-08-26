package com.authorss81.noteflow

import com.authorss81.noteflow.services.OneEuroStreamFilter
import com.authorss81.noteflow.services.StrokeSmoothingPolicy
import com.authorss81.noteflow.services.StrokeStabilizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Phase 214 (Stroke Smoothing v2) — Task 4: One-Euro adaptive model behind the
 * [StrokeStreamFilter] interface, selected by a sanitized preference.
 *
 * Pinned here:
 *  - key resolution fails safe to EWMA (unknown/hand-edited keys never select
 *    an unshipped model);
 *  - EWMA fallback parity is BIT-EXACT after any model round-trip;
 *  - One-Euro keeps its documented constants (minCutoff 1.2 Hz, window-derived
 *    beta) and its behaviour envelope (exact steady-state convergence, strong
 *    standstill jitter damping, tighter fast-ramp tracking than static-EWMA).
 */
class OneEuroParityTest {

    // ---- Model-key resolution ------------------------------------------------------

    @Test
    fun `model keys fail safe to classic ewma`() {
        assertEquals(StrokeSmoothingPolicy.MODEL_EWMA, StrokeSmoothingPolicy.sanitizeModelKey("ewma"))
        assertEquals(StrokeSmoothingPolicy.MODEL_ONE_EURO, StrokeSmoothingPolicy.sanitizeModelKey("one_euro"))
        assertEquals(StrokeSmoothingPolicy.MODEL_EWMA, StrokeSmoothingPolicy.sanitizeModelKey(null))
        assertEquals(StrokeSmoothingPolicy.MODEL_EWMA, StrokeSmoothingPolicy.sanitizeModelKey(""))
        assertEquals(StrokeSmoothingPolicy.MODEL_EWMA, StrokeSmoothingPolicy.sanitizeModelKey("kalman"))
        assertEquals(
            "hand-edited pref cannot smuggle an arbitrary string into capture",
            StrokeSmoothingPolicy.MODEL_EWMA,
            StrokeSmoothingPolicy.sanitizeModelKey("../../secrets")
        )
    }

    @Test
    fun `facade starts on ewma and reports its active model`() {
        val stabilizer = StrokeStabilizer.create()
        assertEquals(StrokeSmoothingPolicy.MODEL_EWMA, stabilizer.activeModelKey)
        stabilizer.selectModel(StrokeSmoothingPolicy.MODEL_ONE_EURO)
        assertEquals(StrokeSmoothingPolicy.MODEL_ONE_EURO, stabilizer.activeModelKey)
        stabilizer.selectModel("garbage-that-must-fall-back")
        assertEquals(StrokeSmoothingPolicy.MODEL_EWMA, stabilizer.activeModelKey)
    }

    // ---- EWMA fallback parity --------------------------------------------------------

    @Test
    fun `ewma output is bit-identical after a one-euro round-trip`() {
        val switched = StrokeStabilizer.create(windowSize = 8, prediction = StrokeStabilizer.DEFAULT_PREDICTION)
        val plain = StrokeStabilizer.create(windowSize = 8, prediction = StrokeStabilizer.DEFAULT_PREDICTION)

        // Round-trip the model BEFORE any input: switching back must leave a
        // canonical EWMA filter behind, so both facades now run IDENTICAL
        // state machines over the SAME deterministic input stream.
        switched.selectModel(StrokeSmoothingPolicy.MODEL_ONE_EURO)
        assertEquals(StrokeSmoothingPolicy.MODEL_ONE_EURO, switched.activeModelKey)
        switched.selectModel(StrokeSmoothingPolicy.MODEL_EWMA)
        assertEquals(StrokeSmoothingPolicy.MODEL_EWMA, switched.activeModelKey)

        val random = java.util.Random(5L)
        repeat(60) { i ->
            val x = i.toFloat() + (random.nextFloat() - 0.5f) * 8f
            val y = 40f + i * 0.3f + (random.nextFloat() - 0.5f) * 8f
            val p = 0.4f + random.nextFloat() * 0.1f
            val a = switched.next(x, y, p, null, null, null)
            val b = plain.next(x, y, p, null, null, null)
            assertEquals(a.x, b.x, 0f)
            assertEquals(a.y, b.y, 0f)
            assertEquals(a.pressure!!, b.pressure!!, 0f)
        }
    }

    @Test
    fun `re-selecting the current model never discards warm-up state`() {
        val stabilizer = StrokeStabilizer.create()
        stabilizer.next(-500f, -500f)
        stabilizer.next(-400f, -400f)
        stabilizer.selectModel(StrokeSmoothingPolicy.MODEL_EWMA) // no-op
        // State survived: the filter must NOT pass the next point through raw.
        val s = stabilizer.next(0f, 0f)
        assertTrue(abs(s.x) < 500f && abs(s.x) > 0.001f)
    }

    // ---- One-Euro behaviour envelope ---------------------------------------------------

    private fun betaForDefaultWindow() = StrokeSmoothingPolicy.oneEuroBetaFor(8)

    @Test
    fun `one euro constants and beta mapping`() {
        assertEquals(1.2f, StrokeSmoothingPolicy.ONE_EURO_MIN_CUTOFF_HZ, 0f)
        assertEquals(0.004f, StrokeSmoothingPolicy.oneEuroBetaFor(2), 1e-6f)
        assertEquals(0.03f, StrokeSmoothingPolicy.oneEuroBetaFor(12), 1e-6f)
        assertEquals(0.0196f, betaForDefaultWindow(), 1e-6f)
        var prev = 0f
        for (w in 2..12) {
            val b = StrokeSmoothingPolicy.oneEuroBetaFor(w)
            assertTrue(b >= prev)
            prev = b
        }
    }

    @Test
    fun `constant input converges exactly and stays put`() {
        val f = OneEuroStreamFilter(beta = 0.02f)
        val first = f.next(10f, 20f, 0.5f, 30f, null, 0L)
        assertEquals(10f, first.x, 0f)
        var last = first
        for (i in 1 until 120) {
            last = f.next(10f, 20f, 0.5f, 30f, 0f, i * 16L)
        }
        assertEquals(10f, last.x, 1e-4f)
        assertEquals(20f, last.y, 1e-4f)
        assertEquals(0.5f, last.pressure!!, 1e-4f)
    }

    @Test
    fun `standstill jitter is strongly damped`() {
        val f = OneEuroStreamFilter(beta = betaForDefaultWindow())
        val random = java.util.Random(21L)
        val raw = mutableListOf<Float>()
        val out = mutableListOf<Float>()
        var ts = 0L
        for (i in 0 until 300) {
            val x = 100f + (random.nextDouble() - 0.5) * 12.0
            raw.add(x.toFloat())
            ts += 16L
            out.add(f.next(x.toFloat(), 50f, null, null, 0f, ts).x)
        }
        val rawSpread = raw.zipWithNext().map { (a, b) -> abs(b - a) }.average()
        val outSpread = out.zipWithNext().map { (a, b) -> abs(b - a) }.average()
        assertTrue(
            "one-euro standstill jitter must collapse ($outSpread vs $rawSpread)",
            outSpread < rawSpread * 0.25
        )
    }

    @Test
    fun `fast ramp tracks tighter than the static window-8 ewma`() {
        // Identical constant-speed ramp (300 px/s => 5 px per 16 ms frame),
        // velocity supplied exactly as the canvas computes it (px/ms).
        val oneEuro = OneEuroStreamFilter(beta = betaForDefaultWindow())
        val ewma = StrokeStabilizer.create(windowSize = 8, prediction = 0f)

        var lagOneEuro = 0.0
        var lagEwma = 0.0
        var xRaw = 0f
        var ts = 0L
        // Warm-up so both filters reach their steady state before measuring.
        repeat(200) {
            xRaw += 5f
            ts += 16L
            val oe = oneEuro.next(xRaw, 0f, null, null, 0.3f, ts).x
            val e = ewma.next(xRaw, 0f).x
            if (it >= 100) {
                lagOneEuro += (xRaw - oe).toDouble()
                lagEwma += (xRaw - e).toDouble()
            }
        }
        lagOneEuro /= 100.0
        lagEwma /= 100.0
        assertTrue(
            "one-euro must track the fast ramp with less lag (oe=$lagOneEuro vs ewma=$lagEwma)",
            lagOneEuro < lagEwma * 0.75
        )
    }

    // ---- Wiring pins ---------------------------------------------------------------------

    private fun readSource(relative: String): String {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            val d: File = dir
            if (File(d, "app/src/main/kotlin/com/authorss81/noteflow").isDirectory) return File(
                d,
                "app/src/main/kotlin/com/authorss81/noteflow/$relative"
            ).readText()
            dir = d.parentFile
        }
        error("repo root not found from $start")
    }

    @Test
    fun `settings manager persists sanitized model + tension prefs`() {
        val settings = readSource("services/SettingsManager.kt")
        assertTrue(settings.contains("\"stroke_stabilizer_prediction_percent\""))
        assertTrue(settings.contains("StrokeSmoothingPolicy.sanitizePredictionPercent"))
        assertTrue(settings.contains("\"stroke_stabilizer_model_key\""))
        assertTrue(settings.contains("StrokeSmoothingPolicy.sanitizeModelKey"))
    }
}
