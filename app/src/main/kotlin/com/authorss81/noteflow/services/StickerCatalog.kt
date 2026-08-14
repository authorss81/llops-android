package com.authorss81.noteflow.services

/**
 * A ready-made offline sticker (emoji-based). Rendered via the platform Emoji
 * font — free, offline, zero image assets, so the APK stays small.
 */
data class Sticker(
    val id: String,
    val emoji: String,
    val label: String,
    val category: String = CATEGORY_MARKS
) {
    companion object {
        const val CATEGORY_MARKS = "Marks"
        const val CATEGORY_MOODS = "Moods"
        const val CATEGORY_SYMBOLS = "Symbols"
        const val CATEGORY_SHAPES = "Shapes"
        const val CATEGORY_WRITING = "Writing"
    }
}

/**
 * Rich, meaningfully-curated, free, offline sticker pack of emoji stickers —
 * organized by category for a note-taking app. The emoji glyph is drawn directly
 * with the platform font, so no bitmap assets and no new permissions.
 *
 * Phase 28: expanded from 16 → 64 stickers across 5 categories (Marks, Moods,
 * Symbols, Shapes, Writing) + category-based [search] + [byCategory].
 */
object StickerCatalog {

    const val DEFAULT_SIZE = 140f

    /** Ordered category names shown in the picker (valid category vocab). */
    val CATEGORIES: List<String> = listOf(
        Sticker.CATEGORY_MARKS,
        Sticker.CATEGORY_MOODS,
        Sticker.CATEGORY_SYMBOLS,
        Sticker.CATEGORY_SHAPES,
        Sticker.CATEGORY_WRITING
    )

    val ALL = listOf(
        // -- Marks ----------------------------------------------------------
        Sticker("sparkle", "\u2728", "Sparkle", Sticker.CATEGORY_MARKS),
        Sticker("star", "\u2B50", "Star", Sticker.CATEGORY_MARKS),
        Sticker("heart", "\u2764\uFE0F", "Heart", Sticker.CATEGORY_MARKS),
        Sticker("flag", "\uD83D\uDEA9", "Flag", Sticker.CATEGORY_MARKS),
        Sticker("target", "\uD83C\uDFAF", "Target", Sticker.CATEGORY_MARKS),
        Sticker("checkmark", "\u2705", "Check", Sticker.CATEGORY_MARKS),
        Sticker("fire", "\uD83D\uDD25", "Fire", Sticker.CATEGORY_MARKS),
        Sticker("crush_green", "\uD83D\uDC9A", "Green Heart", Sticker.CATEGORY_MARKS),
        Sticker("orange_circle", "\uD83D\uDFE0", "Highlight", Sticker.CATEGORY_MARKS),
        Sticker("warning", "\u26A0\uFE0F", "Warning", Sticker.CATEGORY_MARKS),
        Sticker("question", "\u2753", "Question", Sticker.CATEGORY_MARKS),
        Sticker("exclamation", "\u2757", "Important", Sticker.CATEGORY_MARKS),
        Sticker("memo", "\uD83D\uDCDD", "Memo", Sticker.CATEGORY_MARKS),
        Sticker("pushpin", "\uD83D\uDCCC", "Pin", Sticker.CATEGORY_MARKS),
        Sticker("paperclip", "\uD83D\uDCCE", "Clip", Sticker.CATEGORY_MARKS),
        Sticker("bookmark", "\uD83D\uDD16", "Bookmark", Sticker.CATEGORY_MARKS),

        // -- Moods ----------------------------------------------------------
        Sticker("smile", "\uD83D\uDE00", "Smile", Sticker.CATEGORY_MOODS),
        Sticker("joy", "\uD83D\uDE02", "Joy", Sticker.CATEGORY_MOODS),
        Sticker("wink", "\uD83D\uDE09", "Wink", Sticker.CATEGORY_MOODS),
        Sticker("cool", "\uD83D\uDE0E", "Cool", Sticker.CATEGORY_MOODS),
        Sticker("happy", "\uD83D\uDE0A", "Happy", Sticker.CATEGORY_MOODS),
        Sticker("party", "\uD83E\uDD73", "Party", Sticker.CATEGORY_MOODS),
        Sticker("think", "\uD83E\uDD14", "Think", Sticker.CATEGORY_MOODS),
        Sticker("sleepy", "\uD83D\uDE2A", "Sleepy", Sticker.CATEGORY_MOODS),
        Sticker("sad", "\uD83D\uDE22", "Sad", Sticker.CATEGORY_MOODS),
        Sticker("angry", "\uD83D\uDE21", "Angry", Sticker.CATEGORY_MOODS),
        Sticker("love_eyes", "\uD83D\uDE0D", "In Love", Sticker.CATEGORY_MOODS),
        Sticker("star_eyes", "\uD83E\uDD29", "Star Struck", Sticker.CATEGORY_MOODS),
        Sticker("wave", "\uD83D\uDC4B", "Hi", Sticker.CATEGORY_MOODS),
        Sticker("clap", "\uD83D\uDC4F", "Well Done", Sticker.CATEGORY_MOODS),
        Sticker("thumbsup", "\uD83D\uDC4D", "Yes", Sticker.CATEGORY_MOODS),
        Sticker("thumbsdown", "\uD83D\uDC4E", "No", Sticker.CATEGORY_MOODS),

        // -- Symbols --------------------------------------------------------
        Sticker("rocket", "\uD83D\uDE80", "Rocket", Sticker.CATEGORY_SYMBOLS),
        Sticker("bulb", "\uD83D\uDCA1", "Idea", Sticker.CATEGORY_SYMBOLS),
        Sticker("magnifier", "\uD83D\uDD0D", "Search", Sticker.CATEGORY_SYMBOLS),
        Sticker("infinity", "\u267E\uFE0F", "Infinity", Sticker.CATEGORY_SYMBOLS),
        Sticker("recycle", "\u267B\uFE0F", "Recycle", Sticker.CATEGORY_SYMBOLS),
        Sticker("peace", "\u270C\uFE0F", "Peace", Sticker.CATEGORY_SYMBOLS),
        Sticker("muscle", "\uD83D\uDCAA", "Strong", Sticker.CATEGORY_SYMBOLS),
        Sticker("brain", "\uD83E\uDDE0", "Brain", Sticker.CATEGORY_SYMBOLS),
        Sticker("rocket2", "\uD83D\uDE80", "Launch", Sticker.CATEGORY_SYMBOLS),
        Sticker("trophy", "\uD83C\uDFC6", "Goal", Sticker.CATEGORY_SYMBOLS),
        Sticker("star2", "\uD83C\uDF1F", "Achieve", Sticker.CATEGORY_SYMBOLS),
        Sticker("lock", "\uD83D\uDD12", "Private", Sticker.CATEGORY_SYMBOLS),
        Sticker("unlock", "\uD83D\uDD13", "Open", Sticker.CATEGORY_SYMBOLS),
        Sticker("phone", "\uD83D\uDCF1", "Call", Sticker.CATEGORY_SYMBOLS),
        Sticker("mail", "\u2709\uFE0F", "Mail", Sticker.CATEGORY_SYMBOLS),
        Sticker("calendar", "\uD83D\uDCC5", "Date", Sticker.CATEGORY_SYMBOLS),

        // -- Shapes ---------------------------------------------------------
        Sticker("heart_red", "\u2764\uFE0F", "Red Heart", Sticker.CATEGORY_SHAPES),
        Sticker("diamond", "\uD83D\uDD36", "Diamond", Sticker.CATEGORY_SHAPES),
        Sticker("square_blue", "\uD83D\uDFE6", "Blue Square", Sticker.CATEGORY_SHAPES),
        Sticker("square_orange", "\uD83D\uDFE7", "Orange Square", Sticker.CATEGORY_SHAPES),
        Sticker("circle_red", "\uD83D\uDD34", "Red Circle", Sticker.CATEGORY_SHAPES),
        Sticker("circle_blue", "\uD83D\uDD35", "Blue Circle", Sticker.CATEGORY_SHAPES),
        Sticker("triangle", "\uD83D\uDD3B", "Triangle", Sticker.CATEGORY_SHAPES),
        Sticker("circle_yellow", "\uD83D\uDFE1", "Yellow Circle", Sticker.CATEGORY_SHAPES),
        Sticker("circle_green", "\uD83D\uDFE2", "Green Circle", Sticker.CATEGORY_SHAPES),

        // -- Writing --------------------------------------------------------
        Sticker("pencil_emoji", "\u270F\uFE0F", "Pencil", Sticker.CATEGORY_WRITING),
        Sticker("palette", "\uD83C\uDFA8", "Palette", Sticker.CATEGORY_WRITING),
        Sticker("note", "\uD83D\uDCDD", "Note", Sticker.CATEGORY_WRITING),
        Sticker("flower", "\uD83C\uDF38", "Flower", Sticker.CATEGORY_WRITING),
        Sticker("coffee", "\u2615", "Coffee", Sticker.CATEGORY_WRITING),
        Sticker("paintbrush", "\uD83D\uDD8C\uFE0F", "Brush", Sticker.CATEGORY_WRITING),
        Sticker("book", "\uD83D\uDCD6", "Book", Sticker.CATEGORY_WRITING),
        Sticker("mic", "\uD83C\uDFA4", "Record", Sticker.CATEGORY_WRITING),
        Sticker("pen", "\uD83D\uDD8B\uFE0F", "Pen", Sticker.CATEGORY_WRITING),
        Sticker("envelope", "\uD83D\uDCEC", "Inbox", Sticker.CATEGORY_WRITING),
        Sticker("scissors", "\u2702\uFE0F", "Cut", Sticker.CATEGORY_WRITING),
        Sticker("hourglass", "\u231B", "Time", Sticker.CATEGORY_WRITING)
    )

    fun all(): List<Sticker> = ALL

    fun byId(id: String?): Sticker? = ALL.firstOrNull { it.id == id }

    fun isValidId(id: String?): Boolean = byId(id) != null

    /** Every sticker whose category is one of the declared category names. */
    fun isValidCategory(category: String?): Boolean = category in CATEGORIES

    /** Phase 28: stickers in one category (ordered as listed in ALL). */
    fun byCategory(category: String): List<Sticker> =
        ALL.filter { it.category == category }

    /** Phase 28: case-insensitive search over label + emoji + category. */
    fun search(query: String): List<Sticker> {
        val q = query.trim()
        if (q.isEmpty()) return ALL
        return ALL.filter { sticker ->
            sticker.label.contains(q, ignoreCase = true) ||
                sticker.category.contains(q, ignoreCase = true) ||
                sticker.emoji == q
        }
    }

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