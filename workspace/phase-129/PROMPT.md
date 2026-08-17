# Phase 129: Restore horizontal floating ink bar + aspect-correct, draggable minimap [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/phase-status.md` and
`docs/ARCHITECTURE.md` first.**

**THE BUG (owner-confirmed):** the floating ink/tool bar is now **vertical
instead of horizontal** and the **minimap size is wrong** — both regressed in
phase-35 (`7b0507b`), which replaced the old horizontal `FloatingBottomToolbarPill`
with the always-vertical `FloatingToolDock` and a fixed 120×140dp minimap HUD
in `AnnotationCanvas.kt`.

## What to do
- **Restore the horizontal pill as the default posture — faithful to the
  pre-phase-35 design** (`git show 7b0507b^:.../ui/screens/EditorScreen.kt`,
  `FloatingBottomToolbarPill`). Restore the EXACT look and behavior, not an
  approximation:
  - Portrait: 56dp-tall capsule, `RoundedCornerShape(28.dp)`,
    `surfaceContainerHigh`, `tonalElevation = 6.dp`,
    `shadowElevation = 8.dp`, 1dp `outlineVariant` 50%-alpha border; content =
    `Row` with `horizontalScroll(rememberScrollState())`, padding
    `8/4`, `spacedBy(4.dp)`; anchored `BottomCenter`, `bottom = 20.dp`.
  - Landscape: 56dp-wide side `Column` with `verticalScroll`, `spacedBy(6.dp)`,
    items as listed below with the `HorizontalDivider`; anchored `CenterEnd`,
    `end = 20.dp`.
- **Feature-parity checklist — every pre-35 bar function MUST exist and behave
  identically (do not drop or degrade any)**:
  1. Tool selector button: `displayTool` icon + label (`getToolIcon` +
     `displayTool.label`, `labelMedium`), highlight `primaryContainer` when
     `TOOL_PICKER`; displays `lastDrawingTool` while PAN/SELECT active.
  2. Scroll/Pan toggle: `Icons.Outlined.PanTool` + "Scroll" label,
     highlight when `PAN`/`SELECT` active.
  3. Color swatch: 40dp circle Surface, 24dp inner circle filled with
     `currentColor` + 1.5dp `outline` ring; highlight when `COLOR_PICKER`.
  4. Width badge: `Icons.Outlined.LineWeight` + `"${currentWidth.toInt()}pt"`
     label; highlight when `WIDTH_PICKER`.
  5. `VerticalDivider` (portrait, 24dp) / `HorizontalDivider` (landscape).
  6. Canvas settings: `Icons.Outlined.Tune`, 40dp IconButton, tint
     `primary` when `SETTINGS_MENU`.
  7. Undo / Redo: `Icons.Outlined.Undo`/`Redo`, 36dp IconButtons.
  8. Bar auto-hides while drawing (same trigger as pre-35).
  9. Portrait placement bottom-center (20dp above bottom); landscape side
     column (20dp from end). Nothing else may move or resize the bar by
     default.
- Keep any genuinely useful phase-35/36 additions **only if they do not break
  the horizontal default** — e.g. snap-to-edge and dock persistence become
  **optional settings**, default OFF (restore-beautiful-first; do not bury the
  simple horizontal bar behind configuration). If a phase-35/36 extra replaces
  any item in the parity checklist above, the pre-35 behavior wins by default.
- **Make the ink bar draggable**: the restored floating bar can be **dragged
  to reposition it** anywhere on screen (drag persists for the session), gated
  behind a **settings toggle** (`SettingsManager` boolean, e.g.
  `inkBarDraggable`, default OFF) — when disabled the bar sits at its default
  bottom-center anchor. Dragging must not break tap targets, auto-hide while
  drawing, or the landscape side posture; the bar must not clip outside safe
  insets.
- **Fix the minimap — restore pre-35 defaults + aspect correctness**:
  - **Visibility: OFF by default** (pre-35 behavior — plain toggle in the
    canvas settings sheet, `showMinimap` local state `mutableStateOf(false)`).
    The phase-35 change made it a persisted setting defaulting to **true**
    (`SettingsManager.minimapHudEnabled`, pref `minimap_hud_enabled`); change
    the default back to **false** (prefs getter default `false`) so the
    minimap appears only when the user enables it.
  - Replace the fixed 120×140dp box with a minimap whose size is
    **proportional to the page aspect ratio** (fit within a max box,
    preserving aspect) and whose pan/viewport mapping matches the actual
    canvas (including seamless/infinite mode — see the existing "minimap must
    agree" comment in `AnnotationCanvas.kt`). Keep the collapsible header and
    the existing auto-hide while drawing.
  - Pre-35 placement: bottom-right corner. Keep that as the default anchor.
- **Make the minimap draggable**: the user can **drag the minimap to
  reposition it** anywhere on the canvas; drag state persists for the session.
  The draggable behavior is gated behind a **settings toggle**
  (`SettingsManager` boolean, e.g. `minimapDraggable`, default OFF) — when
  disabled the minimap stays at its default anchor corner. Do not regress
  pan/viewport mapping while dragging (viewport still maps to the page).
- The ink bar must render immediately and apply selections immediately (see
  phase-122 — do not regress it).

## Verification
- Pure-JVM unit tests: minimap aspect-fit math (width/height from page ratio,
  max-box clamping), dock posture decision (portrait → horizontal), draggable
  anchor/position helpers (default anchor vs dragged offset; settings gate),
  minimap visibility default (OFF).
- REPORT.md must include a **parity table**: each of the 9 pre-35 bar items
  (checklist above) mapped to its restored code location (file:line) +
  before/after evidence — proof that no bar function was degraded or dropped.
- `gradle testDebugUnitTest` + `gradle assembleDebug` must pass (or a
  documented pre-existing-only failure).

## Definition of done
- Portrait shows the old horizontal floating pill with ALL 9 parity items
  (tool selector, pan toggle, color swatch, width badge, dividers, settings,
  undo, redo, auto-hide) verified identical to `7b0507b^`; landscape keeps the
  side vertical posture; snap-to-edge/dock extras are opt-in settings default
  OFF; minimap is **OFF by default** (settings default `false`), aspect-correct
  with working pan/viewport mapping when enabled; **both the ink bar and the
  minimap are draggable when their respective settings toggles are enabled**
  (default OFF).
- `workspace/phase-129/REPORT.md` committed with file:line evidence
  (before/after).

## Constraints
- NO DB schema change. Do NOT edit `.github/workflows/`. Do not add new
  dependencies. Never log keys/decrypted content. Keep `allowBackup=false`,
  `ClipboardGuard`, FLAG_SECURE intact. Low-end safe (no per-frame
  allocations).