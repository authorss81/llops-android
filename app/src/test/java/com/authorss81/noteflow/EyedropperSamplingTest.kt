package com.authorss81.noteflow

import com.authorss81.noteflow.services.EyedropperSamplingMath
import com.authorss81.noteflow.services.EyedropperSamplingMath.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 225 — eyedropper reference-image + layer sampling math (pure JVM).
 *
 * Covers the three things the task pins down: offset→bitmap coordinate for a
 * reference underlay in world space, out-of-bounds fallback, and the
 * reference-hit-vs-layer-hit resolution PRIORITY (reference wins on a tap
 * inside its bounds).
 */
class EyedropperSamplingTest {

    // ---- offset -> reference-bitmap coordinate ------------------------------

    @Test
    fun `referencePixel maps a centred tap to the bitmap centre`() {
        // 1000x800 reference placed centred in a 2000x1600 page at pageTopY=0.
        val refX = 500f
        val refY = 400f
        val refW = 1000f
        val refH = 800f
        val bmpW = 200
        val bmpH = 160
        val px = EyedropperSamplingMath.referencePixel(
            canvasX = 1000f, canvasY = 800f, pageTopY = 0f,
            refX = refX, refY = refY, refWidth = refW, refHeight = refH,
            bitmapWidth = bmpW, bitmapHeight = bmpH
        )
        assertEquals(100, px!!.first)
        assertEquals(80, px.second)
    }

    @Test
    fun `referencePixel maps a top-left tap to bitmap origin`() {
        val px = EyedropperSamplingMath.referencePixel(
            canvasX = 500f, canvasY = 400f, pageTopY = 0f,
            refX = 500f, refY = 400f, refWidth = 1000f, refHeight = 800f,
            bitmapWidth = 200, bitmapHeight = 160
        )
        assertEquals(0, px!!.first)
        assertEquals(0, px.second)
    }

    @Test
    fun `referencePixel honours the paginated pageTopY offset`() {
        // Continuous mode: page 1 starts at pageTopY = pageHeight+pageGap.
        val pageTopY = 1600f
        val refY = 100f // reference's own y within its page
        val px = EyedropperSamplingMath.referencePixel(
            canvasX = 1000f, canvasY = pageTopY + 100f + 400f, pageTopY = pageTopY,
            refX = 500f, refY = refY, refWidth = 1000f, refHeight = 800f,
            bitmapWidth = 200, bitmapHeight = 160
        )
        // localY = (1600+500) - (1600+100) = 400 → 400/800*160 = 80
        assertEquals(100, px!!.first)
        assertEquals(80, px.second)
    }

    // ---- out-of-bounds fallback --------------------------------------------

    @Test
    fun `referencePixel returns null when tap is left of the reference`() {
        val px = EyedropperSamplingMath.referencePixel(
            canvasX = 499f, canvasY = 800f, pageTopY = 0f,
            refX = 500f, refY = 400f, refWidth = 1000f, refHeight = 800f,
            bitmapWidth = 200, bitmapHeight = 160
        )
        assertNull(px)
    }

    @Test
    fun `referencePixel returns null when tap is right of the reference`() {
        val px = EyedropperSamplingMath.referencePixel(
            canvasX = 1501f, canvasY = 800f, pageTopY = 0f,
            refX = 500f, refY = 400f, refWidth = 1000f, refHeight = 800f,
            bitmapWidth = 200, bitmapHeight = 160
        )
        assertNull(px)
    }

    @Test
    fun `referencePixel returns null when tap is below the reference`() {
        val px = EyedropperSamplingMath.referencePixel(
            canvasX = 1000f, canvasY = 1201f, pageTopY = 0f,
            refX = 500f, refY = 400f, refWidth = 1000f, refHeight = 800f,
            bitmapWidth = 200, bitmapHeight = 160
        )
        assertNull(px)
    }

    @Test
    fun `referencePixel returns null for a zero-size or empty bitmap`() {
        assertNull(
            EyedropperSamplingMath.referencePixel(
                1000f, 800f, 0f, 500f, 400f, 0f, 800f, 200, 160
            )
        )
        assertNull(
            EyedropperSamplingMath.referencePixel(
                1000f, 800f, 0f, 500f, 400f, 1000f, 800f, 0, 160
            )
        )
    }

    @Test
    fun `referencePixel clamps a tap exactly on the far edge to the last pixel`() {
        val px = EyedropperSamplingMath.referencePixel(
            canvasX = 1500f, canvasY = 1200f, pageTopY = 0f,
            refX = 500f, refY = 400f, refWidth = 1000f, refHeight = 800f,
            bitmapWidth = 200, bitmapHeight = 160
        )
        assertEquals(199, px!!.first)
        assertEquals(159, px.second)
    }

    @Test
    fun `referenceSamplingRect centres a 1x1 region clamped to bounds`() {
        val r = EyedropperSamplingMath.referenceSamplingRect(100, 80, 200, 160, margin = 1)
        assertEquals(99, r!!.left)
        assertEquals(79, r.top)
        assertEquals(102, r.right)
        assertEquals(82, r.bottom)
    }

    @Test
    fun `referenceSamplingRect clamps at the bitmap origin`() {
        val r = EyedropperSamplingMath.referenceSamplingRect(0, 0, 200, 160, margin = 1)
        assertEquals(0, r!!.left)
        assertEquals(0, r.top)
        assertEquals(2, r.right)
        assertEquals(2, r.bottom)
    }

    @Test
    fun `referenceSamplingRect returns null when the pixel is out of range`() {
        assertNull(EyedropperSamplingMath.referenceSamplingRect(200, 0, 200, 160))
        assertNull(EyedropperSamplingMath.referenceSamplingRect(-1, 0, 200, 160))
        assertNull(EyedropperSamplingMath.referenceSamplingRect(0, 160, 200, 160))
    }

    // ---- reference-hit vs layer-hit priority -------------------------------

    @Test
    fun `tap inside reference bounds resolves to the reference source`() {
        val px = EyedropperSamplingMath.referencePixel(
            1000f, 800f, 0f, 500f, 400f, 1000f, 800f, 200, 160
        )
        val referenceHit = px != null
        assertEquals(Source.REFERENCE, EyedropperSamplingMath.resolveSampleSource(referenceHit))
    }

    @Test
    fun `tap outside reference bounds resolves to the layer source`() {
        val px = EyedropperSamplingMath.referencePixel(
            100f, 100f, 0f, 500f, 400f, 1000f, 800f, 200, 160
        )
        val referenceHit = px != null
        assertNull(px)
        assertEquals(Source.LAYER, EyedropperSamplingMath.resolveSampleSource(referenceHit))
    }

    @Test
    fun `reference priority stands over an absent bitmap`() {
        // Even with a valid tap, an empty bitmap means no reference pixel → layer.
        val px = EyedropperSamplingMath.referencePixel(
            1000f, 800f, 0f, 500f, 400f, 1000f, 800f, 0, 0
        )
        assertNull(px)
        assertEquals(Source.LAYER, EyedropperSamplingMath.resolveSampleSource(px != null))
    }

    @Test
    fun `a tap within every reference bound is unambiguously a reference hit`() {
        val px = EyedropperSamplingMath.referencePixel(
            700f, 600f, 0f, 500f, 400f, 1000f, 800f, 200, 160
        )
        assertTrue(px != null)
        // Priority decision is TRUE for any in-bounds tap, independent of the
        // specific pixel — the ordering is the contract, not the pixel value.
        assertTrue(EyedropperSamplingMath.resolveSampleSource(true) == Source.REFERENCE)
    }
}
