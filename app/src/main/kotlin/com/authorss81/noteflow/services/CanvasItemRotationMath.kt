package com.authorss81.noteflow.services

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.atan2

/**
 * Pure, unit-testable 2D rotation math for canvas items (sticky notes,
 * stickers, image attachments).
 *
 * All functions operate on plain floats in canvas/world coordinates so they run
 * on the JVM without any Android dependency. The Compose layer feeds these
 * functions the item's model geometry and the pointer position, and the result
 * is applied with a cheap `graphicsLayer { rotationZ = ... }` — no full redraw,
 * safe for API 26+ low-end devices.
 */
object CanvasItemRotationMath {

    /** Safe rotation range; anything outside is normalised on write. */
    const val MIN_DEGREES = -360f
    const val MAX_DEGREES = 360f

    /**
     * Normalises an angle into (-180..180] so identical rotations never
     * accumulate rounding drift across save/load cycles.
     */
    fun normalizeAngle(degrees: Float): Float {
        val wrapped = ((degrees + 180f) % 360f + 360f) % 360f - 180f
        return wrapped.coerceIn(MIN_DEGREES, MAX_DEGREES)
    }

    /**
     * Rotates point (x, y) by [degrees] around anchor (cx, cy).
     * Positive degrees rotate clockwise in the screen coordinate system
     * (y grows downward), which matches Compose graphicsLayer rotationZ.
     */
    fun rotateX(x: Float, y: Float, cx: Float, cy: Float, degrees: Float): Float {
        val rad = Math.toRadians(degrees.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        val dx = x - cx
        val dy = y - cy
        return cx + dx * cosA - dy * sinA
    }

    fun rotateY(x: Float, y: Float, cx: Float, cy: Float, degrees: Float): Float {
        val rad = Math.toRadians(degrees.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        val dx = x - cx
        val dy = y - cy
        return cy + dx * sinA + dy * cosA
    }

    /** Rotates a point around an anchor; returns the rotated (x, y). */
    fun rotatePoint(x: Float, y: Float, cx: Float, cy: Float, degrees: Float): Pair<Float, Float> =
        Pair(rotateX(x, y, cx, cy, degrees), rotateY(x, y, cx, cy, degrees))

    /**
     * Rotated-rect hit test. The item occupies [left]..[left+width] ×
     * [top]..[top+height] in the UNROTATED frame and is rotated by [degrees]
     * around its own centre. The hit point is transformed into the item's local
     * frame (rotate by -degrees around the centre) and tested axis-aligned.
     */
    fun containsInRotatedRect(
        hitX: Float,
        hitY: Float,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        degrees: Float
    ): Boolean {
        if (width <= 0f || height <= 0f) return false
        val cx = left + width / 2f
        val cy = top + height / 2f
        val localX = rotateX(hitX, hitY, cx, cy, -degrees) - left
        val localY = rotateY(hitX, hitY, cx, cy, -degrees) - top
        return localX in 0f..width && localY in 0f..height
    }

    /**
     * Computes the absolute rotation of an item from a rotation-handle drag.
     *
     * [handleCenterRelCardCenterX/Y] is the handle's centre expressed relative
     * to the card centre in the card's LOCAL, UNROTATED pixel frame (the handle
     * sits above the card, so Y is strongly negative).
     *
     * [pointerRelHandleCenterX/Y] is the raw pointer position relative to the
     * handle centre in the card's local ROTATED pixel frame — this is exactly
     * what Compose reports inside the handle's `detectDragGestures` because the
     * card's graphicsLayer rotation transforms its hit area too.
     *
     * [zoom] converts local px back into canvas/world units; [currentDegrees]
     * is the item's rotation at drag time (the local frame is rotated by it).
     *
     * Returns the new absolute rotation in (-180..180]. The rotation applied on
     * top of [currentDegrees] is the (signed) change of the pointer's world
     * angle relative to the handle's idle position: grabbing the handle and
     * holding still must leave the item exactly where it was.
     */
    fun rotationFromHandleDrag(
        handleCenterRelCardCenterX: Float,
        handleCenterRelCardCenterY: Float,
        pointerRelHandleCenterX: Float,
        pointerRelHandleCenterY: Float,
        zoom: Float,
        currentDegrees: Float
    ): Float {
        val safeZoom = if (zoom <= 0f) 1f else zoom
        // Pointer relative to the card CENTRE in the card's rotated local frame.
        val rotatedRelX = rotateX(handleCenterRelCardCenterX, handleCenterRelCardCenterY, 0f, 0f, currentDegrees) + pointerRelHandleCenterX
        val rotatedRelY = rotateY(handleCenterRelCardCenterX, handleCenterRelCardCenterY, 0f, 0f, currentDegrees) + pointerRelHandleCenterY

        // Un-rotate into the world (unrotated) frame, then scale to world units.
        val worldRelX = rotateX(rotatedRelX, rotatedRelY, 0f, 0f, -currentDegrees) / safeZoom
        val worldRelY = rotateY(rotatedRelX, rotatedRelY, 0f, 0f, -currentDegrees) / safeZoom

        // Pointer's world angle around the card centre.
        val pointerWorldAngleDeg = Math.toDegrees(atan2(worldRelY.toDouble(), worldRelX.toDouble())).toFloat()
        // The handle hangs ABOVE the card centre (idle world angle -90°). A
        // fully-rotated item R aims the handle at -90° + R, so the new rotation is
        // `current + (pointerWorldAngle + 90)`. Grabbing the handle (pointer at
        // the handle, pointerWorldAngle = -90°) therefore makes the delta zero.
        return normalizeAngle(currentDegrees + pointerWorldAngleDeg + 90f)
    }
}
