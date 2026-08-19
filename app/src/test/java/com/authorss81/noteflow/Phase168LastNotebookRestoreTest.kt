package com.authorss81.noteflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 168 (2026-08-19): "the app must always open the last-used notebook".
 *
 * User feedback: on cold start InkFlow restored a first/random notebook instead
 * of the one the user was last in, even though `SettingsManager.lastNotebookId`
 * (`last_notebook_id`) existed. Root causes verified in-tree:
 *   - `lastNotebookId` was NEVER WRITTEN — it was only declared
 *     (`SettingsManager.kt:94-96`); no call site ever set it (grep-verified).
 *   - `initializeDataCore()` (`NoteflowViewModel.kt`) restored from
 *     `activeNotebookId` AND required a valid `activeSectionId` to belong to it;
 *     a stale/deleted section pref dropped BOTH restores and fell through to
 *     `ensureDefaultNotebookAndSection()` (the "default_nb" notebook).
 *
 * Fix:
 *   - `selectNotebook()` now writes `settings.lastNotebookId` on EVERY selection
 *     change (the notebook-switcher's single chokepoint); `onCleared()` persists
 *     the current selection on exit.
 *   - `initializeDataCore()` reads `lastNotebookId ?: activeNotebookId`; the
 *     notebook restore is decoupled from the section restore (a stale section
 *     pref now yields the notebook's first section via `observeSections` instead
 *     of discarding the notebook), and a deleted last notebook falls back to the
 *     FIRST existing notebook (persisted back into `lastNotebookId`), or the
 *     default notebook+section on a brand-new vault.
 *
 * Like `B2Ui4UnlockReinitializesStateTest`, the pure-JVM model mirrors the
 * production decision exactly and the Android-bound wiring is pinned at source
 * level (Room/SQLCipher/Compose cannot run here).
 */
class Phase168LastNotebookRestoreTest {

    // ---------- behavior: mirror of the restore decision ----------

    private class RestoreModel(
        var lastNotebookId: String?,
        var activeNotebookId: String?,
        var activeSectionId: String?,
        val orderedNotebookIds: List<String>,
        val orderedSectionIds: List<String>,
        val sectionNotebookOf: Map<String, String>, // sectionId -> notebookId
        val pagesBySection: Map<String, List<String>>
    ) {
        var selectedNotebook: String? = null
        var selectedSection: String? = null
        var sectionsJobArmed = false
        var pagesJobArmed = false
        var pages: List<String> = emptyList()

        // Mirror of the phase-168 initializeDataCore restore block.
        fun coldStart() {
            val lastNbId = lastNotebookId ?: activeNotebookId
            val lastSecId = activeSectionId
            var restoredNb: String? = null
            var restoredSec: String? = null
            if (!lastNbId.isNullOrEmpty() && lastNbId in orderedNotebookIds) {
                restoredNb = lastNbId
            }
            if (restoredNb != null && !lastSecId.isNullOrEmpty() &&
                sectionNotebookOf[lastSecId] == restoredNb
            ) {
                restoredSec = lastSecId
            }
            if (restoredNb != null) {
                lastNotebookId = restoredNb
                selectedNotebook = restoredNb
                if (restoredSec != null) {
                    selectedSection = restoredSec
                    observeSections(restoredNb)
                    observePages(restoredSec)
                } else {
                    observeSections(restoredNb)
                }
            } else {
                if (orderedNotebookIds.isNotEmpty()) {
                    val fallbackNb = orderedNotebookIds.first()
                    lastNotebookId = fallbackNb
                    selectedNotebook = fallbackNb
                    observeSections(fallbackNb)
                } else {
                    lastNotebookId = "default_nb"
                    selectedNotebook = "default_nb"
                    selectedSection = "default_sec"
                    observeSections("default_nb")
                    observePages("default_sec")
                }
            }
        }

        private fun observeSections(notebookId: String) {
            sectionsJobArmed = true
            val secs = orderedSectionIds.filter { sectionNotebookOf[it] == notebookId }
            if (secs.isNotEmpty() && (selectedSection == null || selectedSection !in secs)) {
                selectedSection = secs.first()
                observePages(selectedSection)
            } else if (secs.isEmpty()) {
                selectedSection = null
                pages = emptyList()
            }
        }

        private fun observePages(sectionId: String?) {
            if (sectionId == null) {
                pages = emptyList()
                return
            }
            pagesJobArmed = true
            pages = pagesBySection[sectionId] ?: emptyList()
        }
    }

    @Test
    fun `cold start opens the last-used notebook with its exact section`() {
        val model = RestoreModel(
            lastNotebookId = "nb2",
            activeNotebookId = "nb1",
            activeSectionId = "sec2b",
            orderedNotebookIds = listOf("nb1", "nb2"),
            orderedSectionIds = listOf("sec1a", "sec2a", "sec2b"),
            sectionNotebookOf = mapOf(
                "sec1a" to "nb1",
                "sec2a" to "nb2",
                "sec2b" to "nb2"
            ),
            pagesBySection = mapOf("sec2b" to listOf("p21", "p22"))
        )
        model.coldStart()
        assertEquals("the LAST-used notebook (not the first) must be restored", "nb2", model.selectedNotebook)
        assertEquals("the persisted section inside that notebook is restored too", "sec2b", model.selectedSection)
        assertEquals("the resolved notebook id is persisted back to lastNotebookId", "nb2", model.lastNotebookId)
        assertTrue("sections observer re-armed for the restored notebook", model.sectionsJobArmed)
        assertTrue("pages observer re-armed for the restored section", model.pagesJobArmed)
        assertEquals(listOf("p21", "p22"), model.pages)
    }

    @Test
    fun `cold start keeps the last notebook even when the section pref is stale`() {
        // Pre-fix: a stale activeSectionId dropped BOTH restores and fell back to
        // the first/default notebook. Phase 168 decouples the two restores.
        val model = RestoreModel(
            lastNotebookId = "nb2",
            activeNotebookId = "nb1",
            activeSectionId = "deleted-section", // stale pref
            orderedNotebookIds = listOf("nb1", "nb2"),
            orderedSectionIds = listOf("sec1a", "sec2a"),
            sectionNotebookOf = mapOf("sec1a" to "nb1", "sec2a" to "nb2"),
            pagesBySection = mapOf("sec2a" to listOf("p2a"))
        )
        model.coldStart()
        assertEquals("stale section must NOT discard the last-used notebook", "nb2", model.selectedNotebook)
        assertEquals("stale section falls back to the notebook's first section", "sec2a", model.selectedSection)
        assertEquals(listOf("p2a"), model.pages)
        assertEquals("lastNotebookId stays resolved to the restored notebook", "nb2", model.lastNotebookId)
    }

    @Test
    fun `legacy sessions whose prefs only ever wrote activeNotebookId still restore`() {
        val model = RestoreModel(
            lastNotebookId = null, // never written (pre-phase-168)
            activeNotebookId = "nb3",
            activeSectionId = "sec3a",
            orderedNotebookIds = listOf("nb1", "nb3"),
            orderedSectionIds = listOf("sec1a", "sec3a"),
            sectionNotebookOf = mapOf("sec1a" to "nb1", "sec3a" to "nb3"),
            pagesBySection = mapOf("sec3a" to listOf("p3"))
        )
        model.coldStart()
        assertEquals("legacy activeNotebookId is the fallback restore source", "nb3", model.selectedNotebook)
        assertEquals("the legacy pref now upgrades lastNotebookId", "nb3", model.lastNotebookId)
        assertEquals(listOf("p3"), model.pages)
    }

    @Test
    fun `deleted last notebook falls back to the FIRST existing notebook and persists it`() {
        val model = RestoreModel(
            lastNotebookId = "deleted-nb",
            activeNotebookId = "deleted-nb",
            activeSectionId = "sec1a",
            orderedNotebookIds = listOf("nb1", "nb2"),
            orderedSectionIds = listOf("sec1a", "sec2a"),
            sectionNotebookOf = mapOf("sec1a" to "nb1", "sec2a" to "nb2"),
            pagesBySection = mapOf("sec1a" to listOf("p1"))
        )
        model.coldStart()
        assertEquals("deleted last notebook falls to the first existing", "nb1", model.selectedNotebook)
        assertEquals("fallback id is persisted so the next cold start opens straight into it", "nb1", model.lastNotebookId)
        assertTrue(model.sectionsJobArmed)
    }

    @Test
    fun `a brand-new empty vault opens the default notebook and section`() {
        // Mirror of ensureDefaultNotebookAndSection: a brand-new vault has neither
        // a notebook nor a section — the restore creates default_nb + default_sec,
        // exactly the pair the empty-branch of initializeDataCore then observes.
        val model = RestoreModel(
            lastNotebookId = null,
            activeNotebookId = null,
            activeSectionId = null,
            orderedNotebookIds = emptyList(),
            orderedSectionIds = listOf("default_sec"),
            sectionNotebookOf = mapOf("default_sec" to "default_nb"),
            pagesBySection = emptyMap()
        )
        model.coldStart()
        assertEquals("default_nb", model.selectedNotebook)
        assertEquals("default_sec", model.selectedSection)
        assertEquals("lastNotebookId records the boot notebook", "default_nb", model.lastNotebookId)
        assertTrue(model.pagesJobArmed)
    }

    // ---------- wiring pins: the Android-bound wiring (source-level) ----------

    private val vmSource by lazy {
        java.io.File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt"
        ).readText()
    }
    private val settingsSource by lazy {
        java.io.File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/services/SettingsManager.kt"
        ).readText()
    }

    @Test
    fun `SettingsManager still exposes the last_notebook_id pref`() {
        assertTrue(
            "lastNotebookId accessor must remain in SettingsManager",
            settingsSource.contains("var lastNotebookId: String?")
        )
        assertTrue(
            "the pref key must be last_notebook_id",
            settingsSource.contains("last_notebook_id")
        )
    }

    @Test
    fun `selectNotebook persists lastNotebookId on every selection change`() {
        val selectNb = vmSource.substringAfter("fun selectNotebook", "END")
            .substringBefore("private fun observeSections", "END")
        assertTrue(
            "the notebook-switcher chokepoint must write lastNotebookId",
            selectNb.contains("settings.lastNotebookId = notebook.id")
        )
        assertTrue(
            "the legacy activeNotebookId write must be kept (B2-UI-4 pin)",
            selectNb.contains("settings.activeNotebookId = notebook.id")
        )
    }

    @Test
    fun `cold start restore reads lastNotebookId first and re-arms observers`() {
        val core = vmSource.substringAfter("private suspend fun initializeDataCore()", "END")
        assertTrue(
            "the restore must read lastNotebookId as the primary source",
            core.contains("settings.lastNotebookId")
        )
        assertTrue(
            "activeNotebookId must remain as the legacy fallback source",
            core.contains("settings.activeNotebookId")
        )
        assertTrue(
            "the restore must write back the resolved notebook id (deleted-last fallback update)",
            core.contains("settings.lastNotebookId = restoredNb.id")
        )
        assertTrue(
            "the restore must re-arm the sections observer",
            core.contains("observeSections(")
        )
        assertTrue(
            "the restore must re-arm the pages observer",
            core.contains("observePages(")
        )
    }

    @Test
    fun `onCleared persists the current selection on exit`() {
        val cleared = vmSource.substringAfter("override fun onCleared() {", "END")
            .substringBefore("NoteflowDatabase.dispose()", "END")
        assertTrue(
            "app exit must persist the current selected notebook",
            cleared.contains("settings.lastNotebookId = it.id")
        )
    }

    // ---------- helpers ----------

    private fun repoRoot(): java.io.File {
        val cwd = java.io.File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (java.io.File(dir, "gradle/libs.versions.toml").isFile &&
                java.io.File(dir, "app").isDirectory
            ) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}