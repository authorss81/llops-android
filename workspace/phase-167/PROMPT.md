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