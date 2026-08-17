# Phase 34: Visual Polish & Material 3 Expressive Design [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app (Kotlin 2.0.21, Compose BOM 2024.12.01, Material 3) that already has a Glass
glassmorphism theme (`theme/GlassSurfaces.kt` — `FrostedGlassSurface`,
`GlassThemeMath`, `theme/Motion.kt`) and Material 3 theming (`theme/Theme.kt`,
`Type.kt`, `Color.kt`, `Shape.kt`). **Read `docs/phase-status.md` first** — prior
phases (24 Glass theme, 27 brush, 28 stickers) set the baseline; do not regress
their behavior.

**THE GOAL:** elevate the app from functional to **world-class visual polish**
with Material 3 Expressive — adaptive glassmorphism, refined typography
hierarchy, and warm tactile empty states. Every change is real, measurable, and
does NOT degrade performance on low-end devices (AGENTS.md hardware rule: never
silent degradation).

## 1. Adaptive Glassmorphism & Tonal Elevation
- Refine `GlassSurfaces` (and any screen that uses `FrostedGlassSurface`): dynamic
  blur gradients, subtle **inner-border luminescence** (0.5 dp inner borders),
  and **tonal surface tinting** that smoothly responds to the system
  dark/light dynamic theme palettes.
- Honor AGENTS.md: on API 33- (no AGSL blur) or low-RAM devices, fall back to a
  solid/tonal surface (no blur) — never crash, never silent degradation. Verify
  `ShaderCapabilityHelper`/capability gating still works with the new surfaces.
- Keep blur scopes tight (small surfaces, not the whole screen) for perf.

## 2. Refined Typography & Hierarchy
- Pair a **clean modern geometric sans** (e.g. Plus Jakarta Sans or Inter — add
  the font, justify the dependency) for system UI and metadata with an
  **editorial serif** (e.g. Newsreader or Lora) for Markdown long-form reading
  mode (`MarkdownPreviewScreen`/editor reading view).
- Ensure consistent **baseline grid alignment** and proportional line-heights
  across `theme/Type.kt`; define clear type scale roles (display/headline/title/
  body/label) used consistently.
- A setting or reading-mode toggle to switch the note body to the serif; default
  remains the sans for UI chrome.

## 3. Tactile Empty States & Hero Illustrations
- Replace blank screens (empty home grid, empty search, new vault, no-plugin
  store) with warm, minimalist **vector artwork** (Compose `Canvas`/`Path`
  drawings — isometric notebooks, pen-nibs, subtle graph nodes — no image assets,
  no network) and **contextual onboarding suggestions** (e.g. "Create your first
  note", "Draw with the pen", "Install the LLM plugin from the store") for newly
  created vaults.
- Pure-JVM testable: empty-state decision logic (which illustration + suggestion
  for which state).

## Definition of done
- Glass surfaces refined (blur + inner-border luminescence + tonal tinting) with
  a graceful no-blur fallback for old/low-RAM devices (verified via capability
  gating, `file:line`).
- Sans + serif type pairing implemented; type scale roles defined; serif used in
  Markdown reading mode via a toggle.
- Empty states with vector illustrations + contextual suggestions replace the
  blank screens listed; no network/assets.
- `gradle testDebugUnitTest` + `gradle assembleDebug` pass.
- REPORT.md: before/after evidence, fallback behavior, font-license note, size
  delta (fonts are small — report it).

## Constraints
- No image assets (vector-drawn only). No network for illustrations.
- Do NOT degrade low-end behavior (AGENTS.md). No silent degradation.
- Do NOT change the DB schema. Do NOT edit `.github/workflows/`. No heavy deps.
- Keep `ClipboardGuard`, FLAG_SECURE, and the security model intact (this is
  visual only — no security regressions).
- Never log decrypted content.