package com.authorss81.noteflow

import com.authorss81.noteflow.services.PressureCurve
import com.authorss81.noteflow.services.PressureCurveHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class PressureCurveHelperTest {

    @Test
    fun `all curves preserve endpoints`() {
        PressureCurve.entries.forEach { curve ->
            assertEquals("${curve} remap(0)", 0f, PressureCurveHelper.remapPressure(0f, curve), 1e-5f)
            assertEquals("${curve} remap(1)", 1f, PressureCurveHelper.remapPressure(1f, curve), 1e-5f)
        }
    }

    @Test
    fun `all curves are strictly monotonic`() {
        PressureCurve.entries.forEach { curve ->
            var prev = PressureCurveHelper.remapPressure(0.05f, curve)
            var p = 0.1f
            while (p <= 1.0f) {
                val cur = PressureCurveHelper.remapPressure(p, curve)
                assertTrue("${curve} not monotonic at p=$p ($cur <= $prev)", cur > prev)
                prev = cur
                p += 0.05f
            }
        }
    }

    @Test
    fun `heavier curves respond less to low pressure`() {
        val low = 0.5f
        val heavy = PressureCurveHelper.remapPressure(low, PressureCurve.HEAVY)
        val linear = PressureCurveHelper.remapPressure(low, PressureCurve.LINEAR)
        val light = PressureCurveHelper.remapPressure(low, PressureCurve.LIGHT)
        assertTrue("HEAVY must compress the mid range", heavy < linear)
        assertTrue("LIGHT must expand the mid range", light > linear)
        assertEquals(0.5f, linear, 1e-5f)
        assertEquals(0.25f, heavy, 1e-5f)
        assertEquals(Math.sqrt(0.5), light.toDouble(), 1e-5)
    }

    @Test
    fun `width and opacity factors stay bounded and ordered`() {
        PressureCurve.entries.forEach { curve ->
            var prevW = PressureCurveHelper.widthFactor(0.05f, curve)
            var prevO = PressureCurveHelper.opacityFactor(0.05f, curve)
            var p = 0.1f
            while (p <= 1.0f) {
                val w = PressureCurveHelper.widthFactor(p, curve)
                val o = PressureCurveHelper.opacityFactor(p, curve)
                assertTrue(w in 0.5f..1.0f + 1e-5f)
                assertTrue(o in 0.6f..1.0f + 1e-5f)
                assertTrue("width not monotonic", w >= prevW - 1e-5f)
                assertTrue("opacity not monotonic", o >= prevO - 1e-5f)
                prevW = w
                prevO = o
                p += 0.05f
            }
            assertEquals(1f, PressureCurveHelper.widthFactor(1f, curve), 1e-5f)
            assertEquals(1f, PressureCurveHelper.opacityFactor(1f, curve), 1e-5f)
        }
    }

    @Test
    fun `setting key round-trips and falls back to LINEAR`() {
        assertEquals(PressureCurve.LINEAR, PressureCurve.fromSettingKey("linear"))
        assertEquals(PressureCurve.LIGHT, PressureCurve.fromSettingKey("light"))
        assertEquals(PressureCurve.HEAVY, PressureCurve.fromSettingKey("heavy"))
        assertEquals(PressureCurve.LINEAR, PressureCurve.fromSettingKey("nonsense"))
        assertEquals(PressureCurve.LINEAR, PressureCurve.fromSettingKey(null))
    }

    // ---- Phase 201 (PERF 1.3) goldens: SMOOTH gamma curve ----------------------

    @Test
    fun `smooth setting key round-trips`() {
        assertEquals(PressureCurve.SMOOTH, PressureCurve.fromSettingKey("smooth"))
    }

    @Test
    fun `golden smooth remap matches the p^(2-0_5p) gamma blend exactly`() {
        // p = 0.05 -> exponent ~1.975 -> 0.05^1.975
        val g05 = PressureCurveHelper.SMOOTH_GAMMA_AT_ZERO -
            (PressureCurveHelper.SMOOTH_GAMMA_AT_ZERO - PressureCurveHelper.SMOOTH_GAMMA_AT_ONE) * 0.05f
        assertEquals(
            0.05f.pow(g05),
            PressureCurveHelper.remapPressure(0.05f, PressureCurve.SMOOTH),
            1e-6f
        )
        // Mid-range golden: 0.5^1.75.
        assertEquals(
            0.5f.pow(1.75f),
            PressureCurveHelper.remapPressure(0.5f, PressureCurve.SMOOTH),
            1e-6f
        )
    }

    @Test
    fun `smooth sits between linear and heavy across the whole range`() {
        var p = 0.02f
        while (p <= 1.0f) {
            val smooth = PressureCurveHelper.remapPressure(p, PressureCurve.SMOOTH)
            assertTrue(
                "SMOOTH must compress vs LINEAR at p=$p ($smooth)",
                smooth < PressureCurveHelper.remapPressure(p, PressureCurve.LINEAR)
            )
            assertTrue(
                "SMOOTH must stay more responsive than HEAVY at p=$p ($smooth)",
                smooth > PressureCurveHelper.remapPressure(p, PressureCurve.HEAVY)
            )
            p += 0.02f
        }
    }

    @Test
    fun `golden smooth flattens width variation inside the low-end band`() {
        // Total width travel across the 0-10% band: SMOOTH must move far less
        // than LINEAR for the same raw-pressure span — that is the jitter fix.
        fun widthTravel(curve: PressureCurve): Float {
            val steps = 20
            var total = 0f
            var prev = PressureCurveHelper.widthFactor(0f, curve)
            for (i in 1..steps) {
                val w = PressureCurveHelper.widthFactor(0.10f * i / steps, curve)
                total += kotlin.math.abs(w - prev)
                prev = w
            }
            return total
        }
        val linearTravel = widthTravel(PressureCurve.LINEAR)
        val smoothTravel = widthTravel(PressureCurve.SMOOTH)
        assertTrue(
            "low-band width travel must shrink (smooth=$smoothTravel linear=$linearTravel)",
            smoothTravel < linearTravel * 0.25f
        )
        // Golden: the whole 0-10% band moves the width factor by less than 0.01.
        val bandSpan = PressureCurveHelper.widthFactor(0.10f, PressureCurve.SMOOTH) -
            PressureCurveHelper.widthFactor(0f, PressureCurve.SMOOTH)
        assertTrue("band span must stay under 0.01, was $bandSpan", bandSpan < 0.01f)
    }
}
