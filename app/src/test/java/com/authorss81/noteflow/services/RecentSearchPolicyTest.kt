package com.authorss81.noteflow.services

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 209: recent-search ring policy — insert/dedupe/cap decision table for
 * HomeScreen's recent-search chips. Pure JVM; the SharedPreferences glue in
 * `SettingsManager` (`search_recent_<n>` keys) sanitizes through the same
 * [RecentSearchPolicy.sanitize] tested here.
 */
class RecentSearchPolicyTest {

    @Test
    fun `cap is eight`() {
        assertEquals(8, RecentSearchPolicy.CAP)
    }

    @Test
    fun `recording into an empty ring puts the query first`() {
        assertEquals(listOf("notebook"), RecentSearchPolicy.record(emptyList(), "notebook"))
    }

    @Test
    fun `newest query leads, older entries keep their order`() {
        val ring = listOf("a", "b", "c")
        assertEquals(listOf("x", "a", "b", "c"), RecentSearchPolicy.record(ring, "x"))
    }

    @Test
    fun `re-recording a query moves it to the front without duplicating`() {
        val ring = listOf("beta", "alpha", "gamma")
        assertEquals(
            listOf("alpha", "beta", "gamma"),
            RecentSearchPolicy.record(ring, "alpha")
        )
    }

    @Test
    fun `dedupe is case-insensitive and trims`() {
        val ring = listOf("Beta", "alpha")
        // The NEW spelling wins (trimmed) and the old entry is dropped.
        assertEquals(
            listOf("beta", "alpha"),
            RecentSearchPolicy.record(ring, "  beta ")
        )
    }

    @Test
    fun `ring never exceeds the cap - oldest drops first`() {
        var ring = emptyList<String>()
        for (i in 1..10) {
            ring = RecentSearchPolicy.record(ring, "query$i")
        }
        assertEquals(RecentSearchPolicy.CAP, ring.size)
        // Most recent ten survive: query10 … query3.
        assertEquals("query10", ring.first())
        assertEquals("query3", ring.last())
    }

    @Test
    fun `blank queries are never recorded`() {
        val ring = listOf("existing")
        assertEquals(ring, RecentSearchPolicy.record(ring, ""))
        assertEquals(ring, RecentSearchPolicy.record(ring, "   "))
        assertEquals(emptyList<String>(), RecentSearchPolicy.record(emptyList(), "  "))
    }

    @Test
    fun `dismiss removes a query case-insensitively`() {
        val ring = listOf("Alpha", "beta")
        assertEquals(listOf("beta"), RecentSearchPolicy.dismiss(ring, "ALPHA"))
        assertEquals(listOf("Alpha"), RecentSearchPolicy.dismiss(ring, " Beta "))
    }

    @Test
    fun `dismissing an unknown query leaves the ring unchanged`() {
        val ring = listOf("alpha", "beta")
        assertEquals(ring, RecentSearchPolicy.dismiss(ring, "missing"))
        assertEquals(ring, RecentSearchPolicy.dismiss(ring, ""))
    }

    @Test
    fun `sanitize drops blanks and dedupes on read-back`() {
        val sanitized = RecentSearchPolicy.sanitize(
            listOf(" alpha ", "", "ALPHA", null, "beta", "  ", "Gamma")
        )
        assertEquals(listOf("alpha", "beta", "Gamma"), sanitized)
    }

    @Test
    fun `sanitize caps at eight even with more raw entries`() {
        val raw = (0 until 20).map { "q$it" }
        val sanitized = RecentSearchPolicy.sanitize(raw)
        assertEquals(RecentSearchPolicy.CAP, sanitized.size)
        assertEquals(listOf("q0", "q1"), sanitized.take(2))
    }
}
