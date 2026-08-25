package com.authorss81.noteflow

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 208 — page management UX: source pins for the five shipped fixes.
 *
 * 1. CRITICAL trash-search safety: HomeScreen scopes search results per tab via
 *    [com.authorss81.noteflow.services.TrashSearchScopePolicy] (live notes can
 *    never render under the Restore / Delete Permanently menu on the Trash tab).
 * 2. Sort control: persisted SettingsManager.pageSortModeKey + PageSortPolicy
 *    applied to the Pages-tab lists (search relevance order preserved).
 * 3. Move/Duplicate UI: both card menus expose "Move to Section…" and
 *    "Duplicate"; the repository duplicatePage re-encrypts under NEW record AAD.
 * 4. Multi-select: long-press selection in list + gallery, contextual bulk bar,
 *    bulk permanent-delete confirmation gate.
 * 5. Palm rejection persists via SettingsManager and is surfaced in the Canvas &
 *    Paper Options sheet.
 */
class Phase208PageManagementTest {

    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (File(dir, "gradle/libs.versions.toml").isFile && File(dir, "app").isDirectory) return dir
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }

    private fun read(path: String): String =
        File(repoRoot(), path).readText()

    private fun homeScreen(): String =
        read("app/src/main/kotlin/com/authorss81/noteflow/ui/screens/HomeScreen.kt")

    private fun galleryView(): String =
        read("app/src/main/kotlin/com/authorss81/noteflow/ui/components/GalleryView.kt")

    private fun editorScreen(): String =
        read("app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt")

    private fun repository(): String =
        read("app/src/main/kotlin/com/authorss81/noteflow/data/repository/NoteRepository.kt")

    private fun settingsManager(): String =
        read("app/src/main/kotlin/com/authorss81/noteflow/services/SettingsManager.kt")

    // ---------------- fix #1: trash-search scoping ----------------

    @Test
    fun `home scopes search results through TrashSearchScopePolicy`() {
        val home = homeScreen()
        assertTrue(home.contains("TrashSearchScopePolicy.scopeFor("))
        assertTrue(home.contains("TrashSearchScopePolicy.scoped("))
        // The scoping happens INSIDE the search branch — globalSearchResults must
        // no longer flow straight into activePageList unscoped while on Trash.
        assertFalse(
            "search results must pass through the policy before rendering",
            Regex("""globalSearchResults \?: emptyList\(\)\s*\n\s*\} else if""").containsMatchIn(home)
        )
    }

    @Test
    fun `the policy file exists and covers the tab constants`() {
        val policy = read("app/src/main/kotlin/com/authorss81/noteflow/services/TrashSearchScopePolicy.kt")
        listOf("TAB_PAGES = 0", "TAB_RECENT = 1", "TAB_TAG_VAULT = 2", "TAB_TRASH = 3")
            .forEach { assertTrue(policy.contains(it)) }
        assertTrue(policy.contains("TRASH_INTERSECT"))
    }

    @Test
    fun `trash cards still render from the real trashed flow`() {
        // The non-query trash branch must keep reading trashedPages (untouched).
        assertTrue(Regex("""3 -> trashedPages""").containsMatchIn(homeScreen()))
    }

    // ---------------- fix #2: sort control ----------------

    @Test
    fun `settings persist the page sort mode`() {
        val settings = settingsManager()
        assertTrue(settings.contains("\"page_sort_mode\""))
        assertTrue(settings.contains("PageSortPolicy.sanitizePersistenceKey"))
    }

    @Test
    fun `home applies the sort policy outside search mode only`() {
        val home = homeScreen()
        assertTrue(home.contains("PageSortPolicy.sorted("))
        // Search relevance ordering is preserved (no client-side resort there).
        assertTrue(Regex("""if \(searchQuery\.isNotBlank\(\)\) \{\s*\n\s*rawPageList""").containsMatchIn(home))
        // Sort control lives next to the view-mode chips with a persisted write-back.
        assertTrue(home.contains("Icons.AutoMirrored.Outlined.Sort"))
        assertTrue(home.contains("viewModel.settings.pageSortModeKey = mode.persistenceKey"))
    }

    // ---------------- fix #3: Move/Duplicate UI ----------------

    @Test
    fun `both card menus expose Move-to-Section and Duplicate`() {
        for ((name, src) in listOf("HomeScreen" to homeScreen(), "GalleryView" to galleryView())) {
            assertTrue("$name menu", src.contains("\"Move to Section…\""))
            assertTrue("$name menu", src.contains("\"Duplicate\""))
        }
        // HomeScreen hosts the shared picker dialog wired to the VM move verb.
        // Review-fix (finding 2): the picker partitions targets first, so only
        // active-notebook pages reach bulkMovePages.
        assertTrue(homeScreen().contains("SectionPickerDialog("))
        assertTrue(homeScreen().contains("MoveSectionScopePolicy.partition("))
        assertTrue(homeScreen().contains("bulkMovePages(movable, sectionId)"))
        assertTrue(homeScreen().contains("viewModel.duplicatePage(page.id)"))
    }

    @Test
    fun `repository duplicatePage re-encrypts every copied field under new ids`() {
        val repo = repository()
        assertTrue(repo.contains("suspend fun duplicatePage(pageId: String)"))
        assertTrue("one transaction", Regex("""duplicatePage[\s\S]*db\.withTransaction""").containsMatchIn(repo))
        // Fresh page + stroke ids; fields bound to the NEW record AAD.
        assertTrue(repo.contains("val storedTitle = EncryptionService.encryptField(copyTitle.toByteArray(), dek, \"pages\", newPageId, \"title\")"))
        assertTrue(repo.contains("\"strokes\", newStrokeId, \"textContent\""))
        assertTrue(repo.contains("\"strokes\", newStrokeId, \"pointsJson\""))
        // Copy title derives from the policy suffix helper.
        assertTrue(repo.contains("DuplicatePagePolicy.duplicateTitle(rawTitle)"))
        // Document-backed pages get their own bytes (shared paths break on delete).
        assertTrue(repo.contains("srcFile.copyTo(dest"))
        // Copied strokes never dangle into non-copied layers.
        assertTrue(repo.contains("layerId = null"))
    }

    // ---------------- fix #4: multi-select ----------------

    @Test
    fun `gallery cards long-press into selection mode`() {
        val gallery = galleryView()
        assertTrue(gallery.contains(".combinedClickable("))
        assertTrue(gallery.contains("selectionActive"))
        assertTrue(gallery.contains("onEnterSelection"))
    }

    @Test
    fun `list cards support long-press selection too`() {
        val home = homeScreen()
        assertTrue(home.contains("onLongPress: (() -> Unit)? = null"))
        assertTrue(Regex("""onLongPress = \{\s*\n\s*if \(!selectionActive\) multiSelectedIds = setOf\(page\.id\)""").containsMatchIn(home))
    }

    @Test
    fun `the bulk bar renders context verbs and a confirm-gated permanent delete`() {
        val home = homeScreen()
        assertTrue(home.contains("DuplicatePagePolicy.bulkVerbs("))
        assertTrue(home.contains("deleteConfirmType = \"bulk_page_perm\""))
        assertTrue(home.contains("\"Permanently Delete \${multiSelectedIds.size} Notes?\""))
        assertTrue(home.contains("viewModel.bulkRestorePages(multiSelectedIds)"))
        // Selection resets when leaving a tab so ids never cross verb contexts.
        assertTrue(Regex("""LaunchedEffect\(selectedTab\) \{\s*\n\s*multiSelectedIds = emptySet\(\)""").containsMatchIn(home))
    }

    @Test
    fun `empty trash stays untouched by the bulk bar`() {
        val home = homeScreen()
        assertTrue(home.contains("deleteConfirmType = \"empty_trash\""))
        assertTrue(home.contains("\"Empty Trash\""))
    }

    // ---------------- fix #5: palm rejection persistence ----------------

    @Test
    fun `palm rejection persists through SettingsManager`() {
        val settings = settingsManager()
        assertTrue(settings.contains("\"palm_rejection_enabled\""))
        assertTrue(settings.contains("var palmRejectionEnabled: Boolean"))
    }

    @Test
    fun `editor seeds palm rejection from settings and persists toggles from both surfaces`() {
        val editor = editorScreen()
        assertTrue(
            "seed from prefs, not a hardcoded true",
            editor.contains("mutableStateOf(viewModel.settings.palmRejectionEnabled)")
        )
        // Phase 208 review-fix (finding 5): count WRITE (assignment) sites only —
        // the seed line above must never satisfy this on its own. BOTH toggle
        // surfaces (⋮ menu + Canvas & Paper Options sheet) must persist the
        // toggle, so there must be at least two distinct assignment sites.
        val writeSites = Regex("""viewModel\.settings\.palmRejectionEnabled\s*=""")
            .findAll(editor)
            .count()
        assertTrue(
            "both surfaces must persist the toggle (≥2 write sites), found $writeSites",
            writeSites >= 2
        )
        assertTrue(editor.contains("onPalmRejectionToggle"))
        assertTrue(editor.contains("\"Palm Rejection\""))
    }

    // ---------------- review fixes (2026-08-25) ----------------

    @Test
    fun `review-fix finding 1 - the tag-match branch is scoped like search results`() {
        // The tag filter bypassed the tab branches: a tag filter left active on
        // the Trash tab rendered LIVE notes under the Restore / Delete
        // Permanently menu. The derived-list scope policy must guard it too.
        assertTrue(homeScreen().contains("TrashSearchScopePolicy.scopeForDerivedList(selectedTab)"))
        val policy =
            read("app/src/main/kotlin/com/authorss81/noteflow/services/TrashSearchScopePolicy.kt")
        assertTrue(policy.contains("fun scopeForDerivedList("))
        // scopeFor must delegate to the same rule so query search cannot drift.
        assertTrue(Regex("""scopeFor\(selectedTab[^}]*scopeForDerivedList\(selectedTab\)""").containsMatchIn(policy))
    }

    @Test
    fun `review-fix finding 2 - section picker partitions move targets by notebook ownership`() {
        assertTrue(homeScreen().contains("MoveSectionScopePolicy.partition("))
        val policy =
            read("app/src/main/kotlin/com/authorss81/noteflow/services/MoveSectionScopePolicy.kt")
        assertTrue(policy.contains("activeNotebookSectionIds"))
    }

    @Test
    fun `review-fix finding 3 - bulk tag append reads current tags inside the write path`() {
        assertFalse(
            "the stale composition-time snapshot map must be gone from HomeScreen",
            homeScreen().contains("allActivePages.associate { it.id to it.tags }")
        )
        val repo = repository()
        assertTrue(repo.contains("suspend fun appendTagsToPage(pageId: String, additions: List<String>)"))
        assertTrue(repo.contains("TagAppendPolicy.merge(source.tags, additions)"))
    }

    @Test
    fun `review-fix finding 4 - a failed duplicate transaction rolls back the copied file`() {
        val repo = repository()
        assertTrue(repo.contains("var duplicatedCopyFile: File? = null"))
        assertTrue(
            "the catch block must delete the copied file and rethrow",
            Regex("""catch \(e: Throwable\) \{[\s\S]*?duplicatedCopyFile\?\.let \{ runCatching \{ it\.delete\(\) \} \}[\s\S]*?throw e""")
                .containsMatchIn(repo)
        )
    }
}
