package com.authorss81.noteflow

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 209: discovery/wiring regression pins — the recent-search chips, the
 * shared fuzzy tier's single-implementation rule, the Plugin Store
 * deep-links from every touched menu surface, and the palette quick-action
 * routing. Behavior lives in `services.FuzzyMatchTest`,
 * `services.RecentSearchPolicyTest` and [Phase209SearchQualityTest].
 */
class Phase209DiscoveryPinsTest {

    private fun mainSource(rel: String): String {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            val d: File = dir
            File(d, "src/main/kotlin/com/authorss81/noteflow/$rel").takeIf { it.isFile }?.let { return it.readText() }
            File(d, "src/main/kotlin/$rel").takeIf { it.isFile }?.let { return it.readText() }
            File(d, "app/src/main/kotlin/com/authorss81/noteflow/$rel").takeIf { it.isFile }?.let { return it.readText() }
            dir = d.parentFile
        }
        throw AssertionError("could not locate app/src/main/kotlin/$rel from ${start.path}")
    }

    private fun sdkSource(rel: String): String {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            val d: File = dir
            File(d, rel).takeIf { it.isFile }?.let { return it.readText() }
            dir = d.parentFile
        }
        throw AssertionError("could not locate $rel from ${start.path}")
    }

    // ---------- Task 1: recent searches ----------

    @Test
    fun `SettingsManager persists the search_recent ring through the policy`() {
        val src = mainSource("services/SettingsManager.kt")
        assertTrue(
            "recent searches must live in the search_recent_<n> ring keys",
            src.contains("search_recent_\$it") && src.contains("search_recent_\$i")
        )
        assertTrue(
            "read-back must sanitize through RecentSearchPolicy.sanitize",
            src.contains("RecentSearchPolicy.sanitize(")
        )
        assertTrue(
            "the ring size must come from the policy cap",
            src.contains("0 until RecentSearchPolicy.CAP")
        )
    }

    @Test
    fun `HomeScreen records executed queries and shows dismissible chips`() {
        val src = mainSource("ui/screens/HomeScreen.kt")
        assertTrue(
            "the search field must track focus for the chips row",
            src.contains(".onFocusChanged { searchFieldFocused = it.isFocused }")
        )
        assertTrue(
            "executed queries must enter the persisted ring",
            src.contains("RecentSearchPolicy.record(recentSearches, searchQuery)")
        )
        assertTrue(
            "recorded rings must persist via SettingsManager",
            src.contains("viewModel.settings.setRecentSearches(updatedRecents)")
        )
        assertTrue(
            "chips render only while the field is focused AND blank",
            src.contains("if (searchFieldFocused && searchQuery.isBlank() && recentSearches.isNotEmpty())")
        )
        assertTrue(
            "tapping a chip re-fills + runs the query",
            src.contains("onClick = { searchQuery = pastQuery }")
        )
        assertTrue(
            "dismissals must persist too",
            src.contains("RecentSearchPolicy.dismiss(recentSearches, pastQuery)") &&
                src.contains("viewModel.settings.setRecentSearches(updated)")
        )
        // Phase-166 compact-screen discipline: a full ring can never clip.
        assertTrue(
            "the chips row is horizontally scrollable",
            Regex("recent-search history chips(?s).*horizontalScroll").containsMatchIn(src)
        )
    }

    // ---------- Task 2: shared fuzzy tier ----------

    @Test
    fun `both scorers share ONE fuzzy implementation - no duplicated matcher`() {
        val vault = mainSource("services/VaultSearchPolicy.kt")
        val palette = mainSource("services/graph/CommandPaletteMath.kt")
        val matcher = mainSource("services/FuzzyMatch.kt")

        assertTrue(vault.contains("FuzzyMatch.subsequenceDensity(query"))
        assertTrue(palette.contains("FuzzyMatch.subsequenceDensityPreLowered"))

        // The greedy subsequence walk exists ONLY in FuzzyMatch.kt — the two
        // scorers must never grow their own copies (drift risk).
        assertFalse(vault.contains("for (qc in q)"))
        assertFalse(palette.contains("for (qc in q)"))
        assertTrue(matcher.contains("for (qc in q)"))
        assertTrue(matcher.contains("fun subsequenceDensityPreLowered"))
        assertTrue(matcher.contains("MIN_DENSITY"))
        assertTrue(matcher.contains("MIN_QUERY_LENGTH"))
    }

    @Test
    fun `vault search orders exact hits before fuzzy ones in both paths`() {
        val repo = mainSource("data/repository/NoteRepository.kt")
        assertTrue(
            "the capped keystroke path applies exactFirst ordering",
            repo.contains("VaultSearchPolicy.exactFirst(allPages.filter { VaultSearchPolicy.pageMatches(it, q) }, q)")
        )
        assertTrue(
            "the deep-scan refine path applies the SAME ordering",
            repo.contains("VaultSearchPolicy.exactFirst(matches, q)")
        )
        val policy = mainSource("services/VaultSearchPolicy.kt")
        assertTrue(
            "the tier enum keeps EXACT above FUZZY by construction",
            policy.indexOf("EXACT") < policy.indexOf("FUZZY") &&
                policy.contains("enum class SearchMatchTier { EXACT, FUZZY }")
        )
    }

    @Test
    fun `palette fuzzy tier sits below BODY_CONTAINS in the scorer order`() {
        val src = mainSource("services/graph/CommandPaletteMath.kt")
        val bodyContains = src.indexOf("MatchKind.BODY_CONTAINS")
        val fuzzy = src.indexOf("MatchKind.FUZZY_MATCH")
        assertTrue(bodyContains > 0)
        assertTrue(fuzzy > bodyContains)
        assertTrue(src.contains("FUZZY_SCORE_FLOOR"))
        assertTrue(src.contains("FUZZY_SCORE_SPREAD"))
    }

    // ---------- Task 3: Plugin Store discovery ----------

    @Test
    fun `markdown editor plugin menu deep-links to the store when empty`() {
        val src = mainSource("ui/screens/MarkdownPreviewScreen.kt")
        assertTrue(
            "the screen exposes the store callback",
            src.contains("onOpenPluginStore: () -> Unit = {}")
        )
        assertTrue(
            "the store entry is gated by the discovery policy",
            src.contains("PluginStoreDiscoveryPolicy.shouldShowEntry(")
        )
        assertTrue(
            "the entry uses the policy label + opens the store",
            src.contains("PluginStoreDiscoveryPolicy.MENU_LABEL") &&
                src.contains("onOpenPluginStore()")
        )
        assertTrue(
            "every capability section feeds the served-entry count",
            src.contains("val servedPluginEntries = transformPlugins.size +")
        )
    }

    @Test
    fun `MainActivity hosts the store dialog for both editor panes and the palette`() {
        val src = mainSource("MainActivity.kt")
        assertEquals(
            "BOTH MarkdownPreviewScreen call sites + the palette callback raise the deep-link flag",
            3,
            Regex("showPluginStoreDeepLink = true").findAll(src).count()
        )
        assertTrue(
            "the activity hosts PluginStoreDialog behind the deep-link flag",
            src.contains("if (showPluginStoreDeepLink) {") &&
                src.contains("com.authorss81.noteflow.ui.components.PluginStoreDialog(")
        )
        val dialogHost = src.indexOf("if (showPluginStoreDeepLink) {")
        val overlayBlock = src.substring(src.indexOf("CommandPaletteOverlay("), dialogHost)
        assertTrue(
            "the command palette's store quick-action raises the same deep-link flag",
            overlayBlock.contains("onOpenPluginStore = {")
        )
    }

    @Test
    fun `palette overlay handles the OpenPluginStore result`() {
        val src = mainSource("ui/components/CommandPaletteOverlay.kt")
        assertTrue(src.contains("onOpenPluginStore: () -> Unit = {}"))
        assertTrue(src.contains("is NoteflowViewModel.PaletteActionResult.OpenPluginStore -> {"))
    }

    @Test
    fun `runPaletteAction routes plugin_store to the OpenPluginStore result`() {
        val src = mainSource("ui/viewmodel/NoteflowViewModel.kt")
        assertTrue(
            src.contains("PluginStoreDiscoveryPolicy.PALETTE_CAPABILITY_KEY ->\n            PaletteActionResult.OpenPluginStore")
        )
        assertTrue(
            "the sealed result carries an OpenPluginStore variant",
            src.contains("data object OpenPluginStore : PaletteActionResult()")
        )
    }

    @Test
    fun `palette catalog carries the store action with a unique keyword`() {
        val src = mainSource("services/graph/CommandPaletteMath.kt")
        assertTrue(src.contains("\"plugin-store\""))
        assertTrue(src.contains("PluginStoreDiscoveryPolicy.PALETTE_KEYWORD"))
        assertTrue(src.contains("suffixHint = \"browse & install plugins\""))
    }

    @Test
    fun `no new capability was added to the SDK - plugin_store is UI routing only`() {
        val sdk = sdkSource("plugin-sdk/src/main/kotlin/com/authorss81/noteflow/plugins/PluginCapability.kt")
        assertFalse("the sealed capability set must stay unchanged", sdk.contains("\"plugin_store\""))
        val policy = mainSource("services/PluginStoreDiscoveryPolicy.kt")
        assertTrue(policy.contains("const val PALETTE_CAPABILITY_KEY = \"plugin_store\""))
    }
}
