package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.Stroke

/**
 * B2-DOS-01 (phase-50): the SINGLE place that owns the stroke-geometry budgets.
 *
 * Before this policy, nothing capped how many points a stroke carried nor how
 * many strokes/points a page accumulated: `saveStrokesForPage` wrote whatever
 * `List<Stroke>` it was handed verbatim, `getStrokesForPage` Gson-parse-
 * materialized the page's ENTIRE geometry at once (`EncryptionService.
 * deserializeStrokes` had no size guard), the DAO loaded every `strokes` row
 * without a LIMIT, and `restoreFromZip` transplanted any `pointsJson` row
 * verbatim into the live vault. A crafted backup (B1-DB-7-style) could carry a
 * stroke whose `pointsJson` encrypts ~2M points (~100 MB JSON) and page-open
 * would OOM/ANR while the renderer then walked every point every frame.
 *
 * This object is pure JVM and stateless so every gate below is unit-testable
 * and shared across the write path (repository), the read path (repository),
 * the import/restore path (`ImportExportService`) and the serializer
 * (`EncryptionService`).
 *
 * Budgets (documented at save; a stroke/page at these caps is already extreme):
 *  - [MAX_POINTS_PER_STROKE] — ~5.5 min of continuous 60 Hz pen input in ONE
 *    stroke. Longer content must be a new stroke.
 *  - [MAX_POINTS_PER_PAGE] — ~200k ink points per page regardless of the number
 *    of strokes. Bounds both the load-time Gson materialization and the
 *    renderer's per-frame path/polyline budget.
 *  - [MAX_STROKES_PER_PAGE] — row cap. Bounds the `strokes`/`layers` list sizes
 *    and the layer-group iteration even when strokes are single-point text tags.
 *  - [MAX_STROKE_JSON_PLAINTEXT_CHARS] — plaintext envelope for ONE stroke's
 *    serialized `pointsJson`. Gson emits ~55-95 characters per point (x/y plus
 *    optional pressure/tilt/timestampMs), so a 20k-point stroke serializes to
 *    well under this cap; it is the guard `EncryptionService.deserializeStrokes`
 *    applies BEFORE any Gson object graph is allocated.
 *  - [MAX_STORED_POINTS_JSON_CHARS] — stored (TEXT column) envelope for ONE
 *    stroke's encrypted `pointsJson`. `EncryptionService.encryptField` emits
 *    base64(0x01 || 12-byte IV || AES-256-GCM ciphertext || 16-byte tag), and
 *    base64 of (plaintextBytes + 29) ≈ 4/3 × plaintext — so a plaintext row that
 *    fit the plaintext cap can never store longer than this. It is the cheap
 *    pre-decrypt check in the DAO WHERE clause / load path and the restore
 *    strip: the ciphertext length is an exact proxy for plaintext size because
 *    AES-GCM does not compress.
 */
object StrokeGeometryPolicy {
    const val MAX_POINTS_PER_STROKE = 20_000

    const val MAX_POINTS_PER_PAGE = 200_000

    const val MAX_STROKES_PER_PAGE = 2_000

    /** Page of rows fetched per round in [com.authorss81.noteflow.data.repository.NoteRepository.getStrokesForPage]. */
    const val MAX_STROKES_LOAD_BATCH = 128

    const val MAX_STROKE_JSON_PLAINTEXT_CHARS = 2_500_000

    const val MAX_STORED_POINTS_JSON_CHARS = 3_400_000

    /** True iff a stored (base64 ciphertext or legacy plaintext) pointsJson TEXT value is already over budget. */
    fun storedPointsJsonOverBudget(storedChars: Int): Boolean =
        storedChars > MAX_STORED_POINTS_JSON_CHARS

    /** True iff a DECRYPTED pointsJson plaintext is over budget (guard BEFORE Gson parses it). */
    fun plaintextPointsJsonOverBudget(plaintextChars: Int): Boolean =
        plaintextChars > MAX_STROKE_JSON_PLAINTEXT_CHARS

    fun totalPoints(strokes: List<Stroke>): Int {
        var sum = 0
        for (s in strokes) sum += s.points.size
        return sum
    }

    /**
     * Truncates a single stroke's point list to at most [MAX_POINTS_PER_STROKE]
     * points (keeps the head of the stroke). A stroke already within budget is
     * returned unchanged. Pure — never mutates the input.
     */
    fun gateStroke(stroke: Stroke): Stroke {
        if (stroke.points.size <= MAX_POINTS_PER_STROKE) return stroke
        return stroke.copy(points = stroke.points.take(MAX_POINTS_PER_STROKE))
    }

    /**
     * Applies the WRITE-side gate to a page's incoming [strokes]:
     *  1. per-stroke truncation to [MAX_POINTS_PER_STROKE];
     *  2. the page budget — once [MAX_POINTS_PER_PAGE] total points or
     *     [MAX_STROKES_PER_PAGE] strokes are reached, further strokes are
     *     DROPPED (their content cannot be represented at the agreed budget).
     * Never mutates the input; returns the bounded list plus a metering report
     * so callers can surface a non-alarming notice.
     */
    fun applySaveGate(strokes: List<Stroke>): StrokeGeometryGateResult {
        var pointsBefore = 0
        var truncated = 0
        val gated = ArrayList<Stroke>(strokes.size)
        for (s in strokes) {
            pointsBefore += s.points.size
            if (s.points.size > MAX_POINTS_PER_STROKE) truncated++
            val g = gateStroke(s)
            gated += g
        }

        val kept = ArrayList<Stroke>(gated.size)
        var pagePoints = 0
        var dropped = 0
        for (g in gated) {
            if (kept.size >= MAX_STROKES_PER_PAGE) {
                dropped++
                continue
            }
            val next = pagePoints + g.points.size
            if (pagePoints > 0 && next > MAX_POINTS_PER_PAGE) {
                dropped++
                continue
            }
            kept += g
            pagePoints = next
        }

        var pointsAfter = 0
        for (s in kept) pointsAfter += s.points.size
        return StrokeGeometryGateResult(
            kept = kept,
            pointsBefore = pointsBefore,
            pointsAfter = pointsAfter,
            strokesBefore = strokes.size,
            strokesAfter = kept.size,
            truncatedStrokes = truncated,
            droppedStrokes = dropped
        )
    }

    /**
     * Load-side per-stroke truncation: a legacy row that decrypts to more than
     * [MAX_POINTS_PER_STROKE] points keeps only its head. Mirrors [gateStroke].
     */
    fun capLoadedPoints(points: List<com.authorss81.noteflow.data.model.PointF>): List<com.authorss81.noteflow.data.model.PointF> {
        if (points.size <= MAX_POINTS_PER_STROKE) return points
        return points.take(MAX_POINTS_PER_STROKE)
    }
}

/**
 * Metering result of [StrokeGeometryPolicy.applySaveGate]. Non-zero
 * [truncatedStrokes]/[droppedStrokes] means the write was bounded: callers
 * should surface a one-time, non-alarming notice (AGENTS.md "Never silent
 * degradation") instead of silently discarding geometry.
 */
data class StrokeGeometryGateResult(
    val kept: List<Stroke>,
    val pointsBefore: Int,
    val pointsAfter: Int,
    val strokesBefore: Int,
    val strokesAfter: Int,
    val truncatedStrokes: Int,
    val droppedStrokes: Int
) {
    val geometryWasCapped: Boolean
        get() = truncatedStrokes > 0 || droppedStrokes > 0

    val noticeText: String
        get() = buildString {
            append("Stroke geometry capped")
            val parts = mutableListOf<String>()
            if (truncatedStrokes > 0) parts.add("$truncatedStrokes stroke(s) had their longest tail trimmed")
            if (droppedStrokes > 0) parts.add("$droppedStrokes stroke(s) beyond the page budget dropped")
            if (parts.isNotEmpty()) append(" (").append(parts.joinToString(", ")).append(")")
            append(". Off-page/older ink was kept; the page was bounded to protect older devices.")
        }
}