package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.NotePageEntity
import com.authorss81.noteflow.services.PluginStoreDiscoveryPolicy
import com.authorss81.noteflow.services.VaultSearchPolicy
import com.authorss81.noteflow.services.graph.CommandPaletteMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 209: search-quality behavior — the typo-tolerant tier wired into BOTH
 * scorers, with exact matches always outranking fuzzy ones, plus the Plugin
 * Store discovery decision table. (UI wiring is pinned in
 * [Phase209DiscoveryPinsTest]; the shared matcher itself in
 * `services.FuzzyMatchTest` / `services.RecentSearchPolicyTest`.)
 */
class Phase209SearchQualityTest {

    private fun page(
        id: String,
        title: String,
        body: String? = null,
        updatedAt: Long = 0L
    ) = NotePageEntity(id = id, sectionId = "s", title = title, extractedText = body, updatedAt = updatedAt)

    // ---------- VaultSearchPolicy: tiered matching ----------

    @Test
    fun `exact hits are EXACT tier regardless of case`() {
        val p = page("p1", "Trip to Berlin", "Waves crash loudly on the shore.")
        assertEquals(VaultSearchPolicy.SearchMatchTier.EXACT, VaultSearchPolicy.pageMatchTier(p, "berlin"))
        assertEquals(VaultSearchPolicy.SearchMatchTier.EXACT, VaultSearchPolicy.pageMatchTier(p, "CRASH"))
    }

    @Test
    fun `typo'd queries fall into the FUZZY tier`() {
        val notebook = page("p2", "Noteboook Ideas")
        assertEquals(VaultSearchPolicy.SearchMatchTier.FUZZY, VaultSearchPolicy.pageMatchTier(notebook, "ntebook"))
        // Title-hosted typo: "trp" hugs the start of "Trip …" (density 0.75).
        val trip = page("p3", "Trip to Berlin", "Waves crash loudly on the shore.")
        assertEquals(VaultSearchPolicy.SearchMatchTier.FUZZY, VaultSearchPolicy.pageMatchTier(trip, "trp"))
    }

    @Test
    fun `noise never matches - fuzzy tier stays selective`() {
        val p = page("p4", "Trip to Berlin", "Waves crash loudly on the shore.")
        assertNull(VaultSearchPolicy.pageMatchTier(p, "zzz"))
        assertNull(VaultSearchPolicy.pageMatchTier(p, "paris"))
        assertNull(VaultSearchPolicy.pageMatchTier(p, "nte"))
    }

    @Test
    fun `pageMatches stays true for both tiers`() {
        val p = page("p5", "Groceries")
        assertTrue(VaultSearchPolicy.pageMatches(p, "GROCERIES"))
        assertTrue(VaultSearchPolicy.pageMatches(p, "groceris"))
        assertFalse(VaultSearchPolicy.pageMatches(p, "milk"))
    }

    @Test
    fun `exactFirst orders exact hits ahead of fuzzy ones and is stable within a tier`() {
        // Deliberately interleaved + the fuzzy page NEWER than both exact pages.
        // "Noteboook" (typo host) matches "notebook" fuzzily (density 0.8) but
        // never exactly; "Notebook A/B" are exact substring hits; the unrelated
        // page never matches and is dropped by the same filter the repo runs.
        val candidates = listOf(
            page("f1", "Noteboook", updatedAt = 999L),
            page("e1", "Notebook A", updatedAt = 1L),
            page("n1", "Unrelated", updatedAt = 5L),
            page("e2", "Notebook B", updatedAt = 2L)
        )
        val ordered = VaultSearchPolicy.exactFirst(
            candidates.filter { VaultSearchPolicy.pageMatches(it, "notebook") },
            "notebook"
        )
        assertEquals(listOf("e1", "e2", "f1"), ordered.map { it.id })
    }

    // ---------- CommandPaletteMath: fuzzy tier below every exact tier ----------

    private fun doc(
        id: String,
        title: String,
        body: String = "",
        tags: Set<String> = emptySet(),
        updatedAt: Long = 0L
    ) = CommandPaletteMath.PaletteDoc(id, title, body, tags, updatedAt)

    @Test
    fun `palette typo query matches through the FUZZY_MATCH kind`() {
        val (score, kind) = CommandPaletteMath.score("ntebook", doc("d1", "Notebook"))!!
        assertEquals(CommandPaletteMath.MatchKind.FUZZY_MATCH, kind)
        assertTrue(score > 0f)
    }

    @Test
    fun `palette fuzzy score band stays strictly below BODY_CONTAINS`() {
        val fuzzyScore = CommandPaletteMath.score("ntebook", doc("d1", "Notebook"))!!.first
        val exactBody = CommandPaletteMath.score("quick", doc("d2", "Animals", body = "the quick brown fox"))!!
        assertTrue(fuzzyScore < exactBody.first)
        assertTrue(fuzzyScore < 40f)
    }

    @Test
    fun `palette ranking preserves exact-beats-fuzzy even when the fuzzy note is newer`() {
        // "Noteboook" hosts "notebook" fuzzily (density 0.8) but never exactly.
        val typoDoc = doc("typo-only", "Noteboook", updatedAt = 9_000L)
        val exactDoc = doc("exact", "Notebook", updatedAt = 1_000L)
        val ranked = CommandPaletteMath.rank("notebook", listOf(typoDoc, exactDoc))
        assertEquals("exact", ranked.first().doc.id)
        assertEquals(2, ranked.size)
    }

    @Test
    fun `tighter fuzzy matches score above looser ones inside the tier`() {
        // "ntebook" against "Notebook" skips 3 chars; against
        // "Notable Bookcase" it skips 5 — both FUZZY hits, tighter ranks higher.
        val tight = CommandPaletteMath.score("ntebook", doc("tight", "Notebook"))
        val loose = CommandPaletteMath.score("ntebook", doc("loose", "Notable Bookcase"))
        assertNotNull(tight)
        assertNotNull(loose)
        assertTrue(tight!!.second == CommandPaletteMath.MatchKind.FUZZY_MATCH)
        assertTrue(loose!!.second == CommandPaletteMath.MatchKind.FUZZY_MATCH)
        assertTrue(tight.first > loose.first)
    }

    @Test
    fun `palette noise queries still return nothing`() {
        assertNull(CommandPaletteMath.score("zzz", doc("d1", "Alpha", body = "beta")))
        assertTrue(CommandPaletteMath.rank("qqqqq", listOf(doc("d1", "anything"))).isEmpty())
    }

    // ---------- palette Plugin Store quick-action ----------

    @Test
    fun `store keyword routes to the plugin-store capability key`() {
        val bare = CommandPaletteMath.matchAction("store")
        assertNotNull(bare)
        assertEquals(PluginStoreDiscoveryPolicy.PALETTE_CAPABILITY_KEY, bare!!.action.capabilityKey)
        assertEquals("", bare.arg)

        val colon = CommandPaletteMath.matchAction("store: ocr")
        assertEquals(PluginStoreDiscoveryPolicy.PALETTE_CAPABILITY_KEY, colon!!.action.capabilityKey)

        // Words merely CONTAINING the keyword must not route (no false positives).
        assertNull(CommandPaletteMath.matchAction("storehouse of notes"))
    }

    @Test
    fun `catalog keywords stay unique after adding store`() {
        val keywords = CommandPaletteMath.ACTION_CATALOG.map { it.keyword }
        assertEquals(keywords.size, keywords.toSet().size)
    }

    // ---------- PluginStoreDiscoveryPolicy ----------

    @Test
    fun `store entry shows for empty menus and for the rendered-empty placeholder`() {
        assertTrue(PluginStoreDiscoveryPolicy.shouldShowEntry(servedEntries = 0, emptyPlaceholderVisible = false))
        assertTrue(PluginStoreDiscoveryPolicy.shouldShowEntry(servedEntries = 0, emptyPlaceholderVisible = true))
        assertTrue(PluginStoreDiscoveryPolicy.shouldShowEntry(servedEntries = 5, emptyPlaceholderVisible = true))
        assertFalse(PluginStoreDiscoveryPolicy.shouldShowEntry(servedEntries = 5, emptyPlaceholderVisible = false))
    }

    @Test
    fun `discovery constants are user-facing and stable`() {
        assertEquals("Browse Plugin Store…", PluginStoreDiscoveryPolicy.MENU_LABEL)
        assertEquals("plugin_store", PluginStoreDiscoveryPolicy.PALETTE_CAPABILITY_KEY)
        assertEquals("store", PluginStoreDiscoveryPolicy.PALETTE_KEYWORD)
        assertTrue(PluginStoreDiscoveryPolicy.shouldOpenFromPalette("plugin_store"))
        assertFalse(PluginStoreDiscoveryPolicy.shouldOpenFromPalette("web_search"))
    }
}
