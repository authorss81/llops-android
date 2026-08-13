package com.authorss81.noteflow

import com.authorss81.noteflow.services.StickerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerCatalogTest {

    @Test
    fun `catalog is non-empty and resolvable`() {
        val all = StickerCatalog.all()
        assertTrue("must ship at least one sticker", all.isNotEmpty())
        val ids = all.map { it.id }
        assertEquals("ids must be unique", ids.size, ids.toSet().size)
        all.forEach { sticker ->
            assertEquals(
                "byId round-trip for ${sticker.id}",
                sticker.label,
                StickerCatalog.byId(sticker.id)?.label
            )
        }
        assertNull("unknown id resolves null", StickerCatalog.byId("not-a-sticker"))
    }

    @Test
    fun `every sticker has a non-empty emoji glyph`() {
        StickerCatalog.all().forEach { sticker ->
            assertTrue("emoji must not be blank for ${sticker.id}", sticker.emoji.isNotBlank())
        }
    }

    @Test
    fun `placeTopLeft centres on the tap and clamps at the bottom edge`() {
        // Tap near top-left → sticker's top-left should be tap - size/2.
        val nearCorner = StickerCatalog.placeTopLeft(tapX = 100f, tapY = 100f, size = 140f, pageWidth = 1080f, pageHeight = 1528f)
        assertEquals("x centres relative to tap", 100f - 70f, nearCorner.first, 1e-3f)
        assertEquals("y centres relative to tap", 100f - 70f, nearCorner.second, 1e-3f)

        // Tap beyond the right/bottom edge → clamped so the square fits inside.
        val overEdge = StickerCatalog.placeTopLeft(tapX = 2000f, tapY = 2000f, size = 140f, pageWidth = 1080f, pageHeight = 1528f)
        assertEquals("x clamps to pageWidth-size", 1080f - 140f, overEdge.first, 1e-3f)
        assertEquals("y clamps to pageHeight-size", 1528f - 140f, overEdge.second, 1e-3f)
        assertTrue("placed square stays inside page", overEdge.first + 140f <= 1080f + 1e-3f)
        assertTrue("placed square stays inside page", overEdge.second + 140f <= 1528f + 1e-3f)
    }

    @Test
    fun `placeTopLeft clamps off-page taps`() {
        val beforeEdge = StickerCatalog.placeTopLeft(tapX = -500f, tapY = -500f, size = 140f, pageWidth = 1080f, pageHeight = 1528f)
        assertEquals("negative tap clamps to 0", 0f, beforeEdge.first, 1e-3f)
        assertEquals("negative tap clamps to 0", 0f, beforeEdge.second, 1e-3f)
    }

    @Test
    fun `sticker default size is large enough to interact with`() {
        assertTrue("default size must be usable for a finger target", StickerCatalog.DEFAULT_SIZE * 0.5f >= 20f)
        assertFalse("catalog must never resolve a blank id", StickerCatalog.isValidId(""))
    }
}