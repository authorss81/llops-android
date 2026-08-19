package com.authorss81.noteflow

import com.authorss81.noteflow.services.ColorRecentsPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM policy tests for Phase 172 — persistent recently-used colors +
 * favorites. Verifies the caps, dedupe, most-recent-first ordering, the star
 * toggle (bounded), and the fail-closed prefs wire format.
 */
class ColorRecentsPolicyTest {

    @Test
    fun `recordRecent moves the color to front and dedupes earlier occurrences`() {
        val result = ColorRecentsPolicy.recordRecent(listOf(0xFF111111.toInt(), 0xFF222222.toInt()), 0xFF111111.toInt())
        assertEquals(listOf(0xFF111111.toInt(), 0xFF222222.toInt()), result)
    }

    @Test
    fun `recordRecent new color is prepended`() {
        val result = ColorRecentsPolicy.recordRecent(listOf(0xFF111111.toInt(), 0xFF222222.toInt()), 0xFF333333.toInt())
        assertEquals(listOf(0xFF333333.toInt(), 0xFF111111.toInt(), 0xFF222222.toInt()), result)
    }

    @Test
    fun `recordRecent caps at MAX_RECENT_COLORS keeping the newest 16`() {
        val list = List(ColorRecentsPolicy.MAX_RECENT_COLORS + 5) { it }
        val result = ColorRecentsPolicy.recordRecent(list, 999999)
        assertEquals(ColorRecentsPolicy.MAX_RECENT_COLORS, result.size)
        assertEquals(999999, result.first())
        assertTrue(999999 !in list)
        assertTrue(result.size <= ColorRecentsPolicy.MAX_RECENT_COLORS)
    }

    @Test
    fun `recordRecent on empty list yields single entry`() {
        assertEquals(listOf(0xFF111111.toInt()), ColorRecentsPolicy.recordRecent(emptyList(), 0xFF111111.toInt()))
    }

    @Test
    fun `isFavorite is exact-ARGB`() {
        assertTrue(ColorRecentsPolicy.isFavorite(listOf(5, 7), 7))
        assertFalse(ColorRecentsPolicy.isFavorite(listOf(5, 7), 8))
        assertFalse(ColorRecentsPolicy.isFavorite(emptyList(), 1))
    }

    @Test
    fun `toggleFavorite adds absent color at front`() {
        val result = ColorRecentsPolicy.toggleFavorite(listOf(1, 2), 3)
        assertEquals(listOf(3, 1, 2), result)
    }

    @Test
    fun `toggleFavorite removes present color`() {
        val result = ColorRecentsPolicy.toggleFavorite(listOf(1, 2, 3), 2)
        assertEquals(listOf(1, 3), result)
        assertEquals(ColorRecentsPolicy.MAX_FAVORITE_COLORS, ColorRecentsPolicy.MAX_FAVORITE_COLORS)
    }

    @Test
    fun `toggleFavorite caps at MAX_FAVORITE_COLORS keeping the newest`() {
        var favorites = List(ColorRecentsPolicy.MAX_FAVORITE_COLORS) { it }
        favorites = ColorRecentsPolicy.toggleFavorite(favorites, 424242)
        assertEquals(ColorRecentsPolicy.MAX_FAVORITE_COLORS, favorites.size)
        assertEquals(424242, favorites.first())
        // Toggling the existing oldest stays bounded and removes it.
        val removed = ColorRecentsPolicy.toggleFavorite(favorites, 0)
        assertEquals(ColorRecentsPolicy.MAX_FAVORITE_COLORS - 1, removed.size)
        assertFalse(removed.contains(0))
    }

    @Test
    fun `encode decode round-trips an ordering`() {
        val colors = listOf(0xFF112233.toInt(), 0xFF445566.toInt(), 0xFF778899.toInt())
        val encoded = ColorRecentsPolicy.encodeColors(colors)
        assertEquals(colors, ColorRecentsPolicy.decodeColors(encoded))
    }

    @Test
    fun `decode is fail-closed for blank and malformed input`() {
        assertEquals(emptyList<Int>(), ColorRecentsPolicy.decodeColors(null))
        assertEquals(emptyList<Int>(), ColorRecentsPolicy.decodeColors(""))
        assertEquals(emptyList<Int>(), ColorRecentsPolicy.decodeColors("   "))
        // Malformed tokens are skipped, valid ones retained (a hand-edited pref).
        assertEquals(listOf(1, 3), ColorRecentsPolicy.decodeColors("1,banana,3,1.5"))
    }

    @Test
    fun `decode dedupes and preserves order`() {
        assertEquals(listOf(4, 9), ColorRecentsPolicy.decodeColors("4,9,4"))
    }

    @Test
    fun `sanitizeRecent enforces dedupe and cap`() {
        val tooMany = (0 until ColorRecentsPolicy.MAX_RECENT_COLORS + 4).toList() + listOf(0)
        val cleaned = ColorRecentsPolicy.sanitizeRecent(tooMany)
        assertEquals(ColorRecentsPolicy.MAX_RECENT_COLORS, cleaned.size)
        assertEquals(cleaned.size, cleaned.distinct().size)
        assertEquals(0, cleaned.first()) // dedupe keeps first occurrence, cap drops the tail
    }

    @Test
    fun `sanitizeFavorites enforces dedupe and cap`() {
        val cleaned = ColorRecentsPolicy.sanitizeFavorites(List(ColorRecentsPolicy.MAX_FAVORITE_COLORS + 3) { it })
        assertEquals(ColorRecentsPolicy.MAX_FAVORITE_COLORS, cleaned.size)
        assertEquals(cleaned.size, cleaned.distinct().size)
    }
}