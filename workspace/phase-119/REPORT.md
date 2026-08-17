# Phase 119 Report — Round-2 Triage → Generate Next Phases

- **Date:** 2026-08-17
- **Acknowledged tree:** current `main` (phase-118 is `.blocked`, phases 120-133
  pre-seeded in commit `7a4b6e6` / renumbered `c3a1789`).
- **Commit built/audited sources:** `docs/security-report-round2.md` (43 findings),
  `docs/phase-status.md`, `docs/ARCHITECTURE.md`, `AGENTS.md`.

## What this phase did

Read all 43 round-2 findings, grouped them into 20 fix bundles by shared root
cause/area theme, added 5 product feature phases, created 25 new phase
directories each with a PROMPT.md + `.timeout` (180 min), triaged every finding
to a phase in `docs/security-report-round2.md`, and updated
`workspace/PHASES.md` + `docs/phase-status.md`.

## Numbering decision

The PROMPT instructs "find the max phase-NN, start at max+1". At generation time
the highest existing directory is **phase-133** (`git ls-tree` onsite), so new
phases start at **134**. The 120-133 directories were pre-seeded
user-requested UI/UX feature phases (commit `7a4b6e6`, renumbered by
`c3a1789`) and are left untouched — they run before 134+ in numeric order.
Round-2 findings are NOT re-numbered into 120-133; they map to the new fix
phases 134-153. Feature phases are 154-158.

## Triage table (finding → fix phase)

| Finding | Sev | Fix phase |
|---|---|---|
| R2-B1A-01 (un-guarded write paths) | MEDIUM | 134 |
| R2-B1A-02 (search not cancelled on lock) | MEDIUM | 134 |
| R2-b2b1-UI-01 (read-side loads vs lock) | MEDIUM | 134 |
| R2-B1D-02 (empty/invalid restore) | MEDIUM | 135 |
| R2-b2b1-UI-03 (restore re-entrancy) | LOW | 135 |
| R2-b2b1-UI-06 (recovery state not saveable) | LOW | 135 |
| R2-B1D-01 (tamper baseline cadence) | MEDIUM | 136 |
| R2-B1D-05 (exportBackup torn copy) | LOW | 137 |
| R2-B1D-03 (LocalSend no checkpoint) | LOW | 137 |
| R2-B1D-04 (in-heap restore decrypt) | LOW | 138 |
| R2-B1P-01 (clipboard-scrub coverage) | MEDIUM | 139 |
| R2-B1A-03 (ON_PAUSE exposure) | LOW | 140 |
| R2-b2b1-UI-02 (dialog FLAG_SECURE) | LOW | 140 |
| R2-B1P-05 (share-confirm above lock) | INFO | 140 |
| R2-B1P-02 (cancelled export staging) | LOW | 141 |
| R2-B1P-03 (export, no chooser) | LOW | 141 |
| R2-b2b3-LOG-04 (note title in EXTRA_SUBJECT) | LOW | 141 |
| R2-B1N-01 (LocalSend uncapped reads) | LOW | 142 |
| R2-B1P-04 (copyBounded idle guard) | LOW | 142 |
| R2-B1N-05 (allow-list port blind) | INFO | 142 |
| R2-B1N-04 (Web Capture http) | LOW | 143 |
| R2-B1N-02 (DNS rebinding) | LOW | 144 |
| R2-B1N-03 (plugin egress scan bypass) | LOW | 144 |
| R2-B1C-02 (WebDAV creds silently deleted) | LOW | 145 |
| R2-B1C-03 (DEK hex residue) | INFO | 145 |
| R2-b2b2-DEP-02 (toolchain unverified) | LOW | 146 |
| R2-b2b2-DEP-04 (lockfile TOFU, unfiltered central) | LOW | 146 |
| R2-b2b2-DEP-03 (stale POM-only entries) | INFO | 146 |
| R2-b2b2-DEP-01 (CI actions unpinned) | MEDIUM | 147 (USER APPROVAL REQUIRED) |
| R2-b2b3-LOG-01 (e.message in restore/backup UI) | LOW | 148 |
| R2-b2b3-LOG-02 (VoiceNoteManager logcat) | LOW | 148 |
| R2-b2b3-LOG-03 (ProtobufBrushLoader echo) | INFO | 148 |
| R2-b2b4-DOS-01 (note_versions unbounded) | MEDIUM | 149 |
| R2-b2b4-DOS-02 (live-layer bitmaps) | MEDIUM | 150 |
| R2-b2b4-DOS-03 (minimap per-frame re-walk) | LOW | 150 |
| R2-b2b5-FEA-04 (dynamicPageCount) | LOW | 150 |
| R2-b2b5-FEA-02 (MarkdownInlineMath quadratic) | MEDIUM | 151 |
| R2-b2b5-FEA-03 (hybrid editor re-tokenize) | MEDIUM | 151 |
| R2-b2b5-FEA-01 (knowledge-graph edges) | MEDIUM | 152 |
| R2-b2b5-FEA-05 (palette lowercase) | LOW | 152 |
| R2-b2b5-FEA-06 (waveform non-finite) | LOW | 152 |
| R2-b2b1-UI-04 (snackbar channel vs lock) | LOW | 153 |
| R2-b2b1-UI-05 (voice discard on lock) | LOW | 153 |

All 43 findings → a fix phase. None marked `resolved at triage` (all 43 remain
open in the current tree; the round-1 cross-links in `docs/security-report.md`
are untouched).

## Fix-phase rationale (bundles)

- **134** — one theme: `lock()` disposing the SQLCipher pool under in-flight
  coroutines (write guard + search cancel + read-side guard = one coherent
  change with one shared helper).
- **135** — restore-flow robustness: invalid/empty input acceptance, re-entrancy
  race, saveable recovery state.
- **136** — single MEDIUM, a distinct DB-integrity design defect.
- **137** — two DB-file-copy consistency bugs (both checkpoint-first
  discipline).
- **138** — single LOW, a large isolated streaming-rewrite job.
- **139** — single MEDIUM clipboard-coverage gap.
- **140** — vault-content exposure windows (overlay covers, dialog flags,
  share-confirm above lock).
- **141** — export/share hygiene (staging, chooser, metadata echo).
- **142** — stream/network I/O (capped readers, idle guard, port-aware
  allow-lists).
- **143** — single LOW Web-Capture cleartext policy.
- **144** — deep network boundary (DNS rebinding + plugin egress/exec scan).
- **145** — credential & key hygiene.
- **146** — supply-chain posture, workflow-independent parts (workflow edits
  deferred to 147).
- **147** — CI action pinning, flagged **USER APPROVAL REQUIRED** (edits
  `.github/workflows/`; without approval ships only the gap report + Dependabot
  config).
- **148** — one theme: raw `e.message` surfaces.
- **149** — single MEDIUM `note_versions` growth + wholesale-decrypt.
- **150** — canvas memory & render budget (layers, minimap, page count).
- **151** — markdown main-thread perf (two quadratic/incremental flaws).
- **152** — feature-data bounds fed by crafted/corpus input.
- **153** — post-lock UI message channels.

## Feature phases (product, 2-3 related features each)

- **154** — Knowledge graph & wiki navigation power-ups (node peek + click-to-
  open, per-notebook/cluster subgraph filter, backlinks breadcrumb + unlinked
  mentions), drawing on the phase-38 foundations.
- **155** — Canvas & brush workshop (two-finger undo/redo + quick-color ring,
  `.inkbrush` brush-preset import/export that wires the dormant
  `ProtobufBrushLoader` — the predicted caller for R2-b2b3-LOG-03).
- **156** — Onboarding, empty states & first-run polish (one-time intro for
  passwordless vaults, honest CTAs on every empty surface, home glanceable
  stats + last-backup nudge).
- **157** — Plugin ecosystem & store UX (capability browser, update UX with
  compile-time-pinned release notes + update-all, per-plugin diagnostics) —
  from `docs/PLUGINS.md` + `PluginCapability.kt`.
- **158** — Reading/focus mode + share-sheet capture polish + lightweight home
  widget (deferred ROADMAP 22.5); base-APK-size rule respected (widget is a
  launcher shortcut or a downloadable plugin).

All avoid collision with the pre-seeded 120-133 topics (scrollable menus, brush
edges, rainbow colours, immediate apply, erasers, tutorial, plugin defaults/
descriptions, typography, ink bar/minimap, FLAG_SECURE pin, metadata alignment,
palette header, FAB/daily-note).

## UI/UX + plugin idea list

- Focus/reading mode, quick-node-preview, cluster subgraph filters, breadcrumbs
  (154, 158).
- Two-finger canvas gestures, quick-color ring, brush-preset ecosystem (155).
- First-run triage, empty-state CTAs, home glanceable stats (156).
- Capability browser, update-all + release notes, diagnostics (157).
- Downloadable-widget/quick-capture path flagged per base-APK rule (158).

## Files created/changed

- `workspace/phase-134/…158/PROMPT.md` + `.timeout` (180) — 25 new phases.
- `docs/security-report-round2.md` — Batch-1 + Batch-2 quick-lookup tables and
  all 43 finding detail `Status` lines now point at their fix phase.
- `workspace/PHASES.md` — rows for 120-133 pre-seed, 134-153 fixes, 154-158
  features.
- `docs/phase-status.md` — rows 118 (BLOCKED), 119 (this phase), 120-158
  (`NOT STARTED`).

## Constraints honored

- No app code changed; no `.github/workflows/` edits; no DB schema change; no
  new dependencies.
- Every generated fix phase carries the repo PROMPT constraints (no workflow
  edits, no schema changes without approval, base-APK-size rule, never log
  decrypted content, keep the security model intact).
- phase-147 explicitly flagged `USER APPROVAL REQUIRED`.
- App-only CLI commands were used for verification of file:line anchors; no
  build was required for a planning-only phase.

## Out of scope / known

- phase-118 (Kali dynamic pentest) remains `.blocked` — the 43-finding triage is
  based on the source audit (phase-116) set.
- Some `file:line` anchors quoted in the prompts are the audit's pinned lines
  (audit commit `c813c99`); line numbers may drift at execution time — prompts
  name functions + a fallback anchor so execution can re-pin.