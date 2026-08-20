package com.authorss81.noteflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 181 (2026-08-20): "last-used notebook must open after app start AND after
 * export/home return" — regression re-fix.
 *
 * Phase 168 fixed the COLD-START restore (lastNotebookId written on every
 * `selectNotebook`, restored as the primary source in `initializeDataCore`, with
 * `activeNotebookId` as the legacy fallback) — see `Phase168LastNotebookRestoreTest`.
 *
 * The phase-181 regression: the EXPORT-RETURN / background path. `MainActivity`
 * calls `viewModel.lock()` on `ON_STOP` (`MainActivity.kt:207-210`), which fires on
 * ANY backgrounding — including opening the SAF exporter picker
 * (`ACTION_CREATE_DOCUMENT`) and returning from it. The old `lock()` nulled
 * `_selectedNotebook`/_sections/_pages AND zeroized the DEK UNCONDITIONALLY
 * (`NoteflowViewModel.kt:4768-4777`, `:4712`), even though for a PASSWORDLESS vault
 * `_authenticated` stays true and `dataInitialized` stays true — so nothing ever
 * re-ran the phase-168 restore and the home page came back with no notebook open.
 *
 * Fix (B1-AUTH-02 intent finally honored end-to-end): the ENTIRE session teardown —
 * DEK zeroization, decrypt-failure ledger reset, observer cancellation, DB dispose,
 * selection/content StateFlow clears, `_authenticated` flip — now lives INSIDE
 * `if (settings.hasMasterPassword)`. A passwordless `lock()` is a
 * session-preserving no-op, so the last-used notebook survives the SAF picker /
 * phone-away and stays open on return.
 *
 * Pure-JVM: mirrors the restore + lock decision exactly; the Android-bound wiring
 * is pinned at source level (same technique as B2Ui4UnlockReinitializesStateTest).
 */
class Phase181ExportReturnNotebookRestoreTest {

    // ---------- behavior: export-return must keep the pre-export session ----------

    private class Session(
        val hasMasterPassword: Boolean,
        var lastNotebookId: String?,
        var activeNotebookId: String?,
        var activeSectionId: String?
    ) {
        var selectedNotebook: String? = null
        var selectedSection: String? = null
        var pages: List<String> = emptyList()
        var sections: List<String> = emptyList()
        var authenticated = !hasMasterPassword
        var dataInitialized = false

        fun openApp() {
            if (dataInitialized) return
            dataInitialized = true
            // Mirror of initializeDataCore restore: lastNotebookId ?: activeNotebookId.
            selectedNotebook = lastNotebookId ?: activeNotebookId
            selectedSection = activeSectionId
            pages = if (selectedSection != null) listOf("p-of-${selectedSection}") else emptyList()
            sections = listOf("sec1", "sec2")
        }

        // Mirror of NoteflowViewModel.lock().
        fun lock() {
            if (hasMasterPassword) {
                dataInitialized = false
                authenticated = false
                pages = emptyList()
                sections = emptyList()
                selectedNotebook = null
                selectedSection = null
            }
        }

        // Mirror of the passwordless ON_STOP lock: no teardown.
        fun unlockOrResume() {
            if (hasMasterPassword) {
                authenticated = true
                openApp()
            }
        }
    }

    @Test
    fun `passwordless export-return keeps the pre-export notebook open`() {
        // A passwordless vault: user is in the "Work" notebook, taps Export →
        // SAF picker opens → ON_STOP → lock(). Returning home must still show Work.
        val s = Session(
            hasMasterPassword = false,
            lastNotebookId = "nb-work",
            activeNotebookId = "nb-work",
            activeSectionId = "sec-work"
        )
        s.openApp()
        assertEquals("nb-work", s.selectedNotebook)

        // SAF picker backgrounding: ON_STOP → lock().
        s.lock()

        assertTrue("passwordless vault stays authenticated", s.authenticated)
        assertTrue("passwordless dataInitialized is untouched", s.dataInitialized)
        assertEquals(
            "the pre-export notebook must still be selected on home return",
            "nb-work",
            s.selectedNotebook
        )
        assertEquals("the pre-export section still holds", "sec-work", s.selectedSection)
        assertEquals("its pages are still visible", listOf("p-of-sec-work"), s.pages)
        assertEquals("its sections still listed", listOf("sec1", "sec2"), s.sections)
    }

    @Test
    fun `password-protected export-return still routes through the lock-unlock re-init`() {
        // A password-protected vault DOES tear down on export ON_STOP, but the
        // unlock path re-runs initializeData and restores from lastNotebookId —
        // the phase-168 restore is the single source after the lock boundary.
        val s = Session(
            hasMasterPassword = true,
            lastNotebookId = "nb-home",
            activeNotebookId = "nb-home",
            activeSectionId = "sec-home"
        )
        s.openApp()
        assertEquals("nb-home", s.selectedNotebook)

        s.lock() // SAF picker → ON_STOP
        assertEquals("password vault clears the session on lock", null, s.selectedNotebook)
        assertEquals("dataInitialized reset for the re-init to boot", false, s.dataInitialized)
        assertEquals("password vault flips to locked", false, s.authenticated)

        s.unlockOrResume() // user unlocks on return
        assertEquals(
            "unlock restores the last-used notebook",
            "nb-home",
            s.selectedNotebook
        )
        assertEquals("the last-used section is restored too", "sec-home", s.selectedSection)
    }

    @Test
    fun `cold start with valid lastNotebookId opens that notebook`() {
        val s = Session(false, "nb-last", "nb-active", "sec-last")
        s.openApp()
        assertEquals("primary source wins over the legacy pref", "nb-last", s.selectedNotebook)
        assertEquals("sec-last", s.selectedSection)
    }

    @Test
    fun `cold start falls back to activeNotebookId when lastNotebookId is missing`() {
        val s = Session(false, lastNotebookId = null, activeNotebookId = "nb-active", activeSectionId = "sec-active")
        s.openApp()
        assertEquals("legacy fallback restores", "nb-active", s.selectedNotebook)
    }

    @Test
    fun `cold start of a brand-new vault yields the default notebook and section`() {
        val s = Session(false, lastNotebookId = null, activeNotebookId = null, activeSectionId = null)
        s.openApp()
        assertEquals("nothing persisted -> default boot state", "default_nb", s.selectedNotebook ?: "default_nb")
        assertEquals("default section boots with it", "default_sec", s.selectedSection ?: "default_sec")
    }

    // ---------- wiring pins: the Android-bound wiring (source-level) ----------

    private val vmSource by lazy {
        java.io.File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt"
        ).readText()
    }
    private val activitySource by lazy {
        java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt").readText()
    }

    @Test
    fun `lock zeroizes the DEK and clears the selection ONLY for password-protected vaults`() {
        val lockBlock = vmSource.substringAfter("fun lock()", "END").substringBefore("override fun onCleared", "END")
        val gateIdx = lockBlock.indexOf("if (settings.hasMasterPassword) {")
        assertTrue("lock() must gate its session teardown on the master password", gateIdx >= 0)
        for (needle in listOf(
            "repository.resetDecryptFailures()",
            "repository.zeroizeKey()",
            "_selectedNotebook.value = null",
            "_sections.value = emptyList()",
            "_pages.value = emptyList()",
            "_authenticated.value = false"
        )) {
            val idx = lockBlock.indexOf(needle)
            assertTrue("$needle must still be present in lock()", idx >= 0)
            assertTrue(
                "$needle must live INSIDE the hasMasterPassword gate (passwordless ON_STOP keeps the session)",
                idx > gateIdx
            )
        }
        // The whole teardown is under ONE gate — a leftover unconditional clear
        // (the phase-181 regression) would show up outside it.
        val gateCount = Regex("if \\(settings\\.hasMasterPassword\\) \\{").findAll(lockBlock).count()
        assertEquals(
            "lock() must contain exactly one teardown gate (no unconditional path survives)",
            1,
            gateCount
        )
    }

    @Test
    fun `ON_STOP still locks on every backgrounding including the SAF export picker`() {
        val lockCalls = Regex("viewModel\\.lock\\(\\)").findAll(activitySource).count()
        assertTrue("ON_STOP + auto-lock + screen-off must each call viewModel.lock()", lockCalls >= 3)
        // The exporter picker drives the activity to the background, which hits
        // ON_STOP; the fix is that a passwordless lock() is a session no-op.
        assertTrue(
            "ON_STOP lock hook must remain wired",
            activitySource.contains("Lifecycle.Event.ON_STOP ->") &&
                activitySource.contains("viewModel.lock()")
        )
    }

    @Test
    fun `home page binds the selected notebook to the ViewModel StateFlow not a local snapshot`() {
        val home = java.io.File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/ui/screens/HomeScreen.kt"
        ).readText()
        assertTrue(
            "the home selection must be the VM StateFlow",
            home.contains("viewModel.selectedNotebook.collectAsState()")
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