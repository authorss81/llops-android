package com.authorss81.noteflow

import com.authorss81.noteflow.services.GalleryTagRowPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 188 — gallery tag-row cap policy (pure JVM).
 *
 * The user visual review risk #4: multi-tag notes made the chip row grow
 * unboundedly (up to 3 chips in a WRAPPING `FlowRow`), crowding the "+N" badge
 * and the update timestamp at 1.3–1.5x font scale. The policy caps the row at
 * [GalleryTagRowPolicy.MAX_VISIBLE_TAGS] chips + a "+N" badge, single line.
 */
class GalleryTagRowPolicyTest {

    @Test
    fun `maximum visible chips is two`() {
        assertEquals(2, GalleryTagRowPolicy.MAX_VISIBLE_TAGS)
    }

    @Test
    fun `parses the raw comma field with trimming and hash stripping`() {
        assertEquals(
            listOf("work", "idea"),
            GalleryTagRowPolicy.parseTags("work, #idea, ")
        )
        assertEquals(
            listOf("a", "b", "c"),
            GalleryTagRowPolicy.parseTags("a,b,c")
        )
        assertEquals(emptyList<String>(), GalleryTagRowPolicy.parseTags(""))
        assertEquals(emptyList<String>(), GalleryTagRowPolicy.parseTags(" , ,, "))
    }

    @Test
    fun `visible chips cap at two from any tag count`() {
        assertEquals(listOf("a", "b"), GalleryTagRowPolicy.visibleChips(listOf("a", "b")))
        assertEquals(listOf("a", "b"), GalleryTagRowPolicy.visibleChips(listOf("a", "b", "c")))
        assertEquals(listOf("a", "b"), GalleryTagRowPolicy.visibleChips(listOf("a", "b", "c", "d", "e")))
        assertEquals(listOf("a"), GalleryTagRowPolicy.visibleChips(listOf("a")))
        assertEquals(emptyList<String>(), GalleryTagRowPolicy.visibleChips(emptyList()))
    }

    @Test
    fun `convenience raw-field overload caps at two`() {
        assertEquals(listOf("work", "idea"), GalleryTagRowPolicy.visibleChips("work, idea, extra, more"))
    }

    @Test
    fun `hidden count is the tags beyond the cap, never negative`() {
        assertEquals(0, GalleryTagRowPolicy.hiddenChipCount(listOf("a")))
        assertEquals(0, GalleryTagRowPolicy.hiddenChipCount(listOf("a", "b")))
        assertEquals(1, GalleryTagRowPolicy.hiddenChipCount(listOf("a", "b", "c")))
        assertEquals(2, GalleryTagRowPolicy.hiddenChipCount(listOf("a", "b", "c", "d")))
        assertEquals(0, GalleryTagRowPolicy.hiddenChipCount(emptyList()))
    }

    @Test
    fun `convenience raw-field hidden count works`() {
        assertEquals(3, GalleryTagRowPolicy.hiddenChipCount("a,b,c,d,e"))
    }

    @Test
    fun `badge text renders the plus-N form only when tags are hidden`() {
        assertEquals(null, GalleryTagRowPolicy.hiddenBadgeText(0))
        assertEquals(null, GalleryTagRowPolicy.hiddenBadgeText(-1))
        assertEquals("+1", GalleryTagRowPolicy.hiddenBadgeText(1))
        assertEquals("+3", GalleryTagRowPolicy.hiddenBadgeText(3))
    }

    @Test
    fun `chip text prefixes a hash like the tag store convention`() {
        assertEquals("#work", GalleryTagRowPolicy.chipText("work"))
    }

    @Test
    fun `end-to-end multi-tag note yields two chips plus a badge`() {
        val chips = GalleryTagRowPolicy.visibleChips(listOf("alpha", "beta", "gamma", "delta"))
        val hidden = GalleryTagRowPolicy.hiddenChipCount(listOf("alpha", "beta", "gamma", "delta"))
        val badge = GalleryTagRowPolicy.hiddenBadgeText(hidden)
        assertEquals(listOf("alpha", "beta"), chips)
        assertEquals(2, hidden)
        assertEquals("+2", badge)
        assertTrue("the badge must exist for a 4-tag note", badge != null)
        assertFalse("no badge for a 2-tag note", GalleryTagRowPolicy.hiddenBadgeText(0) != null)
    }

    @Test
    fun `no tag row growth - every row renders at most two chips plus one badge`() {
        // Encode "1..N chips" → (rendered chips, badge?) table to pin the invariant.
        val rows = (0..8).map { n ->
            val tags = (1..n).map { "t$it" }
            val chips = GalleryTagRowPolicy.visibleChips(tags)
            val badge = GalleryTagRowPolicy.hiddenBadgeText(GalleryTagRowPolicy.hiddenChipCount(tags))
            chips.size to (badge != null)
        }
        assertTrue("2-tag note: 2 chips, no badge", rows[2] == 2 to false)
        assertTrue("3-tag note: 2 chips + badge", rows[3] == 2 to true)
        assertTrue("0-tag note: nothing renders", rows[0] == 0 to false)
        assertTrue("8-tag note still bounded: 2 chips + badge", rows[8] == 2 to true)
        assertNull(GalleryTagRowPolicy.hiddenBadgeText(GalleryTagRowPolicy.hiddenChipCount(emptyList())))
    }
}