package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeTool

/**
 * Phase 205: single decision table for LASER trail lifetime.
 *
 * Pre-205 the laser pipeline had TWO independent copies of the 1800 ms constant
 * (the expiry poll in AnnotationCanvas and the render-side alpha ramp in
 * drawSingleStroke), and the expiry path was a 25 Hz `delay(40)` poll that
 * pushed every individual removal through [com.authorss81.noteflow.ui.screens]
 * `handleStrokesChange` — i.e. each fade tick copied the FULL stroke list into
 * the 30-deep undo stack, cleared redo, recomposed and armed a Room autosave.
 *
 * Post-205 contract (see workspace/phase-205/REPORT.md):
 *  - the fade itself is RENDER-SIDE ONLY: the canvas runs one frame clock while
 *    any laser exists and [fadeFraction] drives per-frame alpha;
 *  - removal happens in ONE batched [FadeWave] per fade wave via the EPHEMERAL
 *    channel (`onLaserTrailsExpired`) which must never touch undo/redo history —
 *    a laser trail is a transient pointer highlight by design, not an edit;
 *  - exactly one autosave arm per wave (the parent's normal debounced flush).
 */
object LaserTrailPolicy {

    /** Total visible life of a laser trail: drawn while it fades to zero. */
    const val FADE_DURATION_MS = 1800L

    /**
     * One batched removal: ALL trails that reached end-of-life in the same wake
     * leave together, so a burst of laser strokes costs ONE ephemeral list
     * update instead of one per stroke per tick.
     */
    data class FadeWave(
        val remaining: List<Stroke>,
        val removedIds: List<String>
    )

    /** A laser trail is expired once its full fade budget has elapsed. */
    fun isExpired(stroke: Stroke, nowMs: Long): Boolean =
        stroke.tool == StrokeTool.LASER &&
            stroke.timestampMs != null &&
            (nowMs - stroke.timestampMs) >= FADE_DURATION_MS

    /**
     * Render-side fade envelope in [0..1] (1 = freshly committed, 0 = gone).
     * Strokes without a capture timestamp never fade (live preview renders at
     * full strength until commit stamps its clock).
     */
    fun fadeFraction(timestampMs: Long?, nowMs: Long): Float {
        if (timestampMs == null) return 1f
        val ageMs = (nowMs - timestampMs).coerceAtLeast(0L)
        return (1f - ageMs.toFloat() / FADE_DURATION_MS).coerceIn(0f, 1f)
    }

    /** Ids of every trail whose fade budget elapsed by [nowMs]. */
    fun expiredIds(all: List<Stroke>, nowMs: Long): List<String> =
        all.filter { isExpired(it, nowMs) }.map { it.id }

    /**
     * Batched wave computation over the CURRENT stroke state. Returns null when
     * nothing expired (the caller must not emit anything), otherwise the
     * surviving list — order preserved — plus the removed ids.
     */
    fun stripExpired(all: List<Stroke>, nowMs: Long): FadeWave? {
        var anyExpired = false
        for (stroke in all) {
            if (isExpired(stroke, nowMs)) {
                anyExpired = true
                break
            }
        }
        if (!anyExpired) return null
        val remaining = ArrayList<Stroke>(all.size)
        val removedIds = ArrayList<String>()
        for (stroke in all) {
            if (isExpired(stroke, nowMs)) removedIds.add(stroke.id) else remaining.add(stroke)
        }
        return if (removedIds.isEmpty()) null else FadeWave(remaining, removedIds)
    }
}
