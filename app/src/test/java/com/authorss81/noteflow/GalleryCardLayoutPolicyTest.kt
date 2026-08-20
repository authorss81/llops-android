package com.authorss81.noteflow

import com.authorss81.noteflow.services.GalleryCardLayoutPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 184 — gallery card layout policy (pure JVM).
 *
 * The card minimum height is a CONTENT-DRIVEN floor (font-scale-scaled
 * `heightIn`), never a strict aspect ratio. These tests pin the floor math:
 * base value at 1.0 font scale, monotonic growth at larger scales, hard cap at
 * extreme scales, and fail-safe handling of non-finite/zero scale inputs so a
 * garbage input can never collapse the tile.
 */
class GalleryCardLayoutPolicyTest {

    @Test
    fun `base floor at normal font scale`() {
        assertEquals(
            GalleryCardLayoutPolicy.BASE_MIN_HEIGHT_DP,
            GalleryCardLayoutPolicy.minCardHeightDp(1f),
            0f
        )
        assertEquals(180f, GalleryCardLayoutPolicy.minCardHeightDp(1f), 0f)
    }

    @Test
    fun `floor scales up monotonically with larger font scales`() {
        val normal = GalleryCardLayoutPolicy.minCardHeightDp(1f)
        val large = GalleryCardLayoutPolicy.minCardHeightDp(1.3f)
        val extra = GalleryCardLayoutPolicy.minCardHeightDp(2f)
        assertTrue("1.3x floor must exceed 1x floor", large > normal)
        assertTrue("2x floor must exceed 1.3x floor", extra > large)
        assertEquals(180f * 1.3f, large, 1e-3f)
        // 2x * 180 = 360 exceeds the 288dp cap, so the floor lands ON the cap.
        assertEquals(GalleryCardLayoutPolicy.MAX_MIN_HEIGHT_DP, extra, 0f)
    }

    @Test
    fun `floor is capped at extreme font scales`() {
        assertEquals(
            GalleryCardLayoutPolicy.MAX_MIN_HEIGHT_DP,
            GalleryCardLayoutPolicy.minCardHeightDp(3f),
            0f
        )
        assertEquals(
            GalleryCardLayoutPolicy.MAX_MIN_HEIGHT_DP,
            GalleryCardLayoutPolicy.minCardHeightDp(10f),
            0f
        )
    }

    @Test
    fun `floor never drops below the base value`() {
        // A sub-1.0 font scale must not collapse the tile below the floor.
        assertEquals(
            GalleryCardLayoutPolicy.BASE_MIN_HEIGHT_DP,
            GalleryCardLayoutPolicy.minCardHeightDp(0.85f),
            0f
        )
    }

    @Test
    fun `non-finite and non-positive scales fail safe to the base floor`() {
        assertEquals(
            GalleryCardLayoutPolicy.BASE_MIN_HEIGHT_DP,
            GalleryCardLayoutPolicy.minCardHeightDp(Float.NaN),
            0f
        )
        assertEquals(
            GalleryCardLayoutPolicy.BASE_MIN_HEIGHT_DP,
            GalleryCardLayoutPolicy.minCardHeightDp(Float.NEGATIVE_INFINITY),
            0f
        )
        // +Infinity is non-finite, so it fails safe to the BASE floor (never
        // propagates as NaN/infinity).
        assertEquals(
            GalleryCardLayoutPolicy.BASE_MIN_HEIGHT_DP,
            GalleryCardLayoutPolicy.minCardHeightDp(Float.POSITIVE_INFINITY),
            0f
        )
        assertEquals(
            GalleryCardLayoutPolicy.BASE_MIN_HEIGHT_DP,
            GalleryCardLayoutPolicy.minCardHeightDp(0f),
            0f
        )
        assertEquals(
            GalleryCardLayoutPolicy.BASE_MIN_HEIGHT_DP,
            GalleryCardLayoutPolicy.minCardHeightDp(-2f),
            0f
        )
    }

    @Test
    fun `180dp floor at a 168dp cell is a balanced notebook tile not a tall bookmark`() {
        // The old rigid ratio forced 168 * (16/10) = 268.8dp regardless of content.
        // At 1.0 font scale the floor is 180dp: content-driven, with the long
        // notes still able to exceed it via their own content height.
        val cellWidth = 168f
        val floor = GalleryCardLayoutPolicy.minCardHeightDp(1f)
        assertTrue(
            "the floor must be comfortably below the old 268.8dp fixed height",
            floor < cellWidth * (16f / 10f)
        )
        assertEquals(0f, floor - 180f, 1e-3f)
    }
}