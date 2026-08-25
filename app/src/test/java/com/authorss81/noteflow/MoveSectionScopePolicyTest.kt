package com.authorss81.noteflow

import com.authorss81.noteflow.services.MoveSectionScopePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 208 review-fix (finding 2): "Move to Section…" must never silently
 * re-parent a page across notebooks. The vault-search corpus spans every
 * notebook, so search-result cards can carry foreign-notebook pages; this pins
 * the partition rule that moves only pages whose CURRENT section belongs to the
 * active notebook.
 */
class MoveSectionScopePolicyTest {

    private val activeSections = setOf("sec-a1", "sec-a2")

    @Test
    fun `pages whose section belongs to the active notebook are movable`() {
        val (movable, blocked) = MoveSectionScopePolicy.partition(
            listOf("p1", "p2"),
            sectionIdOfPage = { if (it == "p1") "sec-a1" else "sec-a2" },
            activeNotebookSectionIds = activeSections
        )
        assertEquals(listOf("p1", "p2"), movable)
        assertTrue(blocked.isEmpty())
    }

    @Test
    fun `foreign-notebook pages are blocked`() {
        val (movable, blocked) = MoveSectionScopePolicy.partition(
            listOf("foreign"),
            sectionIdOfPage = { "sec-other-notebook" },
            activeNotebookSectionIds = activeSections
        )
        assertTrue(movable.isEmpty())
        assertEquals(listOf("foreign"), blocked)
    }

    @Test
    fun `unknown section ids fail closed to blocked`() {
        val (movable, blocked) = MoveSectionScopePolicy.partition(
            listOf("ghost"),
            sectionIdOfPage = { null }, // dangling sectionId / page vanished from the flow
            activeNotebookSectionIds = activeSections
        )
        assertTrue(movable.isEmpty())
        assertEquals(listOf("ghost"), blocked)
    }

    @Test
    fun `an empty active section list blocks everything`() {
        val (movable, blocked) = MoveSectionScopePolicy.partition(
            listOf("p1", "p2", "p3"),
            sectionIdOfPage = { "sec-a1" },
            activeNotebookSectionIds = emptySet()
        )
        assertTrue(movable.isEmpty())
        assertEquals(listOf("p1", "p2", "p3"), blocked)
    }

    @Test
    fun `mixed selections keep order in both partitions`() {
        val ids = listOf("a", "b", "c", "d")
        val (movable, blocked) = MoveSectionScopePolicy.partition(
            ids,
            sectionIdOfPage = { if (it == "b" || it == "d") "sec-x" else "sec-a2" },
            activeNotebookSectionIds = activeSections
        )
        assertEquals(listOf("a", "c"), movable)
        assertEquals(listOf("b", "d"), blocked)
    }
}
