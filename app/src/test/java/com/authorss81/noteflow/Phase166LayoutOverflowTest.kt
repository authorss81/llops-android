package com.authorss81.noteflow

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 166 (2026-08-19): overflow-layout regression guard.
 *
 * On a 360dp portrait screen (the narrowest supported width) a fixed-width Row
 * CANNOT render side-by-side wide controls — it clips whichever child does not
 * fit. The fixed surfaces are pinned here so a future edit cannot silently
 * re-introduce the bug:
 *   - WebDavSyncDialog: the two primary actions live in the dialog BODY as full-width
 *     buttons (the confirm slot holds only "Close"). A reversion to a two-button
 *     confirm row fails the suite.
 *   - CalendarView: the date summary + "New Note for Date" stack in a Column
 *     instead of a SpaceBetween row.
 *   - HomeScreen import dialog: the Orientation chips row is horizontalScroll.
 *   - HomeScreen filtered-by-tag banner: the tag path text is flexible (weight)
 *     and ellipsizes instead of pushing the close button out.
 *   - MarkdownPreviewScreen: view-mode / split-orientation / serif chips moved
 *     out of the top app bar into a scrollable full-width sub-bar.
 *   - InteractiveTutorial: "Skip Tutorial" sits on its own line; the Back /
 *     Skip-step / Next row is end-aligned so it can never crowd the whole bar.
 *   - KnowledgeGraphScreen: the selected-node card title wraps/ellipsizes.
 */
class Phase166LayoutOverflowTest {

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

    // --- WebDavSyncDialog -------------------------------------------

    @Test
    fun `WebDavSyncDialog actions are full-width body buttons, confirm holds only Close`() {
        val src = mainSource("ui/components/WebDavSyncDialog.kt")
        // The two primary actions must live in the dialog BODY (the AlertDialog text
        // slot), not the compact right-aligned confirm row that clipped them on 360dp.
        val body = src.substring(src.indexOf("text = {"), src.indexOf("confirmButton = {"))
        assertTrue(
            "Upload Backup action must live in the dialog body",
            body.contains("Text(\"Upload Backup\")")
        )
        assertTrue(
            "Download & Restore action must live in the dialog body",
            body.contains("Text(\"Download & Restore\")")
        )
        // Each action button must be full-width so no supported width can clip it.
        // \b ensures the anchor is the Button/OutlinedButton, not TextButton or the
        // "Button(" substring inside OutlinedButton.
        val uploadStart = Regex("\\bButton\\(").find(body)!!.range.first
        assertTrue(
            "Upload Backup must be a full-width button",
            body.substring(uploadStart, body.indexOf("Text(\"Upload Backup\")")).contains("Modifier.fillMaxWidth()")
        )
        val downloadStart = Regex("\\bOutlinedButton\\(").findAll(body).last().range.first
        assertTrue(
            "Download & Restore must be a full-width button",
            body.substring(downloadStart, body.indexOf("Text(\"Download & Restore\")")).contains("Modifier.fillMaxWidth()")
        )
        // The confirm slot may hold only Close — a reversion to a two-button
        // confirm row (the phase-166 bug) fails this suite.
        val confirm = src.substring(src.indexOf("confirmButton = {"))
        assertTrue("confirm slot must hold Close", confirm.contains("Text(\"Close\")"))
        assertFalse("confirm slot must not re-hold the primary actions", confirm.contains("Icons.Outlined.CloudUpload"))
    }

    // --- CalendarView ------------------------------------------------

    @Test
    fun `CalendarView stacks the date summary above the new-note button`() {
        val src = mainSource("ui/components/CalendarView.kt")
        val section = src.substring(
            src.indexOf("// Selected Date Details & Pages List"),
            src.indexOf("New Note for Date")
        )
        assertTrue("date summary must be inside a Column", section.isNotEmpty())
        assertFalse(
            "no SpaceBetween Row may contain the long date summary and the button",
            section.contains("Arrangement.SpaceBetween")
        )
    }

    // --- HomeScreen import orientation chips -------------------------

    @Test
    fun `import orientation chip row is horizontally scrollable`() {
        val src = mainSource("ui/screens/HomeScreen.kt")
        val chips = src.substring(src.indexOf("Page Format & Orientation:"), src.indexOf("Select document layout structure"))
        assertTrue("orientation chips must scroll instead of clipping", chips.contains("horizontalScroll(rememberScrollState())"))
    }

    // --- HomeScreen filtered-by-tag banner ---------------------------

    @Test
    fun `filtered-by-tag banner text is weight-flexible and ellipsizes`() {
        val src = mainSource("ui/screens/HomeScreen.kt")
        val banner = src.substring(src.indexOf("Filtered by tag:"), src.indexOf("Clear Tag Filter"))
        assertTrue("tag path text must take the flexible width", banner.contains("Modifier.weight(1f)"))
        assertTrue("tag path text must ellipsize instead of overflowing", banner.contains("TextOverflow.Ellipsis"))
    }

    // --- MarkdownPreviewScreen top-bar declutter ----------------------

    @Test
    fun `preview app-bar title holds only the title and the reader toggle chip`() {
        val src = mainSource("ui/screens/MarkdownPreviewScreen.kt")
        val titleBlock = src.substring(src.indexOf("title = {"), src.indexOf("navigationIcon = {"))
        assertEquals(
            "the app-bar title must contain exactly one chip (Reader)",
            1,
            Regex("FilterChip\\(").findAll(titleBlock).count()
        )
        assertFalse("split-orientation chip must not live in the app bar", titleBlock.contains("Auto (Top/Bottom)"))
    }

    @Test
    fun `preview view mode and serif chips moved to a scrollable sub-bar`() {
        val src = mainSource("ui/screens/MarkdownPreviewScreen.kt")
        val subBarStart = src.indexOf("// Phase 166: the view-mode, split-orientation")
        assertTrue("the full-width sub-bar must exist under the app bar", subBarStart >= 0)
        val subBar = src.substring(subBarStart, src.indexOf("if (showSlashCommands)"))
        assertTrue("sub-bar must be horizontally scrollable", subBar.contains("horizontalScroll(rememberScrollState())"))
        assertTrue("Serif chip must live in the sub-bar", subBar.contains("Serif"))
        assertTrue("view-mode chip must live in the sub-bar", subBar.contains("viewMode.name"))
        // The serif toggle must have left the top app bar entirely: the region from
        // TopAppBar opening through the Scaffold content lambda may not reference it.
        val appBarRegion = src.substring(src.indexOf("TopAppBar("), src.indexOf(") { padding ->"))
        assertFalse("serif toggle must not crowd the app bar", appBarRegion.contains("serifReadingEnabled"))
    }

    // --- InteractiveTutorial action rows ------------------------------

    @Test
    fun `tutorial keeps Skip Tutorial on its own line, actions end-aligned`() {
        val src = mainSource("ui/components/InteractiveTutorial.kt")
        // Anchor after the comma | param list (line ~132) and the "Skip Tutorial"
        // mention in the back-handler comment: slice the actual action area.
        val from = src.indexOf("// Phase 166: \"Skip Tutorial\" previously sat")
        assertTrue("the phased action layout must exist", from >= 0)
        val to = src.indexOf("onDontShowAgain", from)
        val actions = src.substring(from, to)
        assertTrue("action buttons must be end-aligned", actions.contains("Arrangement.End"))
        // Skip Tutorial (own line) and the Next/Back row (end-aligned) must not
        // share one SpaceBetween row — there is no fixed-width split to overflow.
        assertFalse(
            "back/next row must not be SpaceBetween against Skip Tutorial",
            actions.contains("Arrangement.SpaceBetween")
        )
    }

    // --- KnowledgeGraphScreen selected-node card ----------------------

    @Test
    fun `knowledge graph node card title wraps instead of overflowing`() {
        val src = mainSource("ui/screens/KnowledgeGraphScreen.kt")
        val card = src.substring(src.indexOf("node.page.title"), src.indexOf("\"Open Note\""))
        assertTrue("node title must take the flexible width", card.contains("Modifier.weight(1f)"))
        assertTrue("node title must wrap with an ellipsis", card.contains("maxLines = 2"))
    }
}