package com.authorss81.noteflow

import com.authorss81.noteflow.services.PressureCurve
import com.authorss81.noteflow.services.PressureCurveHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}