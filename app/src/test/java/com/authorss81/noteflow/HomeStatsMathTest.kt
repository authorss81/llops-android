package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.NotePageEntity
import com.authorss81.noteflow.services.HomeStatsMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 156: home glanceable-stats math. Counts must come only from the inputs
 * handed in (never a DB read) and the backup chip must be honest about an
 * empty history ("No backup yet"), today ("Backed up today"), and older.
 */
class HomeStatsMathTest {

    private fun page(title: String, text: String? = "") = NotePageEntity(
        id = "p_" + title.hashCode(),
        sectionId = "sec",
        title = title,
        extractedText = text
    )

    @Test
    fun `empty corpus has zero links`() {
        assertEquals(0, HomeStatsMath.countDistinctWikiLinks(emptyList()))
    }

    @Test
    fun `wiki links are counted once per distinct target`() {
        val pages = listOf(
            page("Alpha", "see [[Beta]] and [[Gamma]]"),
            page("Beta", "links back to [[Alpha]]")
        )
        assertEquals(3, HomeStatsMath.countDistinctWikiLinks(pages))
    }

    @Test
    fun `repeated links to the same target count once`() {
        val pages = listOf(
            page("Alpha", "[[Beta]] [[Beta]] [[Beta]]"),
            page("Beta", "also [[Beta|self-named]]")
        )
        assertEquals(1, HomeStatsMath.countDistinctWikiLinks(pages))
    }

    @Test
    fun `links live in the title too`() {
        val pages = listOf(
            page("Home", "welcome"),
            page("[[Beta]]", null)
        )
        assertEquals(1, HomeStatsMath.countDistinctWikiLinks(pages))
    }

    @Test
    fun `days since backup is null for never-backed-up`() {
        assertNull(HomeStatsMath.daysSinceBackup(0L, System.currentTimeMillis()))
    }

    @Test
    fun `a fresh backup reads as zero days`() {
        val now = 1_725_600_000_000L
        assertEquals(0L, HomeStatsMath.daysSinceBackup(now - 1000L, now))
    }

    @Test
    fun `an old backup reports the whole-day span`() {
        val now = 1_725_600_000_000L
        assertEquals(3L, HomeStatsMath.daysSinceBackup(now - 3 * HomeStatsMath.MILLIS_PER_DAY, now))
    }

    @Test
    fun `backup chip is honest about each state`() {
        val now = 1_725_600_000_000L
        assertEquals("No backup yet", HomeStatsMath.backupChip(0L, now))
        assertEquals("Backed up today", HomeStatsMath.backupChip(now - 1000L, now))
        assertEquals("Backup 2 d ago", HomeStatsMath.backupChip(now - 2 * HomeStatsMath.MILLIS_PER_DAY, now))
    }

    @Test
    fun `chips pluralize and keep the three-part shape`() {
        val chips = HomeStatsMath.chips(noteCount = 1, linkCount = 7, lastBackupEpochMs = 0L, nowEpochMs = 1L)
        assertEquals(listOf("1 note", "7 links", "No backup yet"), chips)
        assertEquals(3, chips.size)
        val plurals = HomeStatsMath.chips(noteCount = 2, linkCount = 1, lastBackupEpochMs = 1L, nowEpochMs = 2L)
        assertEquals("2 notes", plurals[0])
        assertEquals("1 link", plurals[1])
    }
}