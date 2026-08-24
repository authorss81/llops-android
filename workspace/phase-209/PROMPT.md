# Phase 209: Search Quality & Plugin Discovery — Recent Searches, Typo Tolerance, Store Deep-Links [UX]

**Goal:** Three retrieval/discovery gaps, all pure-JVM friendly, no new deps.

1. **No recent-search history.** The search field is a bare TextField (`HomeScreen.kt:1045-1068`); repo-wide grep shows no `recentSearch|searchHistory`. 
   **Fix:** persist last 8 non-blank queries in SettingsManager (`search_recent_<n>` ring); show as dismissible chips when the field gains focus while blank; tapping fills + runs the query; pure-JVM `RecentSearchPolicy` (ring insert/dedupe/cap) + tests.

2. **Search is exact-substring only.** `VaultSearchPolicy.pageMatches` (`VaultSearchPolicy.kt:66-68`) and the palette scorer `CommandPaletteMath.score` (`services/graph/CommandPaletteMath.kt:96-110`) are tiered but exact-substring; no fuzzy/subsequence anywhere.
   **Fix:** add a subsequence/typo tier BELOW `BODY_CONTAINS` in both scorers (pure JVM): case-insensitive in-order subsequence match ranked lower than any exact hit, with a length-aware gap penalty so "nte" doesn't match everything. Mirror the same function in `VaultSearchPolicy` + `CommandPaletteMath` (single shared `services/FuzzyMatch.kt`). Tests: positive typo cases ("ntebook"→Notebook), negative noise cases, ranking order preserved (exact beats fuzzy).

3. **Plugin Store is a hidden feature.** Sole entry: Home ⋮ MaintenanceMenu among ~18 items (`HomeScreen.kt:2806-2810`); meanwhile empty capability menus are dead ends — "No text-transform plugins installed" with no link (`MarkdownPreviewScreen.kt:600-605`) and unserved capability sections silently hide (`:704-717`).
   **Fix:** append "Browse Plugin Store…" as the last item whenever a capability list renders empty (flag/callback to open `PluginStoreDialog` from MarkdownPreviewScreen), and add a "Plugin Store" quick-action to the Command Palette action catalog (`NoteflowViewModel.runPaletteAction` path).

## DoD
`gradle assembleDebug` green; `testDebugUnitTest` green incl. FuzzyMatchTest + RecentSearchPolicyTest; `workspace/phase-206/REPORT.md` naming every touched menu site. No schema change, no new dependencies, no workflow edits.
