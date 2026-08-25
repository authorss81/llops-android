package com.authorss81.noteflow

import com.authorss81.noteflow.services.PageSortPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 208 fix #2: client-side page-list sort comparator policy. The DAO order
 * (`ORDER BY pinned DESC, updatedAt DESC`) is hard-coded and there was no sort
 * control anywhere; this pins the pure-JVM comparator that orders the
 * already-collected lists per the persisted SettingsManager.pageSortModeKey.
 */
class PageSortPolicyTest {

    private data class Page(
        val id: String,
        val title: String,
        val pinned: Boolean = false,
        val createdAt: Long,
        val updatedAt: Long
    )

    private fun sort(pages: List<Page>, mode: PageSortPolicy.Mode) =
        PageSortPolicy.sorted(
            pages,
            mode,
            pinned = { it.pinned },
            title = { it.title },
            createdAt = { it.createdAt },
            updatedAt = { it.updatedAt }
        )

    private val sample = listOf(
        Page("a", "Banana", pinned = false, createdAt = 100, updatedAt = 400),
        Page("b", "apple", pinned = false, createdAt = 200, updatedAt = 300),
        Page("c", "Cherry", pinned = true, createdAt = 150, updatedAt = 200),
        Page("d", "date", pinned = false, createdAt = 300, updatedAt = 500)
    )

    @Test
    fun `mode decode is fail-closed to UPDATED_DESC`() {
        assertEquals(PageSortPolicy.Mode.UPDATED_DESC, PageSortPolicy.Mode.fromKey(null))
        assertEquals(PageSortPolicy.Mode.UPDATED_DESC, PageSortPolicy.Mode.fromKey(""))
        assertEquals(PageSortPolicy.Mode.UPDATED_DESC, PageSortPolicy.Mode.fromKey("garbage"))
        assertEquals(PageSortPolicy.Mode.UPDATED_DESC, PageSortPolicy.Mode.fromKey("UPDATED_DESC"))
        assertEquals(PageSortPolicy.Mode.CREATED_DESC, PageSortPolicy.Mode.fromKey("created_desc"))
        assertEquals(PageSortPolicy.Mode.TITLE_ASC, PageSortPolicy.Mode.fromKey("title_asc"))
    }

    @Test
    fun `sanitizePersistenceKey round-trips every mode`() {
        for (mode in PageSortPolicy.Mode.entries) {
            assertEquals(mode.persistenceKey, PageSortPolicy.sanitizePersistenceKey(mode.persistenceKey))
        }
        // Unknown keys sanitize to the DEFAULT key, never an arbitrary string.
        assertEquals(
            PageSortPolicy.Mode.DEFAULT.persistenceKey,
            PageSortPolicy.sanitizePersistenceKey("hax")
        )
    }

    @Test
    fun `UPDATED_DESC orders by updatedAt descending`() {
        val ids = sort(sample, PageSortPolicy.Mode.UPDATED_DESC).map { it.id }
        // Pinned first, then recency: c (pinned), d(500), a(400), b(300).
        assertEquals(listOf("c", "d", "a", "b"), ids)
    }

    @Test
    fun `UPDATED_DESC reproduces the legacy default order exactly`() {
        // With no pinned rows this must equal the DAO's ORDER BY pinned DESC,
        // updatedAt DESC — the pre-208 behavior for the default mode.
        val unpinned = sample.filter { !it.pinned }
        assertEquals(
            unpinned.sortedByDescending { it.updatedAt }.map { it.id },
            sort(unpinned, PageSortPolicy.Mode.UPDATED_DESC).map { it.id }
        )
    }

    @Test
    fun `CREATED_DESC orders by createdAt descending`() {
        val ids = sort(sample, PageSortPolicy.Mode.CREATED_DESC).map { it.id }
        // Pinned first (c), then createdAt desc: d(300), b(200), a(100).
        assertEquals(listOf("c", "d", "b", "a"), ids)
    }

    @Test
    fun `TITLE_ASC is case-insensitive alphabetical`() {
        val ids = sort(sample, PageSortPolicy.Mode.TITLE_ASC).map { it.id }
        // apple < Banana < Cherry < date case-insensitively; pinned c still floats.
        assertEquals(listOf("c", "b", "a", "d"), ids)
    }

    @Test
    fun `pinned notes float to the top in EVERY mode`() {
        for (mode in PageSortPolicy.Mode.entries) {
            assertEquals(
                "mode=$mode",
                "c",
                sort(sample, mode).first().id
            )
        }
    }

    @Test
    fun `equal keys preserve input order (stable merge sort)`() {
        val ties = listOf(
            Page("x1", "Same", createdAt = 1, updatedAt = 10),
            Page("x2", "Same", createdAt = 2, updatedAt = 10),
            Page("x3", "Other", createdAt = 3, updatedAt = 20)
        )
        val sorted = sort(ties, PageSortPolicy.Mode.UPDATED_DESC)
        assertEquals(listOf("x3", "x1", "x2"), sorted.map { it.id })
        // Title mode with equal titles tie-breaks on updatedAt DESC instead of
        // scrambling.
        val titled = sort(ties, PageSortPolicy.Mode.TITLE_ASC)
        assertEquals(listOf("x3", "x1", "x2"), titled.map { it.id })
    }

    @Test
    fun `sorting never mutates the input list`() {
        val before = sample.toList()
        sort(sample, PageSortPolicy.Mode.TITLE_ASC)
        assertEquals(before, sample)
    }

    @Test
    fun `empty and singleton lists are handled`() {
        assertTrue(sort(emptyList(), PageSortPolicy.Mode.TITLE_ASC).isEmpty())
        assertEquals(listOf("solo"), sort(listOf(Page("solo", "Z", createdAt = 1, updatedAt = 1)), PageSortPolicy.Mode.TITLE_ASC).map { it.id })
    }
}
