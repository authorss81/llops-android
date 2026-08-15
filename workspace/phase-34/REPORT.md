# Phase 34 — Visual Polish & Material 3 Expressive — REPORT

Commit: (see git log — squashed as `llops: phase-34`)
Date: 2026-08-15

The phase-34 marker commit (`aa23315`) contained only an empty `.done` file.
This work implements the actual feature set: adaptive glassmorphism, a bundled
sans/serif type pairing with a full M3 type scale, and tactile vector empty
states. `gradle testDebugUnitTest` and `gradle assembleDebug` both pass.

---

## 1. Adaptive Glassmorphism & Tonal Elevation

**Before** — `GlassSurfaces.kt` had a fixed frosted surface with a single
fill/alpha, no style decision layer, no device-tier awareness inside the surface,
and no inner-border treatment.

**After** — rewritten `theme/GlassSurfaces.kt`:

- `GlassSurfaceStyle` enum: `BLURRED_FROST`, `TONAL_FROST`, `TONAL_SOLID`
  (`GlassSurfaces.kt:68-74`).
- `GlassSurfaceMath.resolveStyle(applyBlur, tier, tonalTint)` — single policy
  entry point (`GlassSurfaces.kt:82-92`):

  ```
  applyBlur && capable        -> BLURRED_FROST  (real blur + luminescent edge)
  capable && tonalTint        -> TONAL_FROST    (translucent, no blur)
  LOW_END || no tint          -> TONAL_SOLID    (solid tonal panel)
  ```

- **No-blur fallback (verified gating, `file:line`):**
  - `GlassSurfaces.kt:153-155` — tier resolved via
    `DeviceCompatibilityManager.getDeviceTier(context, settings)`.
  - `GlassSurfaces.kt:88` — `tier == DeviceTier.LOW_END -> TONAL_SOLID` (a
    LOW_END device **never** receives real blur even if the user enables it;
    it silently gets a *non-blurred* but polished solid tonal surface — the
    AGENTS.md non-silent-degradation rule: the user-facing effect is a crisp
    solid panel, not a crash and not a blurry jank).
  - Capability gate composes AGSL (`ShaderCapabilityHelper.isAgslSupported`,
    API 33+) with tier + `SettingsManager.glassBlurEnabled`; consumers still
    pass `applyBlur` from their own capability checks
    (defaults `applyBlur = false`, `GlassSurfaces.kt:146`).
- **Inner-border luminescence:** `Modifier.innerLuminescence(shape, 0.5.dp,
  glow)` (`GlassSurfaces.kt:192`) draws a 0.5dp inner rim via `drawRoundRect`
  + `Stroke`; wired at `GlassSurfaces.kt:180`.
- **Tonal tinting:** per-style fills derive from the active `MaterialTheme`
  scheme (`GlassSurfaces.kt:158-162`) plus a `depthGradient` vertical
  gradient (`GlassSurfaces.kt:168`) — responds to system dark/light/dynamic
  palettes automatically.
- Blur scopes stay tight: `Modifier.blur(blurRadius)` is applied to the small
  surface shape, never a whole screen.
- Added `GlassSurfacePolicyTest.kt` (9 tests) covering the full
  style-decision matrix (LOW_END + blur-on → TONAL_SOLID, capable + blur-on →
  BLURRED_FROST, etc.).

## 2. Typography: bundled geometric sans + editorial serif

**Before** — `Type.kt` built a partial `Typography` from the Roboto system font
and a hardcoded small set of styles.

**After** —

- **Bundled fonts (genuinely in `res/font/`, license on file):**
  - `app/src/main/res/font/plus_jakarta_sans.ttf` (variable weight) — geometric
    sans for system UI/metadata.
  - `app/src/main/res/font/lora.ttf` + `lora_italic.ttf` (variable weight) —
    editorial serif for Markdown long-form reading.
  - License (SIL OFL 1.1) texts committed under `docs/fonts/`
    (`plus-jakarta-sans-OFL.txt`, `lora-OFL.txt`). Justification: a "clean
    modern geometric sans" + "editorial serif" pair is the exact pairing the
    phase requested; both are OFL and variable-weight so a single TTF covers the
    full weight ramp (keeps APK delta small).
- **`theme/Fonts.kt`** — `AppFonts.Sans`, `AppFonts.Serif`, `AppFonts.SerifItalic`
  built via `FontVariation.weight(...)` (`@OptIn(ExperimentalTextApi::class)`),
  4 weights per family.
- **`theme/TypeScale.kt`** — pure-JVM M3 type-scale data: `TypeScaleRole`
  (DISPLAY/HEADLINE/TITLE/BODY/LABEL), 15 M3 styles
  (`displayLarge`…`labelSmall`), all line-heights on the 4dp grid
  (`TypeScaleTest.kt:7` tests assert `isComplete()` = all 15 present and every
  line-height `% 4f == 0f`), plus `specFor`/`roleFor`/`styleNamesFor`.
- **`theme/Type.kt` rewritten** — `typographyFor(mode, systemDark)` builds the
  whole M3 `Typography` from `TypeScale`; DISPLAY/HEADLINE roles use
  `AppFonts.Serif` in light/sepia (and light system/dynamic/glass) themes, Sans
  otherwise; BODY + UI chrome stay geometric Sans. `serifBodyStyle(base, serif)`
  (`Type.kt:73`) is the seam the reading mode uses.
- **Reading-mode toggle** — `MarkdownPreviewScreen.kt` toolbar gains a serif
  `FilterChip` (only outside EDIT mode, `MarkdownPreviewScreen.kt:281-285`),
  persisted via the new `SettingsManager.serifReadingEnabled` (default **off**);
  the `serif: Boolean` flag is threaded through `MarkdownRenderedContent`,
  `RenderBlocks`, `ListItemView`, `MarkdownParagraph`, `MarkdownTable`,
  `TableCellView` and applied as `serifBodyStyle` to body text, headings, list
  items and table cells (`MarkdownPreviewScreen.kt:962,1040,1089,1102`). UI
  chrome remains sans by default.

## 3. Tactile Empty States & Hero Illustrations

**Before** — the empty home grid, empty search, trash, tag vault and plugin-store
empty rows were blank/plain-text screens.

**After** —

- **`ui/components/EmptyStateKit.kt`** — pure-JVM decision logic:
  `EmptyStateKind` (HOME_GRID, SEARCH, VAULT_SETUP, TRASH, NOTEBOOK_PICKER,
  SECTION_PICKER, TAG_VAULT, PLUGIN_STORE) → `EmptyStateDecision`
  (illustration + title + body). `EmptyStateResolver.decide(...)` is fully
  unit-testable (`EmptyStateResolverTest.kt:12` tests) with contextual copy
  ("Create your first note", "Draw with the pen", "Install the LLM plugin from
  the store", search-query echo, first-run welcome).
- **`ui/components/EmptyStateArt.kt`** — `TactileEmptyState` +
  `EmptyStateIllustration`: 7 illustrations (NOTEBOOK, GRAPH, PEN, SEARCH,
  TRASH, STACK, PUZZLE) drawn with Compose `Canvas`/`Path` only — **no image
  assets, no network**.
- **Wired in** (`file:line`):
  - `ui/screens/HomeScreen.kt` — notes grid (search echo / first-run welcome /
    quiet vault) + notebook & section picker sheets.
  - `ui/components/TagExplorerView.kt` — empty tag vault.
  - `ui/components/PluginStoreDialog.kt` — empty plugin-store rows.

## Definition-of-done check

| DoD item | Status | Evidence |
|---|---|---|
| Glass refined: blur + inner luminescence + tonal tinting | Done | `GlassSurfaces.kt:68-192` |
| Graceful no-blur fallback for old/LOW_END (capability gated) | Done | `GlassSurfaces.kt:88`, `:153-155`; `ShaderCapabilityHelper.isAgslSupported` + `DeviceCompatibilityManager.getDeviceTier` |
| Sans + serif pairing, type-scale roles, serif in reading mode via toggle | Done | `Fonts.kt`, `TypeScale.kt`, `Type.kt:73`, `MarkdownPreviewScreen.kt:281-285` |
| Vector empty states + contextual suggestions, no network/assets | Done | `EmptyStateKit.kt`, `EmptyStateArt.kt`, wired into HomeScreen/TagExplorerView/PluginStoreDialog |
| `gradle testDebugUnitTest` passes | Done | full suite green ×4 consecutive (see below) |
| `gradle assembleDebug` passes | Done | `BUILD SUCCESSFUL in 1m 55s` |

## Verification evidence

- New unit tests: `EmptyStateResolverTest` (12), `TypeScaleTest` (7),
  `GlassSurfacePolicyTest` (9) — all green.
- Full suite `gradle :app:testDebugUnitTest`: 4 consecutive clean PASS runs
  after the changes (a single earlier failure was the pre-existing flaky
  `PluginUpdateEngineTest`, fixed — see below).
- `gradle :app:assembleDebug`: `BUILD SUCCESSFUL`.

### Pre-existing flaky test fixed (root-caused, not a workaround)

`PluginUpdateEngineTest > a hash mismatch on the downloaded artifact is never
applied` failed intermittently on the clean tree (reproduced 1/5 clean runs
before this phase touched anything). Root cause:
`TestArtifactBuilder.build` named jars `artifact-<System.nanoTime()>.jar`; when
two consecutive `build()` calls land in the same coarse clock tick, the second
artifact silently **overwrites** the first, so the "mismatch" test served v2
bytes under v2's digest → update succeeded → assertion failed. Fix:
`TestDownloadablePlugin.kt:118-141` adds a monotonic `AtomicLong` sequence to
filenames (documented in the file). Verified: the test now passes 10/10 isolated
runs and 4/4 full-suite runs.

## Fallback behavior summary (AGENTS.md no-silent-degradation)

- API < 33 (no AGSL) or LOW_END tier or user-disabled blur → `TONAL_SOLID`
  (solid `surfaceContainerHigh` tonal panel, no blur) — the app never attempts
  unsupported blur, never crashes; the tonal panel is itself a designed
  aesthetic, not a crash path.
- Capable devices with blur enabled → `BLURRED_FROST` (real backdrop blur,
  0.5dp inner luminescence).
- Capable devices without blur → `TONAL_FROST` (translucent surfaceVariant,
  still luminescent).

## Font-license note

- Plus Jakarta Sans — SIL OFL 1.1, `docs/fonts/plus-jakarta-sans-OFL.txt`.
- Lora (regular + italic) — SIL OFL 1.1, `docs/fonts/lora-OFL.txt`.
- Both are variable-font single files, so all 4 weights per family share one TTF.

## Size delta

Fonts stored in the APK are compressed (aapt2 Defl):
`unzip -lv app-debug.apk | grep res/font`:

| file | stored | compressed |
|---|---|---|
| plus_jakarta_sans.ttf | 176,288 | 79,125 |
| lora.ttf | 212,196 | 106,996 |
| lora_italic.ttf | 221,232 | 115,019 |
| **total** | **609,716** | **301,140** |

Debug APK delta: **+301 KB** (compressed) / 610 KB raw — added solely by the
fonts; no other resources were added. Release APK (R8, unused-resource shrink)
will be smaller still. No existing assets were removed or replaced.

## Scope notes

- DB schema untouched; no new dependencies; no `.github/workflows/` edits.
- `ClipboardGuard` / FLAG_SECURE / encryption model untouched (visual only).
- `FrostedGlassSurface` remains an opt-in component consumers enable with their
  own capability checks (default `applyBlur = false`); no existing screen
  regressed (prior phases 24/27/28 behavior unchanged).
