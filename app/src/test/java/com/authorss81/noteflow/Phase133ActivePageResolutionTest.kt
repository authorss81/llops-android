package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.NotePageEntity
import com.authorss81.noteflow.services.ActivePageResolution
import com.authorss81.noteflow.services.ActivePageTracker
import com.authorss81.noteflow.services.ActivePageTrackerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 133: pure-JVM tests for the active-page resolution + synchronous tracker.
 *
 * The bug: MainActivity resolved the active page only against the section-filtered
 * `pages` Room flow, which emits asynchronously — so a brand-new page (Add Page
 * FAB / Daily Journal / wiki-link create / shared note) was missing on the
 * immediate creation frame and `pages.find { it.id == activePageId }` returned
 * null, losing the transition. [ActivePageResolution.resolve] restores the
 * fallback matching (synchronous copy → allActivePages → pages) and
 * [ActivePageTracker] keeps the synchronous copy in lock-step with the async
 * Room lists.
 */
class Phase133ActivePageResolutionTest {

    private fun page(id: String, title: String = "Page $id") = NotePageEntity(
        id = id,
        sectionId = "sec",
        title = title
    )

    // ---------- ActivePageResolution.resolve: order of precedence ----------

    @Test
    fun `null activePageId resolves to null`() {
        val p = page("p1")
        assertNull(
            ActivePageResolution.resolve(null, p, listOf(p), listOf(p))
        )
    }

    @Test
    fun `null immediate frame - synchronous copy wins even when async lists lack the page`() {
        // THE bug: the page was just created; neither Room list has emitted yet.
        val fresh = page("new-1", title = "Freshly Created")
        val resolved = ActivePageResolution.resolve(
            activePageId = fresh.id,
            synchronous = fresh,
            allActivePages = emptyList(),
            sectionPages = emptyList()
        )
        assertSame("synchronous copy must open the page on the exact creation frame", fresh, resolved)
    }

    @Test
    fun `synchronous copy takes precedence over both async lists`() {
        val sync = page("p1", title = "sync copy")
        val all = page("p1", title = "all copy")
        val section = page("p1", title = "section copy")
        assertSame(sync, ActivePageResolution.resolve("p1", sync, listOf(all), listOf(section)))
    }

    @Test
    fun `without synchronous copy - allActivePages beats the section-filtered pages`() {
        val all = page("p1", title = "all copy")
        val section = page("p1", title = "section copy")
        assertSame(all, ActivePageResolution.resolve("p1", null, listOf(all), listOf(section)))
        // same when the allActivePages hit is not first in list order
        val all2 = page("p1", title = "all copy 2")
        assertSame(all2, ActivePageResolution.resolve("p1", null, listOf(page("other"), all2), listOf(section)))
    }

    @Test
    fun `without synchronous or allActivePages match - section pages wins`() {
        val section = page("p1", title = "section copy")
        assertSame(section, ActivePageResolution.resolve("p1", null, emptyList(), listOf(section)))
    }

    @Test
    fun `id present in no list resolves to null`() {
        assertNull(ActivePageResolution.resolve("missing", null, listOf(page("a")), listOf(page("b"))))
    }

    @Test
    fun `synchronous copy with a different id is ignored`() {
        val stale = page("other")
        val target = page("p1")
        val resolved = ActivePageResolution.resolve("p1", stale, listOf(target), emptyList())
        assertSame("a stale synchronous copy must never shadow a different id", target, resolved)
    }

    // ---------- ActivePageTracker.open ----------

    @Test
    fun `open with null page resets the tracker to idle`() {
        val state = ActivePageTracker.open(ActivePageTrackerState(), null, emptyList(), emptyList())
        assertNull(state.id)
        assertNull(state.synchronous)
        assertFalse(state.confirmed)
    }

    @Test
    fun `open with a brand-new page - unconfirmed until Room emits`() {
        val fresh = page("new-1")
        val state = ActivePageTracker.open(ActivePageTrackerState(), fresh, emptyList(), emptyList())
        assertEquals(fresh.id, state.id)
        assertSame(fresh, state.synchronous)
        assertFalse("creation-frame copy must not be DB-confirmed yet", state.confirmed)
    }

    @Test
    fun `open with an existing page already known to the async lists is confirmed`() {
        val existing = page("p1")
        val state = ActivePageTracker.open(ActivePageTrackerState(), existing, listOf(existing), emptyList())
        assertTrue(state.confirmed)
        // confirmed via the section-filtered list too
        val state2 = ActivePageTracker.open(ActivePageTrackerState(), existing, emptyList(), listOf(existing))
        assertTrue(state2.confirmed)
    }

    @Test
    fun `open replaces a previous tracker state`() {
        val first = ActivePageTracker.open(ActivePageTrackerState(), page("p1"), emptyList(), emptyList())
        val second = ActivePageTracker.open(first, page("p2"), emptyList(), emptyList())
        assertEquals("p2", second.id)
        assertEquals("p2", second.synchronous?.id)
        assertNull("previous page must be gone", first.synchronous?.let { if (it.id == "p2") it else null })
    }

    // ---------- ActivePageTracker.onAuthoritative ----------

    @Test
    fun `authoritative emit refreshes the synchronous copy and confirms it`() {
        val fresh = page("new-1", title = "Created")
        val state = ActivePageTracker.open(ActivePageTrackerState(), fresh, emptyList(), emptyList())
        val dbVersion = page("new-1", title = "Renamed by another writer")
        val after = ActivePageTracker.onAuthoritative(state, listOf(dbVersion), emptyList())
        assertSame("must adopt the authoritative instance", dbVersion, after.synchronous)
        assertTrue(after.confirmed)
        assertEquals("new-1", after.id)
    }

    @Test
    fun `confirmed page absent from every source is dropped (deleted - never a stale editor)`() {
        val existing = page("p1")
        val opened = ActivePageTracker.open(ActivePageTrackerState(), existing, listOf(existing), emptyList())
        assertTrue(opened.confirmed)
        val after = ActivePageTracker.onAuthoritative(opened, emptyList(), emptyList())
        assertNull("a DB-confirmed page that disappears must close", after.id)
        assertNull(after.synchronous)
        assertFalse(after.confirmed)
    }

    @Test
    fun `unconfirmed creation-frame copy survives an authoritative emit that still lacks it`() {
        val fresh = page("new-1")
        val state = ActivePageTracker.open(ActivePageTrackerState(), fresh, emptyList(), emptyList())
        val after = ActivePageTracker.onAuthoritative(state, emptyList(), emptyList())
        assertSame("creation-frame race must keep the synchronous copy", fresh, after.synchronous)
        assertEquals(fresh.id, after.id)
        assertFalse(after.confirmed)
    }

    @Test
    fun `idle tracker is a no-op for authoritative emits`() {
        val idle = ActivePageTrackerState()
        assertSame(idle, ActivePageTracker.onAuthoritative(idle, listOf(page("p1")), emptyList()))
    }

    // ---------- ActivePageTracker.restore ----------

    @Test
    fun `restore with a blank saved id is idle`() {
        assertNull(ActivePageTracker.restore("", listOf(page("p1")), emptyList()).id)
        assertNull(ActivePageTracker.restore(null, listOf(page("p1")), emptyList()).id)
        assertNull(ActivePageTracker.restore("   ", listOf(page("p1")), emptyList()).id)
    }

    @Test
    fun `restore finds the page in the section list first and confirms it`() {
        val section = page("p1", title = "in section")
        val state = ActivePageTracker.restore("p1", listOf(page("p1", title = "in all")), listOf(section))
        assertSame(section, state.synchronous)
        assertTrue(state.confirmed)
    }

    @Test
    fun `restore falls back to allActivePages when the section list lacks the page`() {
        val all = page("p2", title = "other section")
        val state = ActivePageTracker.restore("p2", listOf(all), listOf(page("p1")))
        assertSame(all, state.synchronous)
        assertTrue(state.confirmed)
    }

    @Test
    fun `restore for a page that no longer exists is idle`() {
        val state = ActivePageTracker.restore("gone", listOf(page("p1")), listOf(page("p2")))
        assertNull(state.id)
        assertNull(state.synchronous)
        assertFalse(state.confirmed)
    }
}
