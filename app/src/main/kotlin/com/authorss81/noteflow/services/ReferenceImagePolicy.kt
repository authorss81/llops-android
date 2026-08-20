package com.authorss81.noteflow.services

/**
 * Phase 178: single decision table for the per-page reference-image underlay
 * (ROADMAP Phase-07 encouraged item).
 *
 * The underlay is a bitmap rendered BELOW the stroke/layer pass of the canvas: the
 * user traces over it but strokes never draw onto it. This policy owns:
 *
 *  - [MIN_OPACITY]/[MAX_OPACITY]/[DEFAULT_OPACITY] — the dim range the canvas may
 *    render behind the ink (clamped so a corrupted stored value can never render
 *    a full-strength or fully-invisible underlay);
 *  - [clampOpacity] — the storage+render clamp used everywhere a user value or a
 *    decoded store value enters the layer;
 *  - [encodeConfig]/[decodeOpacity] — the FIELD-ENCRYPTED wire format carried in
 *    `media_embeds.textContent` (the same field-encrypted column convention as
 *    every other canvas embed). The underlay geometry (x/y/width/height) is
 *    stored in the row's plain columns exactly like a `PHOTO` embed; only the
 *    opacity rides the encrypted payload so a captured/previewed vault can never
 *    leak it in the clear;
 *  - [fitForPage] — the default placement used at insert time: aspect-preserving
 *    fit into the page world rect, centered.
 */
object ReferenceImagePolicy {

    const val MIN_OPACITY: Float = 0.30f
    const val MAX_OPACITY: Float = 0.50f
    const val DEFAULT_OPACITY: Float = 0.40f

    /**
     * Range-gated opacity — the only value a caller may store or render. A
     * non-finite input (decoded corrupt payload, NaN) collapses to
     * [DEFAULT_OPACITY] so a broken stored value can never render a
     * full-strength, fully-invisible or NaN underlay.
     */
    fun clampOpacity(opacity: Float): Float =
        if (opacity.isFinite()) opacity.coerceIn(MIN_OPACITY, MAX_OPACITY) else DEFAULT_OPACITY

    /**
     * Serializes the stored config (opacity only today) to the wire string that
     * lives inside the field-encrypted `textContent` column.
     */
    fun encodeConfig(opacity: Float): String = "{\"opacity\":${clampOpacity(opacity)}}"

    /**
     * Parses a [encodeConfig] wire string back to an opacity, or returns
     * [DEFAULT_OPACITY] when the payload is missing/corrupt (fail-soft to the
     * in-range default — never an out-of-range clamp surprise).
     */
    fun decodeOpacity(config: String?): Float {
        if (config.isNullOrBlank()) return DEFAULT_OPACITY
        val marker = "\"opacity\":"
        val idx = config.indexOf(marker)
        if (idx < 0) return DEFAULT_OPACITY
        val rest = config.substring(idx + marker.length)
        val end = rest.indexOf('}')
        val raw = if (end >= 0) rest.substring(0, end) else rest
        val parsed = raw.trim().toFloatOrNull() ?: return DEFAULT_OPACITY
        return clampOpacity(parsed)
    }

    /** Aspect-preserving default placement of [imgW]x[imgH] inside a [pageW]x[pageH] world rect. */
    data class Rect(val x: Float, val y: Float, val width: Float, val height: Float) {
        companion object {
            val ZERO = Rect(0f, 0f, 0f, 0f)
        }
    }

    fun fitForPage(imgWidth: Int, imgHeight: Int, pageWidth: Float, pageHeight: Float): Rect {
        if (imgWidth <= 0 || imgHeight <= 0 || pageWidth <= 0f || pageHeight <= 0f) {
            return Rect(0f, 0f, pageWidth, pageHeight)
        }
        val scale = minOf(pageWidth / imgWidth, pageHeight / imgHeight)
        val width = imgWidth * scale
        val height = imgHeight * scale
        return Rect(
            x = (pageWidth - width) / 2f,
            y = (pageHeight - height) / 2f,
            width = width,
            height = height
        )
    }

    /**
     * Re-derives a centered rect for [width]x[height] holding the vertical
     * midpoint at [centerY]. Used by the underlay-size control so a resize never
     * moves the image's center up/down.
     */
    fun recenterVertically(width: Float, height: Float, centerY: Float): Rect {
        val y = centerY - height / 2f
        return Rect(0f, y, width, height)
    }
}