package com.authorss81.noteflow

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 263: home/gallery/tab restoration + import + overflow regression guard.
 *
 * Rotation used to wipe the search query (debounce orphaned), jump the tab back
 * to Pages (re-targeting destructive bulk verbs), and drop the 10-file import
 * dialog; the 4-tab PrimaryTabRow clipped its labels at 320-360dp; and a set of
 * IconButtons shipped 20-36dp hit areas. These source pins hold the fixes:
 * rememberSaveable keys (+ Uri/Set savers), ScrollableTabRow, 48dp minimums,
 * Search IME + isSearching spinner, and the 150dp gallery floor.
 */
class Phase263HomeGalleryTest {

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

    private fun home(): String = mainSource("ui/screens/HomeScreen.kt")

    // --- restoration: saveable keys ---------------------------------

    @Test
    fun `search query survives rotation`() {
        val src = home()
        assertTrue(
            "searchQuery must be rememberSaveable",
            src.contains("var searchQuery by rememberSaveable")
        )
    }

    @Test
    fun `tab and view mode survive rotation`() {
        val src = home()
        assertTrue("selectedTab must be rememberSaveable", src.contains("var selectedTab by rememberSaveable"))
        assertTrue("pageViewMode must be rememberSaveable", src.contains("var pageViewMode by rememberSaveable"))
    }

    @Test
    fun `tag filter and selection survive rotation`() {
        val src = home()
        assertTrue("activeTagFilterPath must be rememberSaveable", src.contains("var activeTagFilterPath by rememberSaveable"))
        assertTrue("activeTagMatchingIds must be rememberSaveable", src.contains("var activeTagMatchingIds by rememberSaveable"))
        assertTrue("multiSelectedIds must be rememberSaveable", src.contains("var multiSelectedIds by rememberSaveable"))
    }

    @Test
    fun `import dialog state survives rotation via Uri saver`() {
        val src = home()
        assertTrue("pendingImportUris must be rememberSaveable", src.contains("var pendingImportUris by rememberSaveable"))
        assertTrue("selectedImportOrientation must be rememberSaveable", src.contains("var selectedImportOrientation by rememberSaveable"))
        assertTrue("showMultiPageImportDialog must be rememberSaveable", src.contains("var showMultiPageImportDialog by rememberSaveable"))
        assertTrue("Uri list must round-trip as strings", src.contains("homeImportUriListSaver"))
        assertTrue("Uri saver must stringify", src.contains("uris.map { it.toString() }"))
        assertTrue("Uri saver must re-parse", src.contains("Uri.parse(it)"))
    }

    @Test
    fun `dialog flags survive rotation`() {
        val src = home()
        listOf(
            "showSecurityDialog", "showUpdateDialog", "showPluginsDialog",
            "showPluginStoreDialog", "showTemplateLibrary", "showWebDavDialog",
            "showLocalSendDialog", "showWebCaptureDialog", "showTagManagerDialog",
            "showBulkTagDialog", "showRestartConfirmDialog",
            "promptDialogType", "deleteConfirmType"
        ).forEach { name ->
            assertTrue(
                "$name must be rememberSaveable",
                src.contains("var $name by rememberSaveable")
            )
        }
    }

    @Test
    fun `no plain-remember dialog or tab state regresses`() {
        val src = home()
        listOf(
            "var selectedTab by remember {",
            "var pageViewMode by remember {",
            "var showMultiPageImportDialog by remember {",
            "var showPluginStoreDialog by remember {",
            "var selectedImportOrientation by remember {"
        ).forEach { stale ->
            assertFalse(
                "stale plain remember must be gone: $stale",
                src.contains(stale)
            )
        }
        // The search field has exactly three saveable declarations: the home
        // query plus the notebook/section panel-local filters.
        assertTrue(
            "three saveable searchQuery declarations",
            src.split("var searchQuery by rememberSaveable").size == 4
        )
    }

    // --- tabs: scrollable, ellipsized --------------------------------

    @Test
    fun `home tabs use ScrollableTabRow with ellipsized labels`() {
        val src = home()
        assertTrue("tabs must be scrollable", src.contains("ScrollableTabRow("))
        assertFalse("fixed PrimaryTabRow must be gone", src.contains("PrimaryTabRow("))
        assertTrue(
            "tab labels must ellipsize on one line",
            src.contains("Text(\"Tag Vault\", maxLines = 1, overflow = TextOverflow.Ellipsis)")
        )
    }

    // --- search IME + isSearching ------------------------------------

    @Test
    fun `search field has Search IME action and label semantics`() {
        val src = home()
        assertTrue("imeAction=Search required", src.contains("imeAction = ImeAction.Search"))
        assertTrue("onSearch must clear focus", src.contains("onSearch = { focusManager.clearFocus() }"))
        assertTrue(
            "leading search icon needs a label",
            src.contains("Icons.Outlined.Search, contentDescription = \"Search\"")
        )
    }

    @Test
    fun `debounce gap shows spinner instead of empty state`() {
        val src = home()
        assertTrue("isSearching flag required", src.contains("var isSearching by remember"))
        assertTrue("isSearching set before debounce", src.contains("isSearching = true"))
        assertTrue("isSearching cleared after search", src.contains("isSearching = false"))
        assertTrue(
            "spinner branch must gate the empty state",
            src.contains("if (isSearching && activePageList.isEmpty())")
        )
        assertTrue("spinner shown during debounce", src.contains("CircularProgressIndicator()"))
    }

    // --- 48dp touch targets ------------------------------------------

    @Test
    fun `home compact controls enforce 48dp hit areas`() {
        val src = home()
        assertTrue("sort button needs 48dp minimum", src.contains("Modifier.size(32.dp).minimumInteractiveComponentSize()"))
        assertTrue("chip dismiss needs 48dp minimum", src.contains("Modifier.size(20.dp).minimumInteractiveComponentSize()"))
        assertTrue("tag-filter clear needs 48dp minimum", src.contains("Modifier.size(24.dp).minimumInteractiveComponentSize()"))
    }

    @Test
    fun `gallery overflow menu enforces 48dp hit area`() {
        val src = mainSource("ui/components/GalleryView.kt")
        assertTrue(
            "gallery MoreVert needs 48dp minimum",
            src.contains(".size(28.dp)") && src.contains(".minimumInteractiveComponentSize()")
        )
    }

    @Test
    fun `sidebar icon buttons enforce 48dp hit areas`() {
        val src = mainSource("ui/components/UnifiedSidebar.kt")
        val pins = src.split("minimumInteractiveComponentSize()").size - 1
        assertTrue(
            "sidebar needs >= 6 minimums (header + notebook x2 + section x2 + page), found $pins",
            pins >= 6
        )
    }

    @Test
    fun `lock biometric button enforces 48dp hit area`() {
        val src = mainSource("ui/screens/LockScreen.kt")
        assertTrue("biometric IconButton needs 48dp minimum", src.contains("minimumInteractiveComponentSize()"))
    }

    @Test
    fun `editor dismiss controls enforce 48dp hit areas`() {
        val src = mainSource("ui/screens/EditorScreen.kt")
        assertTrue(
            "voice dismiss needs 48dp minimum",
            src.contains("Modifier.size(28.dp).minimumInteractiveComponentSize()")
        )
        assertTrue(
            "reference-image close needs 48dp minimum",
            src.contains("Modifier.size(26.dp).minimumInteractiveComponentSize()")
        )
    }

    // --- gallery grid + semantics ------------------------------------

    @Test
    fun `gallery grid fits two columns on a 360dp phone`() {
        val src = mainSource("ui/components/GalleryView.kt")
        assertTrue("150dp adaptive floor required", src.contains("GridCells.Adaptive(minSize = 150.dp)"))
        assertFalse("168dp single-column floor must be gone", src.contains("minSize = 168.dp"))
    }

    @Test
    fun `gallery type badge is announced to TalkBack`() {
        val src = mainSource("ui/components/GalleryView.kt")
        assertTrue(
            "type badge needs a description",
            src.contains("contentDescription = pageTypeLabel(page)")
        )
    }
}
