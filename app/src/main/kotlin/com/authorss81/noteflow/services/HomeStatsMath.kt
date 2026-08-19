package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.NotePageEntity

/**
 * Phase 156: pure, JVM-testable math for the home glanceable stats chips
 * (n notes · n links · n days since last backup).
 *
 * All inputs are already in memory (the cached decrypted search corpus / the
 * collected `allActivePages` list / `SettingsManager.lastBackupTimestamp`) —
 * this object NEVER performs a database read itself, so the home header stays
 * cheap on any device.
 */
object HomeStatsMath {

    const val MILLIS_PER_DAY = 86_400_000L

    /**
     * Distinct wikilink TARGETS across the given (already-decrypted) pages,
     * scanning only title + extractedText. Bounded per page by
     * [WikiLinkParser.MAX_LINKS_PER_PAGE] at extraction time, so a crafted page
     * cannot fan out an unbounded count.
     */
    fun countDistinctWikiLinks(pages: List<NotePageEntity>): Int {
        if (pages.isEmpty()) return 0
        val seen = HashSet<String>(minOf(pages.size * 2, 4096))
        for (page in pages) {
            val text = page.title + "\n" + (page.extractedText ?: "")
            for (link in WikiLinkParser.extractWikiLinks(text)) {
                seen.add(link.targetTitle)
            }
        }
        return seen.size
    }

    /** Days since the last backup; `null` when there has never been one. */
    fun daysSinceBackup(lastBackupEpochMs: Long, nowEpochMs: Long): Long? {
        if (lastBackupEpochMs <= 0L) return null
        if (nowEpochMs <= lastBackupEpochMs) return 0L
        return (nowEpochMs - lastBackupEpochMs) / MILLIS_PER_DAY
    }

    /** Human backup chip: "No backup yet" / "Backed up today" / "Backup N d ago". */
    fun backupChip(lastBackupEpochMs: Long, nowEpochMs: Long): String = when (val days = daysSinceBackup(lastBackupEpochMs, nowEpochMs)) {
        null -> "No backup yet"
        0L -> "Backed up today"
        else -> "Backup $days d ago"
    }

    /** The three home header chips in display order. */
    fun chips(
        noteCount: Int,
        linkCount: Int,
        lastBackupEpochMs: Long,
        nowEpochMs: Long
    ): List<String> = listOf(
        "$noteCount note" + if (noteCount == 1) "" else "s",
        "$linkCount link" + if (linkCount == 1) "" else "s",
        backupChip(lastBackupEpochMs, nowEpochMs)
    )
}
