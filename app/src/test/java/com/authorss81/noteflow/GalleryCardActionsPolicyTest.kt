package com.authorss81.noteflow

import com.authorss81.noteflow.services.GalleryCardActionsPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 186 — gallery card quick-action menu policy (pure JVM).
 *
 * Single source of truth for the gallery card overflow menu: ordered items,
 * pin/unpin label flip, pinned-badge show rule, accessibility description and
 * the destructive-item tint rule. Kept pure so the menu contract is testable
 * without Compose.
 */
class GalleryCardActionsPolicyTest {

    @Test
    fun `menu items order matches list view precedence`() {
        assertEquals(
            listOf("Pin", "Edit Tags", "Move to Trash"),
            GalleryCardActionsPolicy.menuItems(pinned = false)
        )
        assertEquals(
            listOf("Unpin", "Edit Tags", "Move to Trash"),
            GalleryCardActionsPolicy.menuItems(pinned = true)
        )
    }

    @Test
    fun `menu is exactly three active-page actions`() {
        listOf(false, true).forEach { pinned ->
            val items = GalleryCardActionsPolicy.menuItems(pinned)
            assertEquals("exactly 3 items for both pin states", 3, items.size)
            assertFalse(
                "gallery menu must never offer the permanent-delete action " +
                    "(it stays behind the app's confirmation gate)",
                items.any { it == "Delete Permanently" }
            )
        }
    }

    @Test
    fun `pin label flips with the pin state`() {
        assertEquals(GalleryCardActionsPolicy.PIN_LABEL, GalleryCardActionsPolicy.pinMenuLabel(false))
        assertEquals(GalleryCardActionsPolicy.UNPIN_LABEL, GalleryCardActionsPolicy.pinMenuLabel(true))
    }

    @Test
    fun `pinned badge renders only for pinned pages`() {
        assertTrue(GalleryCardActionsPolicy.showPinnedBadge(true))
        assertFalse(GalleryCardActionsPolicy.showPinnedBadge(false))
    }

    @Test
    fun `content description is explicit for accessibility`() {
        assertEquals("Pin this note", GalleryCardActionsPolicy.pinContentDescription(false))
        assertEquals("Unpin this note", GalleryCardActionsPolicy.pinContentDescription(true))
    }

    @Test
    fun `only the trash action is destructive (error tint)`() {
        GalleryCardActionsPolicy.menuItems(false).forEach { item ->
            assertEquals(
                "only Move to Trash is marked destructive, not $item",
                item == GalleryCardActionsPolicy.MOVE_TO_TRASH_LABEL,
                GalleryCardActionsPolicy.isDestructive(item)
            )
        }
        assertFalse(GalleryCardActionsPolicy.isDestructive("Edit Tags"))
        assertFalse(GalleryCardActionsPolicy.isDestructive("Pin"))
        assertFalse(GalleryCardActionsPolicy.isDestructive("Unpin"))
    }
}