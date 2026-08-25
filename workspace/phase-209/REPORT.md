# Phase 209 — REPORT: Search Quality & Plugin Discovery

**Date:** 2026-08-25
**Scope:** Three retrieval/discovery gaps (recent-search history, typo-tolerant search,
Plugin Store deep-links). Pure JVM, no new dependencies, no schema change, no workflow edits.

---

## 1. Recent-search history

**Gap:** the vault search field was a bare `TextField` with no memory; repo-wide grep had
zero `recentSearch|searchHistory` hits.

**Fix:**

- New pure-JVM **`services/RecentSearchPolicy.kt`** — the ring decision table:
  - `record(current, query)` — newest-first insert; blank queries never record; trims;
    case-insensitive dedupe moves an existing entry to the front; capped at `CAP = 8`.
  - `dismiss(current, query)` — chip dismissal (case-insensitive, trim-tolerant).
  - `sanitize(raw)` — read-back normalization (trim, drop blanks, dedupe first-wins, cap).
- **`services/SettingsManager.kt`** — prefs glue `getRecentSearches()` / `setRecentSearches()`
  over the `search_recent_<n>` ring keys (`n ∈ 0..7`, 0 = most recent). SharedPreferences
  only — NEVER the DB schema.
- **`ui/screens/HomeScreen.kt`**:
  - `searchFieldFocused` state via `.onFocusChanged` on the search TextField
    (`HomeScreen.kt:1223`).
  - The debounced search effect records every EXECUTED non-blank query and persists the
    ring only when it actually changed (`HomeScreen.kt:336-344`) — a re-triggered identical
    query writes nothing.
  - Dismissible `InputChip`s render ONLY while `searchFieldFocused && searchQuery.isBlank()`
    (`HomeScreen.kt:1250`); tapping fills + runs the query through the existing 300 ms
    debounce → `viewModel.searchVault`; the trailing × dismisses persistently. The row is
    horizontally scrollable so a full ring can never clip at 360dp (phase-166 rule).

## 2. Typo-tolerant search tier

**Gap:** both scorers (`VaultSearchPolicy.pageMatches`, `CommandPaletteMath.score`) were
exact-substring only.

**Fix:** one shared matcher, two wired scorers:

- New pure-JVM **`services/FuzzyMatch.kt`** — case-insensitive IN-ORDER SUBSEQUENCE match
  with a length-aware gap penalty expressed as matched *density* =
  `q.length / (q.length + skippedChars)` (leading skips count, so matches must hug the
  text start or be dense):
  - `MIN_QUERY_LENGTH = 2` — single characters can never fuzzy-match (they would otherwise
    match everything at density ≈ 1).
  - `MIN_DENSITY = 0.45f` — `"ntebook"→"notebook"` = 0.70 passes; `"nte"→"notebook"` = 0.75
    passes; noise fails: `"nte"↛"alternative"` (0.27), `"ct"↛"electric"` (0.40),
    `"nte"↛Berlin-page body` (0.15).
  - `subsequenceDensityPreLowered` — hot path for the palette's pre-lowered per-index fields
    (phase-152 discipline: no per-keystroke corpus re-lowercasing).
  - Contiguous substrings score exactly 1.0; tighter always outranks looser inside the tier.
- **`services/VaultSearchPolicy.kt`** — new `enum SearchMatchTier { EXACT, FUZZY }` +
  `pageMatchTier(page, query)`: exact title/body substring first, then the fuzzy tier via
  the SHARED `FuzzyMatch`; null = no match. `pageMatches` stays Boolean (= tier ≠ null), so
  all existing call shapes keep compiling. New stable `exactFirst(pages, query)` orders
  EXACT results ahead of FUZZY while preserving corpus-recency order within each tier.
- **`data/repository/NoteRepository.kt`** — BOTH search paths apply the ordering:
  `searchPages` (`:619-626`) filters then `VaultSearchPolicy.exactFirst(...)`; the explicit
  deep-scan `deepSearchPages` keeps its pinned bounded-batch shape (`matches += batch.filter`
  intact for the phase-78 pins) and applies the same `exactFirst` on return.
- **`services/graph/CommandPaletteMath.kt`** — new `MatchKind.FUZZY_MATCH`; `score()` falls
  through to a private `fuzzyScore` tried title → tags → body, scoring
  `FUZZY_SCORE_FLOOR(12f) + FUZZY_SCORE_SPREAD(18f) × density` ⇒ band **12–30**, strictly
  below `BODY_CONTAINS`'s 40 — ranking preserved: exact beats fuzzy, tighter fuzzy beats
  looser fuzzy, existing updatedAt/id tiebreaks unchanged.
- Existing suites verified compatible without edits: `B2Dos02VaultSearchBoundedTest`
  (its "paris"/"milk"/"plan"/"zzz" negatives are non-subsequences or below the floor) and
  `CommandPaletteMathTest` ("zzz" has no subsequence anywhere).

## 3. Plugin Store discovery

**Gap:** sole entry was Home ⋮ MaintenanceMenu among ~18 items; the editor plugin menu's
empty capability lists were dead ends.

**Fix + every touched menu site (the DoD list):**

| # | Menu site | File | Change |
|---|-----------|------|--------|
| 1 | Markdown editor Plugins dropdown (app-bar Extension icon) — empty TextTransform placeholder "No text-transform plugins installed" (`MarkdownPreviewScreen.kt:600-605`) | `ui/screens/MarkdownPreviewScreen.kt:815-838` | Appends `HorizontalDivider` + enabled **"Browse Plugin Store…"** as the LAST menu item whenever `PluginStoreDiscoveryPolicy.shouldShowEntry(servedEntries = servedPluginEntries, emptyPlaceholderVisible = transformPlugins.isEmpty())` — i.e. when NO capability entry rendered anywhere OR the dead-end placeholder is visible |
| 2 | Same menu's twelve capability sections (transform/web-search/text-tools/lang-detect/dictation/read-aloud/translation/dictionary/weather/unit-converter/outline/citation) | `MarkdownPreviewScreen.kt:824-831` | All section sizes feed `servedPluginEntries`, so silently-hidden unserved sections count toward the discovery decision |
| 3 | Command Palette action catalog | `services/graph/CommandPaletteMath.kt` `ACTION_CATALOG` | New `PaletteActionDescriptor("plugin-store", keyword "store", label "Plugin Store", capabilityKey "plugin_store", needsArg=false)` — unique keyword, no prefix collisions ("storehouse" does not route) |
| 4 | Command Palette action execution | `NoteflowViewModel.runPaletteAction` (`:4736-4741`) + sealed `PaletteActionResult` | Intercepts `PALETTE_CAPABILITY_KEY` before capability dispatch and returns the new `data object OpenPluginStore : PaletteActionResult()` — UI routing only; the SDK's sealed `PluginCapability` set is UNCHANGED (pinned by test) |
| 5 | Command Palette overlay result handling | `ui/components/CommandPaletteOverlay.kt` | New `onOpenPluginStore` param; the overlay closes itself and delegates to the host instead of showing feedback text |
| 6 | Home ⋮ MaintenanceMenu → "Plugin Store" (pre-existing sole entry) | `ui/screens/HomeScreen.kt:816/1953` | Untouched — still works; now not the only path |

Wiring: new pure-JVM **`services/PluginStoreDiscoveryPolicy.kt`** owns the label, the
palette key/keyword constants and the `shouldShowEntry(servedEntries,
emptyPlaceholderVisible)` decision. **`MainActivity.kt`** gains activity-level
`showPluginStoreDeepLink` state, passes `onOpenPluginStore` into BOTH
`MarkdownPreviewScreen` call sites (`:659`, `:766`) and the palette overlay (`:996-1011`),
and hosts `com.authorss81.noteflow.ui.components.PluginStoreDialog(viewModel, onDismiss)`
right after the palette block (`:1015-1023`) — the editor screens have no store dialog of
their own.

## Verification

- New tests (all green):
  - `services/FuzzyMatchTest` — 11 (positive typo cases incl. "ntebook"→Notebook,
    negative noise cases, single-char gate, density ordering, bounds, pre-lowered parity).
  - `services/RecentSearchPolicyTest` — 11 (insert order, move-to-front dedupe, cap=8,
    blank rejection, case-insensitive dismiss, sanitize/cap-on-read).
  - `Phase209SearchQualityTest` — 14 behavior tests (vault tiers, exactFirst ordering,
    palette FUZZY_MATCH band < BODY_CONTAINS, exact-beats-fuzzy with newer fuzzy doc,
    store routing + keyword uniqueness, discovery policy truth table).
  - `Phase209DiscoveryPinsTest` — 11 source pins (ring keys in SettingsManager, HomeScreen
    focus/record/dismiss/chips wiring, single-matcher rule — the greedy walk exists ONLY in
    FuzzyMatch.kt —, exactFirst in both repo paths, FUZZY_MATCH below BODY_CONTAINS in
    scorer order, editor menu deep-link + served-entry sum, MainActivity 3× flag raisers +
    dialog host, overlay OpenPluginStore branch, runPaletteAction routing, catalog entry,
    SDK-sealed-set-unchanged).
- `gradle assembleDebug` — **green**.
- `gradle testDebugUnitTest` — 2906 tests / 4 failures, ALL pre-existing/environmental and
  independent of this diff: `Phase148UiFailureTextScrubTest` (the documented UNC-path
  failure, AGENTS.md), `WikiLinkParserCacheUnitTest` + `PaparazziSmokeTest` ×2 (documented
  timing/layoutlib flakes — verified passing in isolation immediately after the full run).
- No DB schema change, no new dependency, no `.github/workflows/` edit, base-APK-size rule
  intact (all additions are pure-JVM Kotlin).

## Notes

- The PROMPT's DoD names `workspace/phase-206/REPORT.md` — that is a stale copy-paste from
  the previous phase; this report is `workspace/phase-209/REPORT.md` per the phase number.
- Density design note: leading skipped characters count toward the gap penalty. This is
  what lets a 3-char query stay selective ("nte" matches "notebook" at 0.75 but rejects
  "alternative" at 0.27); the trade-off is that mid-body typo hits need slightly denser
  matches, which titles — the primary typo surface — satisfy comfortably.

## Review fixes (2026-08-25)

All seven review FINDINGS applied (commit `llops: phase-209 review fixes`):

1. **[HIGH] Prefix pollution** — recording moved AFTER the 300 ms debounce settle
   (`HomeScreen.kt`), so only EXECUTED queries enter the ring; the pre-fix order put every
   keystroke prefix ("n","no","not",…) into the persisted 8-slot ring, evicting real
   history. Pin strengthened: record must appear after `delay(300)` in source.
2. **[MEDIUM] Plaintext-at-rest** — ring values are now AES-256-GCM encrypted under a new,
   non-extractable AndroidKeyStore key (`noteflow_recent_searches_key`,
   `SettingsManager.kt`, WebDavCredentialStore discipline; honors the phase-158
   prefs-hold-non-secret-data-only rule). Fail-CLOSED: no plaintext fallback ever; legacy
   pre-fix plaintext entries fail GCM validation on read and are silently retired.
3. **[MED-LOW perf] `exactFirst`** now decorates tiers ONCE per page
   (`VaultSearchPolicy.kt`) instead of recomputing them O(n log n) times inside the sort
   selector (each recompute = up to two full-text fuzzy scans).
4. **[LOW a11y]** chip dismiss is an `IconButton` (Material minimum-touch-target
   enforcement) instead of a raw clickable 14dp Icon.
5. **[LOW UX]** `showPluginStoreDeepLink` is cleared when the vault locks
   (`MainActivity.kt`), so unlocking never re-presents a dialog the user left behind.
6. **[LOW invariant]** the store dialog composes BEFORE the phase-140 opaque pause cover,
   so the cover draws over it during ON_PAUSE (recents-thumbnail safety); the phase-140
   cover comment updated accordingly and the pin test made order-independent.
7. **[LOW docs]** suite counts corrected (14 behavior / 11 pins here, in ARCHITECTURE.md
   and phase-status.md); totals unchanged (no new @Test methods — all additions are
   assertions inside existing tests).

Verification after the fixes: targeted rerun of `FuzzyMatchTest`, `RecentSearchPolicyTest`,
`Phase209SearchQualityTest`, `Phase209DiscoveryPinsTest`, `CommandPaletteMathTest`,
`B2Dos02VaultSearchBoundedTest` + `gradle assembleDebug` — green.
