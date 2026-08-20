# Phase 187: GalleryView — authentic notebook-paper look for ink-note cards (no generic placeholder) [NOT STARTED]

You are working on **InkFlow/Noteflow**. User visual review: ink canvas notes without
extracted OCR text show a generic pencil icon + "Ink & canvas page" label — an
unpopulated placeholder instead of an authentic sketch page.

Relevant code: `ui/components/GalleryView.kt:184-212`.

Read `docs/ARCHITECTURE.md` and `docs/phase-status.md` first.

## WORKFLOW RULE
Work in small steps; `git add -A && git commit -m "llops: phase-187 step N: <desc>" && git push`
after EVERY step.

## Step 1 - Inventory (commit it)
- Read `ui/components/GalleryView.kt:184-212` (ink placeholder) and how the card
  background is drawn (`scheme.surfaceVariant.copy(alpha=0.45f)` etc.).
- COMMIT this step.

## Step 2 - Paper-texture background
- Replace the generic placeholder with a subtle notebook-paper background: ruled
  lines OR dot-grid pattern behind the ink card (drawn with `drawBehind`, using
  `scheme.outlineVariant.copy(alpha=0.3f)` dots/lines on a paper-toned
  `scheme.surface.copy(alpha=0.7f)` background), a small draw icon, and an honest
  label like "Handwritten note" (or existing label — keep honest copy; never
  claim OCR text exists when it doesn't).
- Keep it cheap to draw (bounded loops, no per-frame allocations); do NOT rasterize
  real strokes (phase-188).
- Respect dark theme: card background must stay distinct from the dark surface
  (see phase-188 border note).
- COMMIT this step.

## Step 3 - Regression proof
- `gradle assembleDebug` green + `gradle testDebugUnitTest` green (except the
  pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure + the 2
  `B1Plat01ReleaseSigningTest` asserts, untouched).
- If a policy class governs the pattern/colors, add pure-JVM tests.

## Definition of done
- Ink-note cards render with an authentic paper texture (ruled/dot-grid), honest
  label, distinct in dark theme, cheap to draw.
  `workspace/phase-187/REPORT.md` before/after + tests.
- Commit + push.

## Constraints
- Do NOT edit `.github/workflows/`. No new dependencies. No DB schema change.
- No per-frame allocation in the draw path; bounded loop counts.