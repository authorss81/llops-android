package com.authorss81.noteflow

import com.authorss81.noteflow.services.QuickColorRingMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 155: pure-JVM layout + hit-testing of the long-press quick-color ring.
 */
class QuickColorRingMathTest {

    // ---- budget ---------------------------------------------------------------

    @Test
    fun cappedSwatchesClampsAtMax() {
        assertEquals(5, QuickColorRingMath.cappedSwatches(5))
        assertEquals(QuickColorRingMath.MAX_SWATCHES, QuickColorRingMath.cappedSwatches(100))
        assertEquals(0, QuickColorRingMath.cappedSwatches(0))
    }

    // ---- layout ---------------------------------------------------------------

    @Test
    fun ringLayoutReturnsEvenlySpacedCount() {
        val n = 8
        val centers = QuickColorRingMath.ringLayout(n, 500f, 500f)
        assertEquals(n, centers.size)
        // every swatch must sit on the mid-band circle
        val bandMid = (QuickColorRingMath.RING_OUTER_RADIUS_PX + QuickColorRingMath.RING_INNER_RADIUS_PX) / 2f
        for ((x, y) in centers) {
            val d = kotlin.math.hypot(x - 500f, y - 500f)
            assertEquals(bandMid, d, 1f)
        }
    }

    @Test
    fun swatchZeroSitsAtTwelveOClock() {
        val centers = QuickColorRingMath.ringLayout(4, 500f, 500f)
        val (x0, y0) = centers[0]
        assertEquals(500f, x0, 0.01f)
        assertTrue(y0 > 500f) // 12 o'clock = +Y in this space
    }

    @Test
    fun swatchesProceedClockwise() {
        val centers = QuickColorRingMath.ringLayout(4, 0f, 0f)
        // index 1 should be to the right of center, index 2 below, index 3 left.
        assertTrue(centers[1].first > 0f)
        assertTrue(centers[2].second < 0f)
        assertTrue(centers[3].first < 0f)
    }

    // ---- hit-testing ----------------------------------------------------------

    @Test
    fun hitIndexCenterDiscIsCenterSlot() {
        val centers = QuickColorRingMath.ringLayout(6, 500f, 500f)
        assertEquals(QuickColorRingMath.CENTER_SLOT, QuickColorRingMath.hitIndex(500f, 500f, 500f, 500f, centers))
        assertEquals(QuickColorRingMath.CENTER_SLOT, QuickColorRingMath.hitIndex(530f, 530f, 500f, 500f, centers))
    }

    @Test
    fun hitIndexOnSwatchReturnsItsIndex() {
        val centers = QuickColorRingMath.ringLayout(4, 500f, 500f)
        val (sx, sy) = centers[1]
        assertEquals(1, QuickColorRingMath.hitIndex(sx, sy, 500f, 500f, centers))
    }

    @Test
    fun hitIndexNearSwatchWithSlopReturnsIndex() {
        val centers = QuickColorRingMath.ringLayout(4, 500f, 500f)
        val (sx, sy) = centers[0]
        assertEquals(0, QuickColorRingMath.hitIndex(sx + 10f, sy, 500f, 500f, centers, touchSlopPx = 12f))
    }

    @Test
    fun hitIndexFarOutsideIsNothing() {
        val centers = QuickColorRingMath.ringLayout(4, 500f, 500f)
        assertEquals(QuickColorRingMath.NOTHING_HIT, QuickColorRingMath.hitIndex(100f, 100f, 500f, 500f, centers))
        assertEquals(QuickColorRingMath.NOTHING_HIT, QuickColorRingMath.hitIndex(500f, 10f, 500f, 500f, centers))
    }

    @Test
    fun hitIndexEmptyRingIsNothingOutside() {
        val centers = QuickColorRingMath.ringLayout(0, 0f, 0f)
        assertEquals(QuickColorRingMath.CENTER_SLOT, QuickColorRingMath.hitIndex(0f, 0f, 0f, 0f, centers))
        assertEquals(QuickColorRingMath.NOTHING_HIT, QuickColorRingMath.hitIndex(500f, 500f, 0f, 0f, centers))
    }

    // ---- angle / selection progress -------------------------------------------

    @Test
    fun angleDegNormalizesToZeroToThreeSixty() {
        val deg = QuickColorRingMath.angleDeg(500f, 600f, 500f, 500f) // straight up
        assertEquals(90f, deg, 0.5f)
        val neg = QuickColorRingMath.angleDeg(500f, 400f, 500f, 500f) // straight down
        assertEquals(270f, neg, 0.5f)
    }

    @Test
    fun selectionProgressMapsIndexToFraction() {
        assertEquals(0f, QuickColorRingMath.selectionProgress(0, 8), 0.0001f)
        assertEquals(0.5f, QuickColorRingMath.selectionProgress(4, 8), 0.0001f)
        assertEquals(0f, QuickColorRingMath.selectionProgress(-3, 8), 0.0001f)
        assertEquals(7f / 8f, QuickColorRingMath.selectionProgress(99, 8), 0.0001f)
    }
}
