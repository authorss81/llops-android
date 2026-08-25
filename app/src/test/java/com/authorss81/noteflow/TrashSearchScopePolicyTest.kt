package com.authorss81.noteflow

import com.authorss81.noteflow.services.TrashSearchScopePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 208 fix #1 (CRITICAL data-loss bug): search results must be scoped per
 * tab. The full tab × query matrix is pinned here — on the Trash tab a query
 * MUST scope results to trashed ids (an honest empty state today), never render
 * live notes with the Restore / Delete Permanently menu.
 */
class TrashSearchScopePolicyTest {

    private val liveIds = listOf("live-1", "live-2", "live-3")
    private val trashedIds = setOf("trash-1", "trash-2")

    @Test
    fun `every tab x query combination resolves the expected scope`() {
        val expected = mapOf(
            // (tab, hasQuery) -> scope
            (TrashSearchScopePolicy.TAB_PAGES to true) to TrashSearchScopePolicy.Scope.LIVE_RESULTS,
            (TrashSearchScopePolicy.TAB_PAGES to false) to TrashSearchScopePolicy.Scope.LIVE_RESULTS,
            (TrashSearchScopePolicy.TAB_RECENT to true) to TrashSearchScopePolicy.Scope.LIVE_RESULTS,
            (TrashSearchScopePolicy.TAB_RECENT to false) to TrashSearchScopePolicy.Scope.LIVE_RESULTS,
            (TrashSearchScopePolicy.TAB_TAG_VAULT to true) to TrashSearchScopePolicy.Scope.LIVE_RESULTS,
            (TrashSearchScopePolicy.TAB_TAG_VAULT to false) to TrashSearchScopePolicy.Scope.LIVE_RESULTS,
            // THE BUG: query + trash used to render live notes as trash cards.
            (TrashSearchScopePolicy.TAB_TRASH to true) to TrashSearchScopePolicy.Scope.TRASH_INTERSECT,
            (TrashSearchScopePolicy.TAB_TRASH to false) to TrashSearchScopePolicy.Scope.LIVE_RESULTS
        )
        for ((key, want) in expected) {
            val (tab, hasQuery) = key
            assertEquals("tab=$tab hasQuery=$hasQuery", want, TrashSearchScopePolicy.scopeFor(tab, hasQuery))
        }
    }

    @Test
    fun `unknown tab indices fail safe to LIVE_RESULTS`() {
        assertEquals(
            TrashSearchScopePolicy.Scope.LIVE_RESULTS,
            TrashSearchScopePolicy.scopeFor(-1, true)
        )
        assertEquals(
            TrashSearchScopePolicy.Scope.LIVE_RESULTS,
            TrashSearchScopePolicy.scopeFor(99, true)
        )
    }

    @Test
    fun `isTrashContext is true only for tab 3`() {
        assertTrue(TrashSearchScopePolicy.isTrashContext(TrashSearchScopePolicy.TAB_TRASH))
        assertFalse(TrashSearchScopePolicy.isTrashContext(TrashSearchScopePolicy.TAB_PAGES))
        assertFalse(TrashSearchScopePolicy.isTrashContext(TrashSearchScopePolicy.TAB_RECENT))
        assertFalse(TrashSearchScopePolicy.isTrashContext(TrashSearchScopePolicy.TAB_TAG_VAULT))
        assertFalse(TrashSearchScopePolicy.isTrashContext(-1))
    }

    @Test
    fun `TRASH_INTERSECT keeps only rows whose id is actually trashed`() {
        val results = listOf("live-1", "live-2", "trash-1")
        val scoped = TrashSearchScopePolicy.scoped(
            results,
            scope = TrashSearchScopePolicy.Scope.TRASH_INTERSECT,
            trashedIds = trashedIds,
            idOf = { it }
        )
        assertEquals(listOf("trash-1"), scoped)
    }

    @Test
    fun `the phase bug scenario yields an honest empty list`() {
        // Search backend returns ONLY non-deleted rows; scoping them against the
        // trashed set must produce an EMPTY list (scoped-empty state), so no live
        // note can ever render under isTrash=true.
        val scoped = TrashSearchScopePolicy.scoped(
            liveIds,
            scope = TrashSearchScopePolicy.Scope.TRASH_INTERSECT,
            trashedIds = trashedIds,
            idOf = { it }
        )
        assertTrue(scoped.isEmpty())
    }

    @Test
    fun `LIVE_RESULTS passes through untouched`() {
        assertEquals(
            liveIds,
            TrashSearchScopePolicy.scoped(
                liveIds,
                scope = TrashSearchScopePolicy.Scope.LIVE_RESULTS,
                trashedIds = emptySet(),
                idOf = { it }
            )
        )
    }

    @Test
    fun `empty result set stays empty in both scopes`() {
        for (scope in TrashSearchScopePolicy.Scope.entries) {
            assertTrue(
                TrashSearchScopePolicy.scoped(emptyList<String>(), scope, trashedIds) { it }.isEmpty()
            )
        }
    }

    @Test
    fun `intersection preserves the backend result order`() {
        val scoped = TrashSearchScopePolicy.scoped(
            listOf("trash-2", "live-1", "trash-1"),
            scope = TrashSearchScopePolicy.Scope.TRASH_INTERSECT,
            trashedIds = trashedIds,
            idOf = { it }
        )
        assertEquals(listOf("trash-2", "trash-1"), scoped)
    }

    // ---------------- review fixes (2026-08-25) ----------------

    @Test
    fun `review-fix finding 1 - derived lists intersect on the trash tab only`() {
        // Derived lists = search hits AND tag-filter matches. Only the trash
        // context may intersect; every other tab passes through.
        assertEquals(
            TrashSearchScopePolicy.Scope.TRASH_INTERSECT,
            TrashSearchScopePolicy.scopeForDerivedList(TrashSearchScopePolicy.TAB_TRASH)
        )
        for (tab in intArrayOf(
            TrashSearchScopePolicy.TAB_PAGES,
            TrashSearchScopePolicy.TAB_RECENT,
            TrashSearchScopePolicy.TAB_TAG_VAULT,
            -1,
            99
        )) {
            assertEquals(
                "tab=$tab",
                TrashSearchScopePolicy.Scope.LIVE_RESULTS,
                TrashSearchScopePolicy.scopeForDerivedList(tab)
            )
        }
    }

    @Test
    fun `review-fix finding 1 - tag-matched live notes on the trash tab render empty`() {
        // The second half of the phase bug class: a tag filter left active while
        // switching to the Trash tab must never surface LIVE notes under
        // isTrash=true — same honest scoped-empty as query search.
        val scoped = TrashSearchScopePolicy.scoped(
            liveIds,
            scope = TrashSearchScopePolicy.scopeForDerivedList(TrashSearchScopePolicy.TAB_TRASH),
            trashedIds = trashedIds,
            idOf = { it }
        )
        assertTrue(scoped.isEmpty())
    }

    @Test
    fun `review-fix finding 1 - query search and derived lists share one rule`() {
        for (tab in 0..4) {
            assertEquals(
                "tab=$tab",
                TrashSearchScopePolicy.scopeFor(tab, hasQuery = true),
                TrashSearchScopePolicy.scopeForDerivedList(tab)
            )
        }
    }
}
