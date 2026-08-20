package com.authorss81.noteflow

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 186 — source-regression pins for the gallery quick-action menu.
 *
 * The gallery card must route Pin/Edit-Tags/Trash through the SAME ViewModel
 * calls the List view (`NotePageCard`) uses, so a future refactor can never
 * fork the two surfaces into diverging behaviors (e.g. a gallery-only thin
 * copy that bypasses `writeGuardedAgainstLock`, or a tags write that skips the
 * TagEditorDialog path). These pins are mechanical on purpose.
 */
class Phase186GalleryQuickActionsTest {

    private fun mainSource(rel: String): String {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            val d: File = dir
            File(d, "src/main/kotlin/com/authorss81/noteflow/$rel").takeIf { it.isFile }?.let { return it.readText() }
            File(d, "src/main/kotlin/$rel").takeIf { it.isFile }?.let { return it.readText() }
            File(d, "app/src/main/kotlin/com/authorss81/noteflow/$rel").takeIf { it.isFile }?.let { return it.readText() }
            dir = d.parentFile
        }
        throw AssertionError("could not locate app/src/main/kotlin/$rel from ${start.path}")
    }

    @Test
    fun `pin action routes through the shared ViewModel call`() {
        val src = mainSource("ui/components/GalleryView.kt")
        assertTrue(
            "gallery Pin/Unpin must call the SAME togglePinPage(page.id, page.pinned) " +
                "the list view uses (HomeScreen.kt:1357/1387)",
            src.contains("viewModel.togglePinPage(page.id, page.pinned)")
        )
        assertTrue(
            "the toggle label must come from the pure-JVM policy",
            src.contains("GalleryCardActionsPolicy.pinMenuLabel(page.pinned)")
        )
    }

    @Test
    fun `trash action routes through the shared ViewModel call`() {
        val src = mainSource("ui/components/GalleryView.kt")
        assertTrue(
            "gallery Move to Trash must call the SAME trashPage(page.id) the list " +
                "view uses (HomeScreen.kt:1363/1393)",
            src.contains("viewModel.trashPage(page.id)")
        )
        assertTrue(
            "the trash label must come from the policy",
            src.contains("GalleryCardActionsPolicy.MOVE_TO_TRASH_LABEL")
        )
    }

    @Test
    fun `edit tags flows through the shared TagEditorDialog path`() {
        val gallery = mainSource("ui/components/GalleryView.kt")
        assertTrue(
            "gallery Edit Tags must surface the page via the onEditTags callback " +
                "so HomeScreen's tagEditorTargetPage drives the SAME TagEditorDialog",
            gallery.contains("onEditTags(page)")
        )
        val home = mainSource("ui/screens/HomeScreen.kt")
        assertTrue(
            "HomeScreen must wire the gallery onEditTags to tagEditorTargetPage",
            home.contains("onEditTags = { tagEditorTargetPage = it }")
        )
        assertTrue(
            "HomeScreen TagEditorDialog still saves via updatePageTags",
            home.contains("viewModel.updatePageTags(page.id, newTags)")
        )
    }

    @Test
    fun `kind labels all come from the pure-JVM policy`() {
        val src = mainSource("ui/components/GalleryView.kt")
        assertTrue(
            "Edit Tags label comes from the policy, not an inline literal",
            src.contains("GalleryCardActionsPolicy.EDIT_TAGS_LABEL")
        )
    }

    @Test
    fun `pinned badge uses PushPin in primary tint and stays compact`() {
        val src = mainSource("ui/components/GalleryView.kt")
        assertTrue(
            "badge is the PushPin icon",
            src.contains("imageVector = Icons.Outlined.PushPin")
        )
        assertTrue(
            "badge tint is the primary colour",
            src.contains("tint = scheme.primary")
        )
        assertTrue(
            "badge is compact 18dp so the header fits a ~140dp grid column",
            src.contains(".size(18.dp)")
        )
    }

    @Test
    fun `overflow menu is compact and trash item is error tinted`() {
        val src = mainSource("ui/components/GalleryView.kt")
        assertTrue(
            "MoreVert button is compact ~28dp",
            src.contains("Modifier.size(28.dp)")
        )
        assertTrue(
            "MoreVert icon is compact 18dp",
            src.contains("imageVector = Icons.Outlined.MoreVert")
        )
        assertTrue(
            "trash item text is error coloured",
            src.contains("color = scheme.error")
        )
        assertTrue(
            "trash item leading icon is error tinted",
            src.contains("tint = scheme.error")
        )
    }

    @Test
    fun `gallery menu stays non-trash only`() {
        val src = mainSource("ui/components/GalleryView.kt")
        assertTrue(
            "gallery is the active-pages view; the menu must not branch on trash state",
            !src.contains("isTrash")
        )
        assertTrue(
            "the app's hard delete (Delete Permanently) is NOT offered on a gallery card",
            !src.contains("Delete Permanently")
        )
    }
}