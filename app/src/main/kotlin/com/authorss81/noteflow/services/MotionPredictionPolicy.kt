package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.PointF

/**
 * Phase 196: decision table + coordinate math for OS-level stylus/touch motion
 * prediction (`androidx.input.motionprediction.MotionEventPredictor`).
 *
 * Why: the ink path draws a point only when a real input event lands. When the
 * digitizer reports at a lower rate than the display refreshes (60 Hz finger on
 * a 120 Hz panel; Bluetooth styluses batching samples), the live stroke freezes
 * for one or more frames — perceived pen lag. The OS predictor extrapolates the
 * next sample from recent ballistics; drawing that extrapolation as a temporary
 * TAIL of the live preview removes the freeze frame.
 *
 * Contract enforced here (and pinned by tests):
 *  1. Capability gate — prediction is attempted only on API 29+ ([MIN_SDK]);
 *     below that (or if `MotionEventPredictor.newInstance` fails) callers run
 *     the pre-196 stabilizer-only path. No silent behavior change on old
 *     devices, because prediction is an additive preview enhancement, not a
 *     user-visible feature toggle.
 *  2. Preview-only — a predicted sample is ALWAYS a temporary last element of
 *     the live preview list. The next real input event reconciles it away
 *     ([PredictedTailTracker.stripFrom]) BEFORE the real sample is appended and
 *     BEFORE the stroke is committed, so stored stroke geometry never contains
 *     a predicted point.
 *  3. Coordinate mapping — raw `MotionEvent` samples are relative to the
 *     hosting view/window, while the canvas drag handlers work in the canvas
 *     box's own layout space; [predictedWorldPoint] subtracts the box's window
 *     offset, un-applies pan/zoom, then coerces into the active page bounds
 *     with the SAME clamp the real drag path uses, so a predicted point can
 *     never escape the page or land at stale-transform coordinates.
 *
 * This object is intentionally free of Android framework dependencies so the
 * gate/mapping/tracker logic is directly unit-testable on the JVM.
 */
object MotionPredictionPolicy {

    /**
     * Minimum API level for attempting prediction. The androidx library itself
     * degrades gracefully below R, but phase-196 scope pins the feature gate to
     * API 29+ (devices older than that keep the proven stabilizer-only path).
     */
    const val MIN_SDK = 29

    fun isSupported(sdkInt: Int): Boolean = sdkInt >= MIN_SDK

    /**
     * Whether a freshly predicted sample may extend the live stroke preview.
     * Every guard must hold:
     * - [predictorAvailable]: API gate passed AND `newInstance` succeeded.
     * - [freehandTool]: only freehand tools accumulate `activePoints`; shape /
     *   text / eraser / select / pan paths must stay untouched.
     * - [strokeInProgress]: no live preview, nothing to extend.
     * - [singlePointerStream]: during two-finger pinch/undo gestures the first
     *   pointer's ballistics are meaningless for ink — never predict.
     * - [panningWhiteSpace]: dragging in black space pans instead of drawing.
     */
    fun shouldExtendPreview(
        predictorAvailable: Boolean,
        freehandTool: Boolean,
        strokeInProgress: Boolean,
        singlePointerStream: Boolean,
        panningWhiteSpace: Boolean
    ): Boolean = predictorAvailable &&
        freehandTool &&
        strokeInProgress &&
        singlePointerStream &&
        !panningWhiteSpace

    /**
     * Maps one predicted sample from host-view (window) coordinates into canvas
     * WORLD coordinates:
     *
     *   local  = predicted - canvasBoxWindowOffset   (view space -> box space)
     *   world  = (local - pan) / zoom                (box space -> world space)
     *   clamped into [0, pageWidth] x [pageTop, pageBottom]
     *
     * The clamp mirrors the real drag path (`AnnotationCanvas.onDrag`) exactly,
     * so a predicted point obeys the same page-boundary rule as a real one.
     * Returns null when any input is non-finite or the zoom is degenerate —
     * fail-safe: the caller drops the prediction rather than drawing garbage.
     */
    fun predictedWorldPoint(
        predictedViewX: Float,
        predictedViewY: Float,
        canvasWindowX: Float,
        canvasWindowY: Float,
        zoomScale: Float,
        panX: Float,
        panY: Float,
        pageWidthPx: Float,
        pageTopY: Float,
        pageBottomY: Float,
        pressure: Float?,
        tilt: Float?,
        timestampMs: Long?
    ): PointF? {
        if (!(predictedViewX.isFinite() && predictedViewY.isFinite() &&
                canvasWindowX.isFinite() && canvasWindowY.isFinite() &&
                panX.isFinite() && panY.isFinite())
        ) {
            return null
        }
        if (!zoomScale.isFinite() || zoomScale <= 0f) return null
        if (!(pageWidthPx.isFinite() && pageWidthPx > 0f)) return null
        if (!(pageTopY.isFinite() && pageBottomY.isFinite() && pageBottomY >= pageTopY)) return null

        val worldX = ((predictedViewX - canvasWindowX) - panX) / zoomScale
        val worldY = ((predictedViewY - canvasWindowY) - panY) / zoomScale
        return PointF(
            x = worldX.coerceIn(0f, pageWidthPx),
            y = worldY.coerceIn(pageTopY, pageBottomY),
            pressure = pressure?.takeIf { it.isFinite() && it > 0f },
            tilt = tilt?.takeIf { it.isFinite() },
            timestampMs = timestampMs
        )
    }

    /**
     * Tracks whether the LAST element of the live preview list is a predicted
     * (synthetic) point. Deliberately plain mutable state — NOT Compose
     * snapshot state — so marking/stripping never invalidates composition;
     * the preview list itself stays snapshot-backed and draw-phase reads keep
     * invalidating ONLY the draw. All access happens on the UI thread.
     */
    class PredictedTailTracker {
        private var tailPresent = false

        val isPresent: Boolean get() = tailPresent

        /** Flags the (just appended) last preview element as predicted. */
        fun mark() {
            tailPresent = true
        }

        /** Resets the flag when the preview list was cleared wholesale anyway. */
        fun clear() {
            tailPresent = false
        }

        /**
         * Reconcile: removes the trailing predicted point (if flagged) and
         * resets the flag. Idempotent; safe on an empty list. Call before
         * appending each real sample and before committing the stroke.
         */
        fun stripFrom(points: MutableList<*>) {
            if (!tailPresent) return
            if (points.isNotEmpty()) {
                points.removeAt(points.size - 1)
            }
            tailPresent = false
        }
    }
}
