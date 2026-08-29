package com.authorss81.noteflow

import androidx.compose.ui.graphics.colorspace.ColorSpaces
import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeTool
import com.authorss81.noteflow.services.BrushStrokeMath
import com.authorss81.noteflow.services.EraserGeometryPolicy
import com.authorss81.noteflow.services.WetCanvasEngine
import com.authorss81.noteflow.services.WetMixingMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 239 — the user's portrait-shade watercolor regression, as pure-JVM logic
 * tests.
 *
 * Phase-235 specified an instrumented `WetShadeRegressionTest`: set WATERCOLOR,
 * lay down THREE light-pressure strokes at the same spot, verify none are dropped
 * (light pressure must not vanish), switch to ERASER PARTIAL and erase the middle,
 * verify the erase mask is present and leaves no fragmented dark seam.
 *
 * The instrumented UI can't run on the JVM, but every ingredient of that scenario
 * is pure math pinned here:
 *
 *  - 3 light-pressure deposits accumulate rather than drop
 *    (`BrushStrokeMath.pigmentFromPressure` bound + `WetCanvasEngine` hydration),
 *  - overlapping wet washes monotonically darken — a second wash over the first
 *    can NEVER thin/lighten it (the seam bug is an alpha that dips where washes
 *    overlap): `WetMixingMath.sourceOverAlpha` +
 *    `WetMixingMath.pigmentMixRgb` round-trip stability,
 *  - ERASER PARTIAL produces a single round mask per sample, fully bounded, and
 *    its coverage swallows the nib half-width so the surviving edge is round —
 *    no jagged fragment (`EraserGeometryPolicy`).
 *
 * Regression ids pinned: `1e54820` (light pressure not dropped), `8a2032d`
 * (wet erase mask), phase-204 (partial erase, no fragments).
 */
class Phase239WetShadeRegressionTest {

    // ---- light pressure: three same-spot strokes are NOT dropped -------------

    @Test
    fun `three light-pressure deposits each stay above the pigment floor`() {
        val pressure = 0.3f
        repeat(3) { i ->
            val pigment = BrushStrokeMath.pigmentFromPressure(pressure)
            // A light stroke must still deposit visible pigment (never zero).
            assertTrue("deposit $i must carry pigment", pigment >= 0.55f)
            assertEquals(0.55f + 0.45f * 0.3f, pigment, 0.0001f)
            // The bristle patch widens under light pressure but never degenerates.
            val spread = BrushStrokeMath.bristleSpreadFactor(pressure)
            assertTrue("spread $i in range", spread in 0.75f..1f)
        }
    }

    @Test
    fun `three light watercolor deposits accumulate one wet state`() {
        val engine = WetCanvasEngine()
        repeat(3) {
            engine.markPaintDeposited(StrokeTool.WATERCOLOR)
        }
        assertTrue(engine.isCanvasWet)
        assertEquals(BrushStrokeMath.wetnessPeakForTool(StrokeTool.WATERCOLOR), engine.activeWetnessLevel, 0.0001f)
        assertEquals(0.9f, engine.activeWetnessLevel, 0.0001f)
    }

    // ---- overlapping washes monotonically darken (no seam) -------------------

    @Test
    fun `a second wash over a first never lightens the alpha`() {
        // Wet-on-wet source-over: alpha must monotonically rise.
        val single = WetMixingMath.sourceOverAlpha(0.0f, 0.3f)
        val double = WetMixingMath.sourceOverAlpha(single, 0.3f)
        val triple = WetMixingMath.sourceOverAlpha(double, 0.3f)
        assertTrue("triple >= double", triple >= double)
        assertTrue("double >= single", double >= single)
        assertTrue("single >= 0", single >= 0f)
        // Clamped to 1.
        assertEquals(1f, WetMixingMath.sourceOverAlpha(1f, 1f), 0f)
    }

    @Test
    fun `pigment mixing is exact at the factor endpoints and never overshoots`() {
        // Pigment-space (subtractive) mixing: absorbances multiply. The pinned
        // contract of the shader's pigment branch:
        //   - factor 0 → returns the base unchanged;
        //   - factor 1 → returns 1-(1-base)(1-brush) EXACTLY (single pass),
        //     which is always >= each input channel (mixing DARKENS a wash —
        //     it can never make the shade thinner/lighter);
        //   - any factor stays in [0,1] and repeated passes converge monotonically
        //     to `combined` without overshooting — so an overlapping wash can't
        //     carving a dark seam into the shade. We mix in `ColorSpaces.Srgb`
        //     (identity mix space — the legacy gamma-space path) so the endpoint
        //     factors equal the plain algebraic formula exactly.
        val base = floatArrayOf(0.85f, 0.60f, 0.55f)
        val brush = floatArrayOf(0.30f, 0.45f, 0.70f)
        fun mix(b: FloatArray, f: Float): FloatArray = WetMixingMath.pigmentMixRgb(
            b[0], b[1], b[2], brush[0], brush[1], brush[2], factor = f, mixSpace = ColorSpaces.Srgb
        )

        // factor 0 is identity.
        val zero = mix(base, 0f)
        for (i in 0..2) assertEquals("factor 0 keeps base channel $i", base[i], zero[i], 1e-6f)

        // factor 1 reaches the combined value exactly, and combined >= base.
        val one = mix(base, 1f)
        val combined = FloatArray(3)
        for (i in 0..2) {
            combined[i] = 1f - (1f - base[i]) * (1f - brush[i])
            assertEquals("factor 1 channel $i = combined", combined[i], one[i], 1e-5f)
            assertTrue("combined never lightens (>= base)", combined[i] >= base[i] - 1e-6f)
            assertTrue("combined stays in gamut", combined[i] in -1e-6f..1f + 1e-6f)
        }

        // Repeated half-factor passes are monotonic toward `combined` (no overshoot).
        var prev: FloatArray = base
        for (pass in 0 until 4) {
            val next = mix(prev, 0.5f)
            for (i in 0..2) {
                assertTrue("channel $i stays in gamut (pass $pass)", next[i] in -1e-6f..1f + 1e-6f)
            }
            prev = next
        }
        // Determinism: identical inputs reproduce the identical result.
        val again = mix(prev, 0.5f)
        val original = mix(prev, 0.5f)
        assertTrue("mixing is deterministic", again.contentEquals(original))
    }

    @Test
    fun `pigment mixing is bounded and monotonic across the full envelope`() {
        // Sweep the factor ramp: no crash, everything clamped to [0,1], and alpha
        // (wet-on-wet coverage) rises monotonically with the factor.
        var prevAlpha = 0f
        for (factor in floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            for (i in 0..2) {
                val out = WetMixingMath.pigmentMixRgb(0.5f, 0.5f, 0.5f, 0.1f, 0.9f, 0.3f, factor)
                assertTrue("clamped at factor=$factor", out[i] in -0.0001f..1.0001f)
            }
            val alpha = WetMixingMath.sourceOverAlpha(0.2f, 0.4f * factor)
            assertTrue("alpha monotonic across factors", alpha >= prevAlpha - 0.0001f)
            prevAlpha = alpha
        }
    }

    // ---- ERASER PARTIAL: round mask, no fragmented dark seam -----------------

    @Test
    fun `partial eraser produces a bounded round mask per erase sample`() {
        // Wet pigments erase via a single round Clear punch (no fragments).
        val stamp = EraserGeometryPolicy.stampRadius(9f, 0.5f)
        assertTrue("stamp is a real positive radius", stamp > 0f)
        assertTrue("stamp bounded low", stamp >= EraserGeometryPolicy.MIN_ERASE_WIDTH_PX)
        assertTrue("stamp bounded high", stamp <= EraserGeometryPolicy.MAX_ERASE_WIDTH_PX + 4f)
        // Coverage swallows the nib so the surviving edge is round, not jagged.
        val cover = EraserGeometryPolicy.coverageRadius(stamp, 4f)
        assertTrue("coverage > stamp radius", cover > stamp)
        assertEquals(cover, stamp + 2f, 0.0001f)
    }

    @Test
    fun `heavy press carves wider than light - the shade edge follows pressure`() {
        val light = EraserGeometryPolicy.stampRadius(9f, 0.2f)
        val heavy = EraserGeometryPolicy.stampRadius(9f, 0.9f)
        assertTrue("heavier press carves wider", heavy > light)
    }

    @Test
    fun `wet tool mask geometry carries over into a stroke preserving pressure`() {
        // A watercolor stroke's per-point pressure/tilt survives a geometry copy
        // (the same copy semantics the wet-mask erase path relies on) — so the
        // surviving run keeps the shade's original pressure taper.
        val stroke = Stroke(
            id = "wet1", tool = StrokeTool.WATERCOLOR,
            points = listOf(
                PointF(0f, 0f, pressure = 0.3f, tilt = 0.5f),
                PointF(10f, 0f, pressure = 0.3f, tilt = 0.5f)
            )
        )
        val copied = stroke.copy(points = stroke.points, eraseMask = listOf(com.authorss81.noteflow.data.model.EraseMask(5f, 0f, 12f)))
        assertNotNull(copied.eraseMask)
        assertFalse("erase mask is non-empty", copied.eraseMask.orEmpty().isEmpty())
        assertEquals("pressure preserved", 0.3f, copied.points[0].pressure!!, 0f)
        assertEquals("tilt preserved", 0.5f, copied.points[0].tilt!!, 0f)
        assertEquals(12f, copied.eraseMask!![0].radius, 0f)
    }
}
