package com.authorss81.noteflow.services

/**
 * A ready-made offline sticker (emoji-based). Rendered via the platform Emoji
 * font — free, offline, zero image assets, so the APK stays small.
 */
data class Sticker(
    val id: String,
    val emoji: String,
    val label: String
)

/**
 * Small curated, free, offline sticker pack. The emoji glyph is drawn directly
 * with the platform font, so no bitmap assets and no new permissions.
 */
object StickerCatalog {

    val ALL = listOf(
        Sticker("sparkle", "\u2728", "Sparkle"),
        Sticker("star", "\u2B50", "Star"),
        Sticker("heart", "\u2764\uFE0F", "Heart"),
        Sticker("smile", "\uD83D\uDE00", "Smile"),
        Sticker("rocket", "\uD83D\uDE80", "Rocket"),
        Sticker("bulb", "\uD83D\uDCA1", "Idea"),
        Sticker("pencil_emoji", "\u270F\uFE0F", "Pencil"),
        Sticker("palette", "\uD83C\uDFA8", "Palette"),
        Sticker("note", "\uD83D\uDCDD", "Note"),
        Sticker("bookmark", "\uD83D\uDD16", "Bookmark"),
        Sticker("flag", "\uD83D\uDEA9", "Flag"),
        Sticker("target", "\uD83C\uDFAF", "Target"),
        Sticker("flower", "\uD83C\uDF38", "Flower"),
        Sticker("coffee", "\u2615", "Coffee"),
        Sticker("magnifier", "\uD83D\uDD0D", "Magnifier"),
        Sticker("checkmark", "\u2705", "Check")
    )

    fun all(): List<Sticker> = ALL

    fun byId(id: String?): Sticker? = ALL.firstOrNull { it.id == id }

    fun isValidId(id: String?): Boolean = byId(id) != null

    /** Default sticker canvas size in world units (square). */
    const val DEFAULT_SIZE = 140f

    /**
     * Sticker placement math: given a tap point, returns the top-left (x, y)
     * so the sticker's square body stays fully inside [pageWidth]×[pageHeight]
     * when possible, otherwise clamped to the bounds. Pure and unit-testable.
     */
    fun placeTopLeft(tapX: Float, tapY: Float, size: Float, pageWidth: Float, pageHeight: Float): Pair<Float, Float> {
        val s = size.coerceAtLeast(8f)
        val clampedTapX = tapX.coerceIn(0f, pageWidth)
        val clampedTapY = tapY.coerceIn(0f, pageHeight)
        var x = clampedTapX - s / 2f
        var y = clampedTapY - s / 2f
        if (x + s > pageWidth) x = (pageWidth - s).coerceAtLeast(0f)
        if (y + s > pageHeight) y = (pageHeight - s).coerceAtLeast(0f)
        return Pair(x.coerceIn(0f, pageWidth), y.coerceIn(0f, pageHeight))
    }
}