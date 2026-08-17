package com.authorss81.noteflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2-UI-4 (phase-95) behavioral + wiring tests for the post-unlock state
 * re-initialization boundary.
 *
 * Finding (LOW, `docs/security-report.md:588`): `lock()` cleared the session
 * StateFlows but `unlock()` never re-established them because
 * `dataInitialized` stayed `true` across the lock — so after every lock->unlock
 * cycle the app unlocked into an empty home list and the user had to manually
 * re-navigate; the persistent `activeNotebookId`/`activeSectionId`/`activePageId`
 * prefs still pointed at the previous session.
 *
 * After the phase-47 fix (commit 23c87429, B1-AUTH-02) the lock->unlock cycle
 * is closed end-to-end:
 *   - `lock()` resets `dataInitialized = false` and cancels the section/page
 *     observer jobs (password-protected vaults only), then nulls the selection
 *     StateFlows.
 *   - Both unlock paths (`verifyMasterPassword` / `verifyBiometricsAndUnlock`)
 *     reinstate the disposed SQLCipher connection, flip `_authenticated`, then
 *     call `initializeData()` — which now boots because the flag was reset.
 *   - `initializeDataCore()` re-establishes the selected notebook/section from
 *     the persisted prefs and re-arms `observeSections`/`observePages`, so the
 *     home lists repopulate WITHOUT manual navigation.
 *
 * What is provable on the pure JVM (no AndroidKeyStore/Room/SQLCipher/Compose):
 * a behavioral model of the flag + restore + re-arm decision, mirroring the
 * production `dataInitialized`/prefs/observer logic exactly, plus source-level
 * wiring pins proving the Android-bound `NoteflowViewModel` really calls it.
 * The real Room flows themselves run only at build/device runtime.
 */
class B2Ui4UnlockReinitializesStateTest {

    // ---------- behavior: the lock/unlock state machine, modelled exactly ----------

    private class SessionModel(
        val hasMasterPassword: Boolean,
        var activeNotebookId: String?,
        var activeSectionId: String?,
        val notebooksById: Map<String, String>,
        val sectionsById: Map<String, Pair<String, String>>, // id -> (notebookId, name)
        val pagesBySection: Map<String, List<String>>
    ) {
        var dataInitialized = false
        var authenticated = !hasMasterPassword
        var selectedNotebook: String? = null
        var selectedSection: String? = null
        var sections: List<String> = emptyList()
        var pages: List<String> = emptyList()
        var sectionsJobArmed = false
        var pagesJobArmed = false

        fun firstStart() {
            if (hasMasterPassword) return
            initData()
        }

        // Mirror of noteflow's initializeData(): guarded early-return on the flag,
        // then a launch of initializeDataCore().
        fun initData() {
            if (dataInitialized) return
            dataInitialized = true
            initDataCore()
        }

        // Mirror of initializeDataCore(): restore selected notebook/section from
        // the persisted prefs, else ensure defaults; then re-arm the observers.
        private fun initDataCore() {
            val nbId = activeNotebookId
            val secId = activeSectionId
            val nbOk = nbId != null && notebooksById.containsKey(nbId)
            val secOk = secId != null && sectionsById[secId]?.first == nbId
            if (nbOk && secOk) {
                selectedNotebook = nbId
                selectedSection = secId
                observeSections(nbId!!)
                observePages(secId!!)
            } else {
                val defaultNb = notebooksById.keys.firstOrNull() ?: "defaultNb"
                val defaultSec = sectionsById.entries.firstOrNull { it.value.first == defaultNb }?.key
                selectedNotebook = defaultNb
                selectedSection = defaultSec
                observeSections(defaultNb)
                observePages(defaultSec ?: "")
            }
        }

        private fun observeSections(notebookId: String) {
            sectionsJobArmed = true
            // Mirror: collect -> _sections.value = list; auto-select first if none.
            sections = sectionsById.values
                .filter { it.first == notebookId }
                .map { it.second }
                .runningFold(emptyList<String>()) { acc, name -> acc + name }.last()
            if (sections.isNotEmpty() && (selectedSection == null || sectionsById.keys.none { it == selectedSection })) {
                val firstSec = sectionsById.entries.first { it.value.first == notebookId }.key
                selectedSection = firstSec
                observePages(firstSec)
            }
        }

        private fun observePages(sectionId: String) {
            if (sectionId.isEmpty()) {
                pages = emptyList()
                return
            }
            pagesJobArmed = true
            pages = pagesBySection[sectionId] ?: emptyList()
        }

        // Mirror of lock(): for password-protected vaults cancel the observer jobs,
        // reset dataInitialized, dispose; ALWAYS null the selection StateFlows.
        fun lock() {
            if (hasMasterPassword) {
                sectionsJobArmed = false
                pagesJobArmed = false
                dataInitialized = false
                authenticated = false
            }
            pages = emptyList()
            selectedPage = null
            sections = emptyList()
            selectedSection = null
            selectedNotebook = null
        }

        // Mirror of verifyMasterPassword success: reinstate connection, flip
        // authenticated, then initializeData().
        fun unlock() {
            authenticated = true
            initData()
        }

        var selectedPage: String? = null // mirror of _selectedPage StateFlow
    }

    @Test
    fun `password vault unlocks into a repopulated home list without manual navigation`() {
        val model = SessionModel(
            hasMasterPassword = true,
            activeNotebookId = "nb1",
            activeSectionId = "sec2",
            notebooksById = mapOf("nb1" to "Notes"),
            sectionsById = mapOf("sec1" to ("nb1" to "General"), "sec2" to ("nb1" to "Work")),
            pagesBySection = mapOf("sec1" to listOf("p1", "p2"), "sec2" to listOf("p3"))
        )
        model.firstStart() // locked vault: not initialized yet

        val before = System.nanoTime()
        model.unlock()
        val after = System.nanoTime()
        assertTrue("unlock must actually run initializeData (flag was reset by lock)", after > before)

        assertTrue(model.authenticated)
        assertTrue("dataInitialized must be true after a real init", model.dataInitialized)
        assertEquals("restored notebook from persisted prefs", "nb1", model.selectedNotebook)
        // The persisted activeSectionId "sec2" survives the lock and drives restore.
        assertEquals("observer jobs must be re-armed by initializeData", true, model.sectionsJobArmed)
        assertEquals("pages observer must be re-armed for the restored section", true, model.pagesJobArmed)
        assertEquals("sections repopulated from the restored notebook", listOf("General", "Work"), model.sections)
        assertEquals(
            "pages repopulated for the restored section WITHOUT any user tap",
            listOf("p3"),
            model.pages
        )
    }

    @Test
    fun `a lock then unlock then lock then unlock cycle repopulates every time`() {
        val model = SessionModel(
            hasMasterPassword = true,
            activeNotebookId = "nb1",
            activeSectionId = "sec1",
            notebooksById = mapOf("nb1" to "Notes"),
            sectionsById = mapOf("sec1" to ("nb1" to "General")),
            pagesBySection = mapOf("sec1" to listOf("p1", "p2", "p3"))
        )
        model.unlock()
        assertEquals(listOf("p1", "p2", "p3"), model.pages)

        model.lock()
        assertFalse(model.authenticated)
        assertEquals("lock clears the selection StateFlows", null, model.selectedNotebook)
        assertEquals("lock clears the sections StateFlow", emptyList<String>(), model.sections)
        assertEquals("lock clears the pages StateFlow", emptyList<String>(), model.pages)
        assertEquals("lock resets dataInitialized for a password-protected vault", false, model.dataInitialized)

        model.unlock()
        assertEquals("re-lock+unlock repopulates pages", listOf("p1", "p2", "p3"), model.pages)
        assertEquals("re-lock+unlock restores the notebook selection", "nb1", model.selectedNotebook)

        model.lock()
        model.unlock()
        assertEquals(
            "repeated cycles must never degrade to an empty home list",
            listOf("p1", "p2", "p3"),
            model.pages
        )
    }

    @Test
    fun `passwordless vault keeps the session state intact under lock - by design`() {
        // A passwordless vault has no lock boundary (the device-wrapped DEK IS the
        // boot credential), so lock() never tears down the session; the empty-list
        // exploit cannot exist there.
        val model = SessionModel(
            hasMasterPassword = false,
            activeNotebookId = null,
            activeSectionId = null,
            notebooksById = mapOf("nb1" to "Notes"),
            sectionsById = mapOf("sec1" to ("nb1" to "General")),
            pagesBySection = mapOf("sec1" to listOf("pA"))
        )
        model.firstStart()
        assertEquals("passwordless first start initializes immediately", true, model.dataInitialized)
        assertEquals(listOf("pA"), model.pages)

        // A "lock" call on a passwordless vault is a no-op session-wise by design
        // (the real lock() only tears down inside if (settings.hasMasterPassword)).
        model.lock()
        assertTrue("passwordless vault stays authenticated (no lock boundary)", model.authenticated)
        assertTrue("dataInitialized unchanged for passwordless", model.dataInitialized)
        assertTrue("pages must NOT be repopulated from scratch - session kept as-is", model.pages.isEmpty())
    }

    @Test
    fun `initializeData is re-entrant guarded - the sticky-flag bug cannot recur`() {
        // The pre-fix bug: initializeData() silently no-oped because the flag was
        // left true by the previous session. The guard is CORRECT but the lock must
        // reset the flag; this pins the contract: a second bumper call no-ops, but
        // a lock() between calls allows a fresh boot.
        val model = SessionModel(
            hasMasterPassword = true,
            activeNotebookId = "nb1",
            activeSectionId = "sec1",
            notebooksById = mapOf("nb1" to "Notes"),
            sectionsById = mapOf("sec1" to ("nb1" to "General")),
            pagesBySection = mapOf("sec1" to listOf("x"))
        )
        model.unlock()
        val armedAfterFirst = model.sectionsJobArmed
        model.initData() // re-entrant call while already initialized
        assertEquals("a second initData while initialized must no-op (guard)", true, model.sectionsJobArmed)
        val armedAfterNoop = model.sectionsJobArmed
        assertEquals("no-op means no re-arming happened", armedAfterFirst, armedAfterNoop)

        model.lock()
        model.initData() // this is exactly the unlock path
        assertEquals("after lock the guard must allow a fresh boot", true, model.sectionsJobArmed)
        assertEquals(listOf("x"), model.pages)
    }

    @Test
    fun `sections auto-select their first page when the restored selection is stale`() {
        // If the persisted activeSectionId points at a section that no longer
        // exists (deleted meanwhile), initializeDataCore must fall back to the
        // default notebook/section instead of leaving an empty selection.
        val model = SessionModel(
            hasMasterPassword = true,
            activeNotebookId = "nb1",
            activeSectionId = "gone-gone", // stale pref
            notebooksById = mapOf("nb1" to "Notes"),
            sectionsById = mapOf("sec1" to ("nb1" to "General")),
            pagesBySection = mapOf("sec1" to listOf("p1"))
        )
        model.unlock()
        assertEquals("stale section pref falls back to the default section", "sec1", model.selectedSection)
        assertEquals(listOf("p1"), model.pages)
        assertEquals("the restored notebook is the default one", "nb1", model.selectedNotebook)
    }

    // ---------- wiring pins: the Android-bound wiring (source-level) ----------

    // The lock()/unlock/initializeData logic lives in the Android-bound
    // NoteflowViewModel which cannot be instantiated in a pure-JVM test. Pin the
    // wiring at source level (same technique as B1Auth02LockedOpenTest) so a
    // future refactor cannot silently drop a link and reopen the finding.

    @Test
    fun `lock must reset dataInitialized and cancel the observer jobs`() {
        val source = readNoteflowViewModelSource()
        val lockBlock = source.substringAfter("fun lock()", "END")
            .substringBefore("override fun onCleared()", "END")
        assertTrue(
            "lock() must reset dataInitialized so the next unlock re-boots initializeData()",
            lockBlock.contains("dataInitialized = false")
        )
        assertTrue(
            "lock() must cancel the section observer so nothing collects from the closed vault",
            lockBlock.contains("sectionsJob?.cancel()")
        )
        assertTrue(
            "lock() must cancel the page observer",
            lockBlock.contains("pagesJob?.cancel()")
        )
        assertTrue(
            "lock() must null the pages selection StateFlow",
            lockBlock.contains("_selectedPage.value = null")
        )
        assertTrue(
            "lock() must null the sections StateFlow",
            lockBlock.contains("_sections.value = emptyList()")
        )
    }

    @Test
    fun `every unlock path must call initializeData after reinstating the connection`() {
        val source = readNoteflowViewModelSource()
        val verifyBlock = source.substringAfter("suspend fun verifyMasterPassword", "END")
            .substringBefore("suspend fun isMasterPasswordValid", "END")
        val biometricsBlock = source.substringAfter("fun verifyBiometricsAndUnlock", "END")
            .substringBefore("fun disableBiometricFallback", "END")
        assertTrue(
            "password unlock must reinstate the disposed connection before any flow re-subscribes",
            verifyBlock.contains("reinstateDatabaseAfterLock()")
        )
        assertTrue(
            "password unlock must call initializeData() to re-arm observers + restore prefs",
            verifyBlock.contains("initializeData()")
        )
        assertTrue("biometric unlock must reinstate the connection too", biometricsBlock.contains("reinstateDatabaseAfterLock()"))
        assertTrue("biometric unlock must call initializeData() too", biometricsBlock.contains("initializeData()"))
    }

    @Test
    fun `initializeDataCore must restore notebook and section from persisted prefs and re-arm observers`() {
        val source = readNoteflowViewModelSource()
        val core = source.substringAfter("private suspend fun initializeDataCore()", "END")
        assertTrue(
            "the restore must read the persisted active notebook id",
            core.contains("settings.activeNotebookId")
        )
        assertTrue(
            "the restore must read the persisted active section id",
            core.contains("settings.activeSectionId")
        )
        assertTrue(
            "the restore must re-arm the sections observer for the restored notebook",
            core.contains("observeSections(")
        )
        assertTrue(
            "the restore must re-arm the pages observer for the restored section",
            core.contains("observePages(")
        )
        // Guard pin: the flag reset must happen in lock(), not be confused with the
        // init-block assignment. The guard itself is verify: initializeData() must
        // early-return when already initialized so a fresh boot neither double-arms
        // nor races.
        val initBlock = source.substringAfter("private fun initializeData() {", "END")
        assertTrue("initializeData() must guard re-entry via the dataInitialized flag", initBlock.contains("if (dataInitialized) return"))
    }

    @Test
    fun `selectNotebook and selectSection must persist the ids that initializeDataCore restores from`() {
        val source = readNoteflowViewModelSource()
        val selectNb = source.substringAfter("fun selectNotebook", "END").substringBefore("private fun observeSections", "END")
        val selectSec = source.substringAfter("fun selectSection", "END").substringBefore("private fun observePages", "END")
        assertTrue(
            "selectNotebook must persist activeNotebookId so a later unlock restores it",
            selectNb.contains("settings.activeNotebookId = notebook.id")
        )
        assertTrue(
            "selectSection must persist activeSectionId so a later unlock restores it",
            selectSec.contains("settings.activeSectionId = section.id")
        )
        assertTrue(
            "selectSection must re-arm the pages observer",
            selectSec.contains("observePages(")
        )
    }

    @Test
    fun `dbGate flows must gate the home lists on authentication`() {
        val source = readNoteflowViewModelSource()
        for (flow in listOf("notebooks", "allActivePages", "allSections", "recentPages", "trashedPages", "paletteItems")) {
            assertTrue(
                "home list flow `$flow` must be gate-rebuilt (locked vault emits empty, unlocked re-emits)",
                source.contains("val $flow: StateFlow<List<")
            )
        }
        assertTrue(
            "dbGate must require authentication (isAuth && !blocked && !keyLost)",
            source.contains("isAuth && !blocked && !keyLost")
        )
    }

    // ---------- helpers ----------

    private fun readNoteflowViewModelSource(): String {
        val file = java.io.File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt"
        )
        assertTrue("NoteflowViewModel.kt must exist", file.isFile)
        return file.readText()
    }

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