package com.authorss81.noteflow.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 209: the SHARED typo-tolerant matcher behind both search scorers
 * (`VaultSearchPolicy.pageMatchTier` and `CommandPaletteMath.score`).
 *
 * Contract under test:
 *  - POSITIVE typo cases: an in-order subsequence with enough density matches
 *    ("ntebook" → "notebook", "nte" → "note"/"notebook", "helo" → "hello").
 *  - NEGATIVE noise cases: non-subsequences never match; sparse subsequences
 *    below [FuzzyMatch.MIN_DENSITY] never match ("nte" ↛ "alternative");
 *    single-character queries NEVER match ([FuzzyMatch.MIN_QUERY_LENGTH]).
 *  - Ordering signal: tighter matches score strictly higher; contiguous
 *    substrings score exactly 1.0.
 */
class FuzzyMatchTest {

    // ---------- positive typo cases ----------

    @Test
    fun `typo'd query matches its intended word`() {
        assertNotNull(FuzzyMatch.subsequenceDensity("ntebook", "notebook"))
        assertNotNull(FuzzyMatch.subsequenceDensity("ntebook", "Notebook"))
        assertNotNull(FuzzyMatch.subsequenceDensity("helo", "hello"))
        assertNotNull(FuzzyMatch.subsequenceDensity("aple", "apple"))
    }

    @Test
    fun `short typo query still matches a tight host word`() {
        assertNotNull(FuzzyMatch.subsequenceDensity("nte", "note"))
        assertNotNull(FuzzyMatch.subsequenceDensity("nte", "notebook"))
    }

    @Test
    fun `matching is case-insensitive`() {
        assertEquals(
            FuzzyMatch.subsequenceDensity("ntebook", "notebook"),
            FuzzyMatch.subsequenceDensity("NTEBOOK", "NOTEBOOK")
        )
        assertEquals(
            FuzzyMatch.subsequenceDensity("ntebook", "notebook"),
            FuzzyMatch.subsequenceDensity("NteBook", "noTeBook")
        )
    }

    // ---------- negative noise cases ----------

    @Test
    fun `non-subsequences never match`() {
        assertNull(FuzzyMatch.subsequenceDensity("xyz", "notebook"))
        assertNull(FuzzyMatch.subsequenceDensity("zzz", "alpha beta gamma"))
        assertNull(FuzzyMatch.subsequenceDensity("milk", "groceries"))
    }

    @Test
    fun `sparse subsequence below the density floor is rejected`() {
        // "nte" IS an in-order subsequence of "alternative" but far too sparse.
        assertNull(FuzzyMatch.subsequenceDensity("nte", "alternative"))
        // Two matched chars buried deep in a long span are noise, not a hit.
        assertNull(FuzzyMatch.subsequenceDensity("ct", "electric"))
    }

    @Test
    fun `single-character and empty queries never fuzzy-match`() {
        assertNull(FuzzyMatch.subsequenceDensity("n", "notebook"))
        assertNull(FuzzyMatch.subsequenceDensity("", "notebook"))
        assertNull(FuzzyMatch.subsequenceDensity("nte", ""))
    }

    @Test
    fun `boolean form agrees with the density form`() {
        assertTrue(FuzzyMatch.isFuzzyMatch("ntebook", "notebook"))
        assertFalse(FuzzyMatch.isFuzzyMatch("nte", "alternative"))
        assertFalse(FuzzyMatch.isFuzzyMatch("z", "zoo"))
    }

    // ---------- ordering signal ----------

    @Test
    fun `contiguous substring scores the maximum density 1_0`() {
        assertEquals(1.0f, FuzzyMatch.subsequenceDensity("note", "notebook")!!, 0.0001f)
    }

    @Test
    fun `tighter matches score strictly higher than looser ones`() {
        val tight = FuzzyMatch.subsequenceDensity("ntebook", "notebook")!!
        val loose = FuzzyMatch.subsequenceDensity("nte", "notebook")!!
        assertTrue(tight > loose)
        // Both stay inside the documented (0..1] band.
        assertTrue(tight in 0f..1f)
        assertTrue(loose in 0f..1f)
    }

    @Test
    fun `density decreases as skipped characters grow`() {
        val contiguous = FuzzyMatch.subsequenceDensity("no", "note")!! // 0 skipped → 1.0
        val oneSkip = FuzzyMatch.subsequenceDensity("nt", "note")!!    // 'o' skipped → 2/3
        assertTrue(contiguous > oneSkip)
    }

    // ---------- pre-lowered hot path ----------

    @Test
    fun `pre-lowered path matches the convenience path`() {
        val convenience = FuzzyMatch.subsequenceDensity("NteBook", "a Notebook Entry")
        val preLowered = FuzzyMatch.subsequenceDensityPreLowered(
            "NteBook".lowercase(),
            "a Notebook Entry".lowercase()
        )
        assertEquals(convenience, preLowered)
        assertNotNull(preLowered)
    }
}
