# Phase 128: Restore horizontal floating ink bar + aspect-correct minimap [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/phase-status.md` and
`docs/ARCHITECTURE.md` first.**

**THE BUG (owner-confirmed):** the floating ink/tool bar is now **vertical
instead of horizontal** and the **minimap size is wrong** — both regressed in
phase-35 (`7b0507b`), which replaced the old horizontal `FloatingBottomToolbarPill`
with the always-vertical `FloatingToolDock` and a fixed 120×140dp minimap HUD
in `AnnotationCanvas.kt`.

## What to do
- **Restore the horizontal pill as the default posture**: in portrait, the
  floating tool bar must be the old **horizontal bottom capsule**
  (`Row`, 56dp tall, rounded 28dp, `surfaceContainerHigh`, tonal 6dp/shadow
  8dp, auto-hides while drawing) — per the pre-phase-35 design
  (`git show 7b0507b^:.../ui/screens/EditorScreen.kt`). In landscape it may
  stay vertical on the side (that was the old behavior: `isLandscape` →
  side Column).
- Keep any genuinely useful phase-35/36 additions **only if they do not break
  the horizontal default** — e.g. snap-to-edge and dock persistence become
  **optional settings**, default OFF (restore-beautiful-first; do not bury the
  simple horizontal bar behind configuration).
- **Fix the minimap**: replace the fixed 120×140dp box with a minimap whose
  size is **proportional to the page aspect ratio** (fit within a max box,
  preserving aspect) and whose pan/viewport mapping matches the actual canvas
  (including seamless/infinite mode — see the existing "minimap must agree"
  comment in `AnnotationCanvas.kt`). Keep the collapsible header.
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
  max-box clamping), dock posture decision (portrait → horizontal).
- `gradle testDebugUnitTest` + `gradle assembleDebug` must pass (or a
  documented pre-existing-only failure).

## Definition of done
- Portrait shows the old horizontal floating pill; landscape keeps a side
  vertical posture; snap-to-edge/dock extras are opt-in settings default OFF;
  minimap is aspect-correct with working pan/viewport mapping.
- `workspace/phase-128/REPORT.md` committed with file:line evidence
  (before/after).

## Constraints
- NO DB schema change. Do NOT edit `.github/workflows/`. Do not add new
  dependencies. Never log keys/decrypted content. Keep `allowBackup=false`,
  `ClipboardGuard`, FLAG_SECURE intact. Low-end safe (no per-frame
  allocations).