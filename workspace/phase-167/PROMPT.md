# Phase 167: Bottom navigation bar overlays messages & calendar on mobile — fix dynamically [NOT STARTED]

You are working on **InkFlow/Noteflow**. User feedback: on mobile there is a
"back home" button and another button at the bottom; some messages and the
calendar go below it (hidden behind it). Fix the layout so content is never
covered by the bottom buttons.

Read `docs/ARCHITECTURE.md` and `docs/phase-status.md` first.

## Context
- Screens use `Scaffold` (HomeScreen L550, KnowledgeGraphScreen L322,
  MarkdownPreviewScreen L222, EditorScreen L193-ish) and EditorScreen has its
  own bottom bar/buttons ("back home" + another bottom button).
- The bug: content (calendar in Calendar view — HomeScreen pageViewMode=3 —
  and transient messages/snackbars/toasts) scrolls or renders UNDER the bottom
  button bar and is not visible.
- Root causes to check:
  1. Content not given bottom padding equal to the bottom bar height
     (`Scaffold` `contentPadding`, `imePadding()`, `navigationBarsPadding()`).
  2. Calendar view and message overlays drawn at the bottom of a Column that
     ends behind the bottom bar.
  3. Snackbar/toast anchored behind the bar (`SnackbarHost` placed under it, or
     default toast position).

## Definition of done
- No content is hidden behind the bottom buttons on mobile:
  - Calendar (and all pager/scrollable content) respects the bottom bar height —
    use `Scaffold` content padding / explicit `bottomPadding` so the last row is
    scrollable above the bar, not under it.
  - Messages/snackbars/toasts render ABOVE the bottom bar (or are re-positioned
    dynamically, e.g. `SnackbarHost` at the proper offset).
  - Works on small (360dp) and large screens; the fix is dynamic (does not
    hard-code a fixed pixel height that breaks in landscape or with gesture nav).
- `workspace/phase-167/REPORT.md`: before/after with screen:line evidence of
  every spot that could be covered.
- Commit + push.

## Constraints
- Do NOT edit `.github/workflows/`. Do NOT change navigation logic — only fix
  the layout/padding/insets so nothing is obscured.
- Respect AGENTS.md hardware rule (graceful on low-end devices, gesture nav).

## Part B — Generate the next phases from the Kali round-2 report
After your main task is done and committed, triage `docs/kali-report-round2.md`
(Kali's static security analysis of the release APK, produced by phase-160) and
GENERATE the next phases of the pipeline.

1. Number them DYNAMICALLY after the highest existing `phase-NN` in
   `workspace/` (run `git ls-tree -r --name-only origin/main -- workspace` or
   list `workspace/phase-*`, find the max, start at max+1).
2. CRITICAL/HIGH findings → one fix phase each; MEDIUM/LOW → bundle 2-3 related
   findings per fix phase (same area/theme).
3. **DO NOT duplicate existing phases.** Anything already covered by
   phases 167-174 (bottom-nav overlay, layout-fit/paging, last-notebook, export
   decryption, base-APK size/lingua/ABI, signing v3/plugin channel, editor/
   canvas productivity, FileTransfer-over-LocalSend, reading/authoring UX) or by
   any earlier phase must be marked `already covered by phase-NN` in the triage
   table and must NOT get a new phase. Only genuinely NEW, un-covered findings
   become new phases.
4. Each new phase reuses the repo PROMPT format: `# Phase NN: <title> [NOT
   STARTED]`, context (read docs/ARCHITECTURE.md + docs/phase-status.md first),
   real `file:line` references, Definition of done, Constraints (no
   `.github/workflows/` edits, no DB schema change without user approval, never
   log decrypted content, keep security model intact, base-APK-size rule).
5. Append every new phase to `workspace/PHASES.md` + add rows to
   `docs/phase-status.md` (status `NOT STARTED`).
6. In `workspace/phase-167/REPORT.md`, add a section: the triage table
   (finding → verdict: NEW phase / already covered by phase-NN / deferred) and
   the list of new phases with rationale.
7. Commit + push.

If `docs/kali-report-round2.md` contains only `DYNAMIC-DEFERRED` entries and no
new actionable static findings, say so explicitly in the REPORT and generate NO
new phases (or only clearly-warranted ones).