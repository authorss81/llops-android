package com.authorss81.noteflow

import com.authorss81.noteflow.services.PaperGrainPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 200 (PERF 3.3): the paper-grain decision table — deterministic
 * tileable noise, bounded alpha envelope, low-end gating and the static LRU
 * budget that `PaperGrainTileCache` executes.
 */
class PaperGrainPolicyTest {

    @Test
    fun `noise is deterministic across repeated calls`() {
        for (dark in listOf(false, true)) {
            for (y in intArrayOf(0, 7, 100, 191)) {
                for (x in intArrayOf(0, 3, 88, 191)) {
                    assertEquals(
                        "noise must be deterministic",
                        PaperGrainPolicy.noiseAt(x, y, dark),
                        PaperGrainPolicy.noiseAt(x, y, dark),
                        0f
                    )
                }
            }
        }
    }

    @Test
    fun `noise field is tileable at TILE_SIZE_PX`() {
        // Structural tileability: folding through the modulo means the wrap
        // seam columns/rows are IDENTICAL, so BitmapShader REPEAT is seamless.
        val t = PaperGrainPolicy.TILE_SIZE_PX
        for (dark in listOf(false, true)) {
            for (i in 0 until t) {
                assertEquals("column seam differs", PaperGrainPolicy.noiseAt(i, 5, dark), PaperGrainPolicy.noiseAt(i + t, 5, dark), 0f)
                assertEquals("row seam differs", PaperGrainPolicy.noiseAt(5, i, dark), PaperGrainPolicy.noiseAt(5, i + t, dark), 0f)
            }
        }
    }

    @Test
    fun `noise fills the unit range and the two families differ`() {
        var min = 1f; var max = 0f
        for (y in 0 until PaperGrainPolicy.TILE_SIZE_PX) {
            for (x in 0 until PaperGrainPolicy.TILE_SIZE_PX) {
                val n = PaperGrainPolicy.noiseAt(x, y, false)
                assertTrue("noise out of [0,1)", n >= 0f && n < 1f)
                if (n < min) min = n
                if (n > max) max = n
            }
        }
        assertTrue("field too flat to be visible grain: [$min,$max]", max - min > 0.9f)
        assertNotEquals(
            "light and dark families must use different fields",
            PaperGrainPolicy.noiseAt(11, 23, false),
            PaperGrainPolicy.noiseAt(11, 23, true),
            0f
        )
    }

    @Test
    fun `pixel alpha stays within the family envelope`() {
        for (dark in listOf(false, true)) {
            val cap = PaperGrainPolicy.speckleMaxAlpha(dark)
            for (raw in listOf(-1f, 0f, 0.3f, 0.91f, 0.93f, 0.999f, 1f, Float.NaN, Float.POSITIVE_INFINITY)) {
                val a = PaperGrainPolicy.pixelAlphaAt(raw, dark)
                assertTrue("alpha $a out of range for raw=$raw", a >= 0f && a <= cap)
            }
            // NaN / Infinity fail safe to invisible
            assertEquals(0f, PaperGrainPolicy.pixelAlphaAt(Float.NaN, dark), 0f)
            assertEquals(0f, PaperGrainPolicy.pixelAlphaAt(Float.POSITIVE_INFINITY, dark), 0f)
        }
    }

    @Test
    fun `fleck band is stronger than the flat tooth`() {
        val tooth = PaperGrainPolicy.pixelAlphaAt(0.5f, false)
        val fleck = PaperGrainPolicy.pixelAlphaAt(1f, false)
        assertTrue("fleck must dominate the tooth", fleck > tooth * 2f)
        // monotonic non-decreasing along the noise axis
        var prev = 0f
        var v = 0f
        while (v <= 1f) {
            val a = PaperGrainPolicy.pixelAlphaAt(v, false)
            assertTrue("alpha curve must not dip", a >= prev)
            prev = a
            v += 0.01f
        }
    }

    @Test
    fun `low-end devices disable the grain entirely`() {
        assertFalse(PaperGrainPolicy.enabled(deviceTierLowEnd = true))
        assertTrue(PaperGrainPolicy.enabled(deviceTierLowEnd = false))
    }

    @Test
    fun `cache keys are distinct per paper family and stable`() {
        assertNotEquals(PaperGrainPolicy.cacheKey(false), PaperGrainPolicy.cacheKey(true))
        assertEquals(PaperGrainPolicy.cacheKey(false), PaperGrainPolicy.cacheKey(false))
        assertEquals(PaperGrainPolicy.cacheKey(true), PaperGrainPolicy.cacheKey(true))
    }

    @Test
    fun `resident budget matches the LRU cap`() {
        val expected = PaperGrainPolicy.TILE_SIZE_PX.toLong() *
            PaperGrainPolicy.TILE_SIZE_PX * 4L * PaperGrainPolicy.MAX_CACHED_TILES
        assertEquals(expected, PaperGrainPolicy.maxResidentBytes())
        // worst case is comfortably sub-megabyte-scale cosmetic memory
        assertTrue("grain budget too large: ${PaperGrainPolicy.maxResidentBytes()}", PaperGrainPolicy.maxResidentBytes() <= 4L * 1024 * 1024)
    }

    @Test
    fun `speckle tints match their paper families`() {
        assertEquals(PaperGrainPolicy.LIGHT_SPECKLE_RGB, PaperGrainPolicy.speckleRgb(false))
        assertEquals(PaperGrainPolicy.DARK_SPECKLE_RGB, PaperGrainPolicy.speckleRgb(true))
        assertTrue("dark-paper speckle must be lighter than light-paper speckle",
            PaperGrainPolicy.speckleRgb(true) > PaperGrainPolicy.speckleRgb(false))
    }
}
