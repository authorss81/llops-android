# Phase 31: False-implementation audit & general audit (non-security) [DONE]
You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app. Phase 30 audits SECURITY; this phase audits EVERYTHING ELSE: false/claimed
implementations, broken wiring, dead UI, and general code quality. The owner
wants an honest accounting of what is REAL versus what is claimed.

Your deliverable is **`docs/general-audit-report.md`** (create fresh; append if
present). Write findings INCREMENTALLY and commit/push as you go.

## Part A — False-implementation audit (honesty gate)
For every feature the app claims (in `ROADMAP.md`, `AGENTS.md`, `README.md`,
`docs/`, phase `PROMPT.md`/`REPORT.md`, changelog), verify against the code with
`file:line` evidence whether it is:
- **REAL** — wired, called, works, reachable in UI,
- **STUB/FAKE** — looks functional but does nothing (e.g. returns hardcoded
  values, no-op, dead code path),
- **BROKEN** — wired but fails or is unreachable,
- **CLAIM-ONLY** — documented but no implementation exists,
- **DEAD** — implemented but never reachable from any UI entry.
Cover every area: canvas/brushes, AGSL wet-mixing, WebDAV E2EE sync, plugins
(OCR/web search/export/etc.), stickers, LocalSend, dictation, TTS, translation,
assistant, encryption, import/export, markdown preview, knowledge graph,
templates, voice notes, performance fixes, release signing. Update
`ROADMAP.md`/`AGENTS.md` claims that are FALSE (correct them honestly) — never
leave a known false claim standing.

## Part B — General audit (non-security quality)
- Dead code / unused imports / unused functions (grep-verify, not vibes).
- Performance smells (main-thread I/O, per-frame allocations, missing
  `remember`/`derivedStateOf`, recomposition hotspots, large lists).
- Correctness smells (swallowed exceptions, `!!` on nullable, silent
  fallbacks, magic numbers).
- UX reachability: every feature reachable within ≤3 taps; no orphan UI.
- Test quality: tests that assert nothing, or test the wrong thing.
- Docs consistency between files.
For each finding: `file:line`, category, suggested fix, priority. Prefer
documenting; only fix trivial things (typos, dead import) this phase.

## Part C — FIX these three known startup-crash bugs (owner-provided, verified)
These crash the app on cold start and MUST be fixed this phase (they block all
later phases from running against a live app). Fix each with `file:line`
verification + a test or build pass.

1. **Uninitialized-property NPE in `NoteflowViewModel` construction.**
   `app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt`:
   the `init {}` block (line ~204) calls `refreshPluginStates()` (line ~228) which
   writes `_pluginEnabledIds.value`, `_pluginStates.value`, `_pluginDiagnostics.value`,
   `_storeRows.value` — but those `MutableStateFlow` properties are declared BELOW
   the `init` block (lines ~231–275), so they are still null when the init runs,
   producing a fatal `NullPointerException: Attempt to invoke interface method
   'void kotlinx.coroutines.flow.MutableStateFlow.setValue(...)' on a null object
   reference` during `ViewModelProvider` instantiation. Fix: reorder so all
   `MutableStateFlow` backing properties are initialized BEFORE any init-block code
   that writes them (move the property declarations above the `init {}`, or move
   the `refreshPluginStates()` call into a second `init` after the properties).
   Add a unit test (or compose-free construction test) that instantiates the
   ViewModel and asserts no NPE and that the four flows are non-null.

2. **`android:extractNativeLibs="false"` breaks native libs on SDK 36.**
   `app/src/main/AndroidManifest.xml` line 14 sets `android:extractNativeLibs="false"`,
   forcing Android to memory-map `.so` files (e.g. SQLCipher) straight from the APK.
   On target SDK 36 / modern runtimes with strict page-alignment (16KB/4KB) this
   causes `dlopen` failures (`UnsatisfiedLinkError`) and immediate cold-start death.
   Fix: set `android:extractNativeLibs="true"` (or remove the attribute so the
   default applies), and verify AGP doesn't re-add it via `useLegacyPackaging`.
   Verify by building `assembleRelease` and confirming `.so` files extract properly
   (and note whether the app launches — via emulator if available, else document
   why the fix is correct).

3. **Unchecked `KeyStore` key casting in `SecurityService`.**
   `app/src/main/kotlin/com/authorss81/noteflow/services/SecurityService.kt` line 26:
   `val entry = keyStore.getEntry(alias, null) as KeyStore.SecretKeyEntry` — an
   unchecked cast that throws `ClassCastException` (crashing DEK init on startup)
   if the stored entry is not a `SecretKeyEntry` (e.g. a private-key entry, a
   `TrustedCertificateEntry`, or a migrated/legacy entry) on some devices or
   emulators. Fix: inspect the entry type first (`when (keyStore.getEntry(...))`),
   handle the non-`SecretKeyEntry` case gracefully (clear the invalid entry and
   re-create the DEK, or fail with a recoverable error — never crash startup), and
   add a unit test covering the wrong-entry-type path with a fake key store seam.

## Part D — Note templates (implement or enhance)
The app currently has CANVAS PAPER templates only (`template` field on pages:
`blank`/`lined`/`grid`/`dots`, see `data/model/Entities.kt` ~line 45,
`ImportExportService.drawTemplateBackground`, and `NoteRepository`). Add a proper
**note-template** capability OR enhance the existing one:
- Implement **note-content templates**: a curated set (e.g. Daily Journal, Meeting
  Notes, To-Do, Book/Article Notes, Gratitude, Brain Dump) that pre-fill a new
  note with structured Markdown when creating a note, plus **user-defined
  templates** (save any note as a template; apply template to a new note). Reachable
  from the note-creation flow (HomeScreen/new-note menu) in ≤2 taps.
- Keep the existing canvas paper-template feature working; do not regress it.
- Pure-JVM tests: template save/apply round-trip, variable/placeholder substitution
  (e.g. date), user-template persistence (no new DB schema — use existing prefs or
  a new table only if the design truly requires it, then a migration-safe note).
- If you judge the existing template system already covers this, ENHANCE it instead
  (more templates, nicer picker, placeholders) and document the decision with
  `file:line` evidence.

## Definition of done
- `docs/general-audit-report.md` written: verdict per feature
  (REAL/STUB/FAKE/BROKEN/CLAIM-ONLY/DEAD) with `file:line`, plus a Part-B
  findings section with priorities.
- **Part C: all three startup-crash bugs FIXED** with `file:line` evidence in
  REPORT.md (NoteflowViewModel init order, extractNativeLibs, SecurityService
  cast), each verified by test or build.
- **Part D: note templates implemented or existing system enhanced** with pure-JVM
  tests and evidence in REPORT.md.
- False claims in `ROADMAP.md`/`AGENTS.md`/`README.md` corrected honestly.
- A summary table: count per verdict, top broken/dead items.
- `gradle assembleDebug` + `gradle testDebugUnitTest` pass.
- No finding lives only in a reply; everything is in the file.

## Constraints
- Security findings belong in Phase 30's `docs/security-report.md` — do NOT
  duplicate them here (cross-reference if needed).
- Do NOT change the DB schema unless Part D genuinely requires it (then a
  migration-safe note is mandatory) — prefer existing prefs/storage. Do NOT edit
  `.github/workflows/`. No new deps without justification.
- Do NOT refactor subsystems beyond the three Part-C fixes (AGENTS.md: major
  architectural change requires user approval).
- Be brutally honest: label fakes as fakes. The goal is a clean, truthful
  picture that later phases can act on.
