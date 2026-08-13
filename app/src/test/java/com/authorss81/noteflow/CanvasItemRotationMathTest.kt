package com.authorss81.noteflow

import com.authorss81.noteflow.services.CanvasItemRotationMath
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasItemRotationMathTest {

    @Test
    fun `normalizeAngle wraps into negative-180-to-180`() {
        assertEquals("0 stays 0", 0f, CanvasItemRotationMath.normalizeAngle(0f), 1e-4f)
        assertEquals("45 stays 45", 45f, CanvasItemRotationMath.normalizeAngle(45f), 1e-4f)
        assertEquals("190 wraps to -170", -170f, CanvasItemRotationMath.normalizeAngle(190f), 1e-4f)
        assertEquals("370 wraps to 10", 10f, CanvasItemRotationMath.normalizeAngle(370f), 1e-4f)
        assertEquals("-190 wraps to 170", 170f, CanvasItemRotationMath.normalizeAngle(-190f), 1e-4f)
        assertEquals("720 wraps to 0", 0f, CanvasItemRotationMath.normalizeAngle(720f), 1e-4f)
    }

    @Test
    fun `rotatePoint rotates clockwise for positive degrees`() {
        // Right edge point (w=200,h=100,half=100,50) rotated 90° about its centre
        // (100,50) must land below the centre (screen y grows downward).
        val (x, y) = CanvasItemRotationMath.rotatePoint(200f, 50f, 100f, 50f, 90f)
        assertEquals("90° CW: right edge goes down", 100f, x, 1e-3f)
        assertEquals("90° CW: right edge goes down", 150f, y, 1e-3f)
    }

    @Test
    fun `rotatePoint full circle returns to origin`() {
        val startX = 42f
        val startY = 17f
        var cx = startX
        var cy = startY
        for (step in 1..4) {
            val (x, y) = CanvasItemRotationMath.rotatePoint(cx, cy, startX, startY, 90f)
            cx = x
            cy = y
        }
        assertEquals("four 90° rotations return to start", startX, cx, 1e-2f)
        assertEquals("four 90° rotations return to start", startY, cy, 1e-2f)
    }

    @Test
    fun `containsInRotatedRect hits only within rotated footprint`() {
        // 200x100 rect with top-left at (0,0), rotated 90° about (100,50).
        // Footprint becomes 100 wide x 200 tall centred on (100,50).
        val inside = CanvasItemRotationMath.containsInRotatedRect(
            hitX = 100f, hitY = 20f, left = 0f, top = 0f, width = 200f, height = 100f, degrees = 90f
        )
        assertTrue("point inside rotated footprint must hit", inside)

        val farLeft = CanvasItemRotationMath.containsInRotatedRect(
            hitX = 10f, hitY = 50f, left = 0f, top = 0f, width = 200f, height = 100f, degrees = 90f
        )
        assertFalse("point outside the rotated footprint must miss", farLeft)
    }

    @Test
    fun `containsInRotatedRect with zero rotation is axis-aligned`() {
        assertTrue("center always inside", CanvasItemRotationMath.containsInRotatedRect(100f, 50f, 0f, 0f, 200f, 100f, 0f))
        assertFalse("outside must miss", CanvasItemRotationMath.containsInRotatedRect(-1f, 50f, 0f, 0f, 200f, 100f, 0f))
        assertFalse("zero-size rect never hits", CanvasItemRotationMath.containsInRotatedRect(0f, 0f, 0f, 0f, 0f, 100f, 0f))
    }

    @Test
    fun `rotationFromHandleDrag resting handle yields current rotation`() {
        // Handle hangs above the unrotated card (angle -90°); touching it must
        // leave the rotation at 0° (idle), not jump to -90°.
        val cardHpx = 400f * 1f
        val handleCenterY = -(8f + 13f + cardHpx / 2f)
        val result = CanvasItemRotationMath.rotationFromHandleDrag(
            handleCenterRelCardCenterX = 0f,
            handleCenterRelCardCenterY = handleCenterY,
            pointerRelHandleCenterX = 0f,
            pointerRelHandleCenterY = 0f,
            zoom = 1f,
            currentDegrees = 0f
        )
        assertEquals("grabbing the handle must not snap the card", 0f, result, 1e-3f)
    }

    @Test
    fun `rotationFromHandleDrag dragging to the right gives 90 degrees`() {
        // Handle at unrotated centre (0,-221). Pointer offset (1000,+221) puts
        // the pointer horizontally right of the card centre in world space,
        // which must rotate the card by exactly 90°.
        val cardHpx = 400f
        val handleCenterY = -(8f + 13f + cardHpx / 2f)
        val result = CanvasItemRotationMath.rotationFromHandleDrag(
            handleCenterRelCardCenterX = 0f,
            handleCenterRelCardCenterY = handleCenterY,
            pointerRelHandleCenterX = 1000f,
            pointerRelHandleCenterY = -handleCenterY,
            zoom = 1f,
            currentDegrees = 0f
        )
        assertEquals("handle dragged to the right rotates the card 90°", 90f, result, 1e-2f)
    }

    @Test
    fun `rotationFromHandleDrag preserves incremental rotation`() {
        // If the card is already at 30°, grabbing the resting handle stays at 30°.
        val cardHpx = 400f
        val handleCenterY = -(8f + 13f + cardHpx / 2f)
        val result = CanvasItemRotationMath.rotationFromHandleDrag(
            handleCenterRelCardCenterX = 0f,
            handleCenterRelCardCenterY = handleCenterY,
            pointerRelHandleCenterX = 0f,
            pointerRelHandleCenterY = 0f,
            zoom = 1f,
            currentDegrees = 30f
        )
        assertEquals("existing rotation is preserved on grab", 30f, result, 1e-3f)
        assertTrue("result stays in negative-180-to-180", abs(result) <= 180f)
    }
}