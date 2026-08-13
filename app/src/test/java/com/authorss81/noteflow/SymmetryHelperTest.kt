package com.authorss81.noteflow

import com.authorss81.noteflow.services.SymmetryHelper
import com.authorss81.noteflow.services.SymmetryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SymmetryHelperTest {

    private fun mirror(x: Float, y: Float, mode: SymmetryMode, cx: Float = 200f, cy: Float = 100f) =
        SymmetryHelper.mirrorPoint(x, y, mode, cx, cy)

    @Test
    fun `OFF is identity`() {
        val m = mirror(37f, 91f, SymmetryMode.OFF)
        assertEquals(37f, m.x, 1e-5f)
        assertEquals(91f, m.y, 1e-5f)
        assertFalse(SymmetryHelper.isActive(SymmetryMode.OFF))
    }

    @Test
    fun `vertical mirror reflects x and keeps y`() {
        val m = mirror(50f, 80f, SymmetryMode.VERTICAL)
        // symmetric about centerX=200: x' = 2*200 - 50 = 350
        assertEquals(350f, m.x, 1e-5f)
        assertEquals(80f, m.y, 1e-5f)
        assertTrue(SymmetryHelper.isActive(SymmetryMode.VERTICAL))
    }

    @Test
    fun `horizontal mirror reflects y and keeps x`() {
        val m = mirror(50f, 30f, SymmetryMode.HORIZONTAL)
        // symmetric about centerY=100: y' = 2*100 - 30 = 170
        assertEquals(50f, m.x, 1e-5f)
        assertEquals(170f, m.y, 1e-5f)
    }

    @Test
    fun `radial mirror reflects both axes`() {
        val m = mirror(50f, 30f, SymmetryMode.RADIAL)
        assertEquals(350f, m.x, 1e-5f)
        assertEquals(170f, m.y, 1e-5f)
    }

    @Test
    fun `mirror twice returns the original for every mode`() {
        SymmetryMode.entries.forEach { mode ->
            val first = mirror(73f, 41f, mode)
            val second = mirror(first.x, first.y, mode)
            assertEquals("${mode} double-mirror x", 73f, second.x, 1e-4f)
            assertEquals("${mode} double-mirror y", 41f, second.y, 1e-4f)
        }
    }

    @Test
    fun `points exactly on the mirror axis stay put`() {
        val onAxisV = mirror(200f, 77f, SymmetryMode.VERTICAL)
        assertEquals(200f, onAxisV.x, 1e-5f)
        assertEquals(77f, onAxisV.y, 1e-5f)

        val onAxisH = mirror(66f, 100f, SymmetryMode.HORIZONTAL)
        assertEquals(66f, onAxisH.x, 1e-5f)
        assertEquals(100f, onAxisH.y, 1e-5f)

        val onCenter = mirror(200f, 100f, SymmetryMode.RADIAL)
        assertEquals(200f, onCenter.x, 1e-5f)
        assertEquals(100f, onCenter.y, 1e-5f)
    }

    @Test
    fun `mirrorPoints preserves length and maps every point`() {
        val pts = listOf(1f to 2f, 3f to 4f, 5f to 6f)
        val out = SymmetryHelper.mirrorPoints(pts, SymmetryMode.VERTICAL, 200f, 100f)
        assertEquals(pts.size, out.size)
        assertEquals(399f to 2f, out[0])
        assertEquals(397f to 4f, out[1])
        assertEquals(395f to 6f, out[2])
    }

    @Test
    fun `setting key round-trips and falls back to OFF`() {
        assertEquals(SymmetryMode.OFF, SymmetryMode.fromSettingKey("off"))
        assertEquals(SymmetryMode.VERTICAL, SymmetryMode.fromSettingKey("vertical"))
        assertEquals(SymmetryMode.HORIZONTAL, SymmetryMode.fromSettingKey("horizontal"))
        assertEquals(SymmetryMode.RADIAL, SymmetryMode.fromSettingKey("radial"))
        assertEquals(SymmetryMode.OFF, SymmetryMode.fromSettingKey("nonsense"))
        assertEquals(SymmetryMode.OFF, SymmetryMode.fromSettingKey(null))
    }
}
