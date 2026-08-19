# Phase 156 — Onboarding, empty states & first-run polish

**Status:** DONE (implemented + reviewed; `gradle testDebugUnitTest` + `gradle assembleDebug` green)

## What shipped (three features, deeply bundled)

### Feature 1 — first-run onboarding (passwordless vaults)

- New pure-JVM `app/src/main/kotlin/com/authorss81/noteflow/services/OnboardingPolicy.kt`:
  - `shouldAutoShow(isFirstRun, hasMasterPassword, onboardingCompleted)` — the single tested gate; auto-shows
    ONLY on first install + passwordless vault + not yet completed.
  - 3 `OnboardingStep`s (Create notes fast / Draw on ink canvas / Plugins & backup) + the privacy-stance
    constants (`PRIVACY_STANCE_TITLE` = "Your vault stays on this device", body = offline/encrypted stance).
- `SettingsManager.onboardingCompleted` (default `false`) — distinct from `isFirstRun`/`tutorialCompleted`,
  so the phase-125 tutorial and this first-run triage never double-arm.
- `ui/components/FirstRunOnboarding.kt` `FirstRunOnboardingSheet`: non-blocking `ModalBottomSheet`, privacy
  banner first, 3 instant steps with dot indicators, per-step CTAs (`onCreateNote`/`onOpenPluginStore`/
  `onOpenWebDav`), Skip/Close/Get-started all dismiss once. **No animated transitions** between steps —
  reduce-motion satisfied by construction.
- `HomeScreen.kt` wiring:
  - `shouldAutoShowOnboarding = remember(...) { OnboardingPolicy.shouldAutoShow(isFirstRun, hasMasterPassword, onboardingCompleted) }`
  - `showTutorial` now also requires `!shouldAutoShowOnboarding` (never stack).
  - ⋮ `MaintenanceMenu` gains "Show help again" (after "Interactive Tutorial") → `showOnboarding = true`, so
    the guide stays reachable even after a master password is set.

### Feature 2 — empty states everywhere

- `ui/components/EmptyStateKit.kt`:
  - new kinds `RECENT`, `KNOWLEDGE_GRAPH`, `VERSION_HISTORY`, `WEB_SEARCH`;
  - nullable `actionLabel` on `EmptyStateDecision` — exactly ONE CTA per empty surface;
  - `PLUGIN_STORE` is now query-aware ("No plugin matches" + **Clear filter** vs "Nothing in the store");
  - `HOME_GRID` returns CTAs ("Clear search" / "Create your first note" / "Create a note");
  - `KNOWLEDGE_GRAPH` teaches: "Create a wikilink to start mapping — write `[[Another note]]` inside any note…".
- `ui/components/EmptyStateArt.kt`: `IllustrationKind.HISTORY` + `drawHistory` (clock face + restore arc + hands);
  fixed a mangled `drawPuzzle` signature line.
- Wired call sites:
  - `HomeScreen.kt`: Recent tab → `RECENT`, Trash tab → `TRASH`, grid → `HOME_GRID`; single CTA (Clear search vs create page).
  - `KnowledgeGraphScreen.kt`: `graphLoaded` flag set after the decrypt/kept pass so the empty overlay never
    flashes pre-load; zero-nodes → `KNOWLEDGE_GRAPH` with "Create a note" CTA → `addPage("New Page", onCreated = onOpenPage)`.
  - `TagExplorerView.kt:88` → `TAG_VAULT` (already wired, untouched).
  - `VersionHistoryBottomSheet.kt`: `VERSION_HISTORY` (informational, no CTA — honest surface).
  - `WebSearchDialog.kt`: new `SearchStage.NoResults(query)` (empty results no longer miscast as an Error);
    "New search" CTA clears the query.
  - `PluginStoreDialog.kt`: `storeFilter` `OutlinedTextField`; filtered `LazyColumn`; match-less → `PLUGIN_STORE`
    with **Clear filter** CTA.
- Locked-vault safety: every surface stays arms-empty / re-checks `authenticated` before assigning decrypted
  pages into state; no empty-state copy renders decrypted content when locked.

### Feature 3 — home glanceable stats + search polish

- New pure-JVM `services/HomeStatsMath.kt`: `countDistinctWikiLinks(pages)` (dedup on target, bounded per page
  by `WikiLinkParser.MAX_LINKS_PER_PAGE`), `daysSinceBackup` (`null` when never), `backupChip`
  ("No backup yet"/"Backed up today"/"Backup N d ago"), `chips(noteCount, linkCount, lastBackupEpochMs, now)`.
- `NoteflowViewModel.kt`: `onboardingCompleted` + `lastBackupTimestamp` `StateFlow`s (`completeOnboarding()`,
  `refreshBackupTimestamp()`), and `suspend countCachedWikiLinks(): Int` over the cached search corpus on
  `Dispatchers.Default` — **no new DB reads**.
- `ImportExportService.exportBackup` records `SettingsManager.lastBackupTimestamp` at the SINGLE success
  chokepoint — covers the Home menu, WebDAV push (which also calls `refreshBackupTimestamp()`), and LocalSend
  producers with one seam.
- `HomeScreen.kt`: stats chips row after the search/import bar (`n notes · n links · backup`, keyed on
  `currentSearchCorpusGeneration`), plus a "No backup yet — keep your vault safe with an encrypted backup."
  nudge above ⋮ "Backup to File" when `lastBackupTimestamp == 0L`.

## Verification

- `gradle testDebugUnitTest`: **2187 tests, 2186 green** — only the pre-existing documented
  `Phase148UiFailureTextScrubTest` UNC-path failure (untouched; reproduced pre-existing per AGENTS.md).
  A `WikiLinkParserCacheUnitTest` cancel-timing flake appeared in one full-suite run and passed in isolation
  on re-run (same flake already documented at phase-155).
- `gradle assembleDebug`: green (one transient `packageDebug` packaging failure on a first run, succeeded on
  re-run; final `BUILD SUCCESSFUL`, `app-debug.apk` produced).
- New/changed tests:
  - `OnboardingPolicyTest` (gate matrix: first-run × passwordless × completed; 3 non-blank steps; privacy copy).
  - `HomeStatsMathTest` (link counting incl. title links + dedupe, day math incl. never-backed-up, chip states + pluralization).
  - `EmptyStateResolverTest` extended (RECENT/KNOWLEDGE_GRAPH/VERSION_HISTORY/WEB_SEARCH decisions + CTAs,
    PLUGIN_STORE filtered vs unfiltered, HOME_GRID search/first-run/quiet CTAs).

## Constraints honored

- NO DB schema change · `.github/workflows/` untouched · no new dependencies.
- Base-APK cap respected: all new illustration/UI work is drawn in Compose (`drawHistory`), no assets.
- reduce-motion + 48dp touch targets intact (instant step swaps, full-width 48dp+ buttons).
- No keys/passwords/decrypted content logged; no `INTERNET` added.
