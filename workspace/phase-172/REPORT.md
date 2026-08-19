# Phase 172 REPORT — Editor & canvas productivity: persistent color recents + favorites, minimap quick-actions, layer blend presets

Date: 2026-08-19
Phase: `workspace/phase-172/PROMPT.md` (phase-161 UI/UX triage bucket; 3 related
editor/canvas productivity features)

## 1. Task

Three feature items behind today's editor UX:

- **Feature 1 — Persistent recently-used colors + favorites.** The old
  `EditorScreen.kt` recents row was a volatile `remember { recentColors }`
  derivation (pre-fix `EditorScreen.kt:3595-3605`) — it died every session and
  repopulated from the current palette. Add a bounded, persisted recents list
  (+ a favorites star on the palette picker) in `SettingsManager`, loaded early
  via `StateFlow` (no blocking read on main), recording on pick.
- **Feature 2 — Minimap quick-actions.** The minimap (bit-budgeted sampling via
  `MinimapGeometryPolicy.kt`) had no quick-view helpers. Add zoom-to-fit +
  jump/pan-home, pure-JVM testable, `MotionPolicy`-aware, respecting
  `reduce-motion`, animating through the existing canvas transform pipeline.
- **Feature 3 — Layer blend quick presets.** Compact blend-mode preset chips
  (normal/multiply/screen/overlay/soft-light) in the layer panel, persisted via
  the existing per-layer blend field (no schema change).

Constraints honored: NO `.github/workflows/` edits; NO Room schema change
(prefs only via `SettingsManager`); NO new dependencies / assets (base-APK-size
book unchanged); never log decrypted content; reduce-motion / API-26 low-end
respected; 48dp touch targets.

## 2. Feature 1 — Persistent recents + favorites

### 2.1 Policy: `services/ColorRecentsPolicy.kt` (pure JVM)

- `MAX_RECENT_COLORS = 16`, `MAX_FAVORITE_COLORS = 12`.
- `recordRecent(list, colorArgb, cap)`: dedupe + move-to-front + trim to cap.
- `toggleFavorite`, `isFavorite`.
- Prefs wire format is fail-closed: `encodeColors` joins `#AARRGGBB` decimal
  ints with `","` (always stable order — no color == sortable-violating
  leading zeros); `decodeColors` splits, validates each token, and DROPS any
  malformed/out-of-range entry (returns an empty list rather than a bad state
  for a corrupt pref). Palette entries (`isColorIncluded`) are never written.
- `sanitizeRecent` / `sanitizeFavorites`: cap + dedupe + drop `UnsetColor` /
  palette-included colors on read.

### 2.2 Persistence + view-model plumbing

- `SettingsManager.recentColors` / `SettingsManager.favoriteColors`
  (`SettingsManager.kt`) — SharedPreferences-backed accessors keyed
  `recent_colors` / `favorite_colors`, round-tripping through the policy.
- `NoteflowViewModel`: `_recentColors` / `_favoriteColors` `MutableStateFlow`s,
  seeded at init (no blocking prefs read on main), plus `recentColors` /
  `favoriteColors` `StateFlow`s, `recordRecentColor(colorArgb)`,
  `toggleFavoriteColor(colorArgb)`, `isFavoriteColor(colorArgb)`.

### 2.3 UI wiring (`EditorScreen.kt`)

- The picker receives the persisted lists + callbacks.
- The old volatile `remember` recents row is replaced by the persisted recents
  list (kept session-only custom colors as a suffix so existing behavior isn't
  lost), with a Favorites star row (48dp `minimumInteractiveComponentSize`
  targets) toggling the current color on/off.
- Palette-on-color-selected + saved-swatch taps and the eyedropper
  (`onColorSampled`) all `recordRecentColor`. HSV-slider drags do NOT set the
  interaction state (only a deliberate pick) so the 16-slot list can't flood.

## 3. Feature 2 — Minimap quick-actions

### 3.1 Policy: `services/CanvasNavigationPolicy.kt` (pure JVM)

- `Bounds` + `TargetTransform(scale, panX, panY)` data classes, `emptyBounds`,
  `jumpHome()` → `(1f, 0f, 0f)`.
- `computeContentBounds(strokes, worldW, worldH, strideOverride)` — bit-budget
  aware: page steps via `MinimapGeometryPolicy.strokeStepFor` (spacers/notes on
  their own page track) and point steps via `MinimapGeometryPolicy.pointStepFor`
  above `EXACT_BOUNDS_POINT_CAP = 20_000`, so a heavy page can't blow the
  budget (the same sampling ceilings the minimap renderer itself uses).
- `contentWithinWorld(content)` — clamps bounds to the world rect (spacers sit
  on the page-track above the note, or page max height bounds wrapping).
- `zoomToFit(content, viewportW, viewportH, worldW, worldH)` — single call maps
  strokes to a clamped target transform: fits to the clamped bounds with
  `FIT_PADDING_PX = 48`, clamps scale to `MIN_FIT_ZOOM = 0.5f` .. `MAX_FIT_ZOOM
  = 4f`, and pans to this clamped scale's center (a truly out-of-bounds /
  empty / zero-area content fit falls back to `jumpHome()`).
- `shouldAnimate(reduceMotion)` + `MotionPolicy.SpringKind.CANVAS_PAN`
  spring — the policy picks the spring, the UI honors it.

### 3.2 UI (`AnnotationCanvas.kt`)

- A compact "Go:" row next to the minimap: zoom-to-fit chip (`CenterFocusWeak`
  icon) + jump-home chip (`Home` icon), 48dp targets.
- Both route through `navigateCanvasTo(targetScale, targetPan)` →
  `LaunchedEffect(navRequestSeq)` → `navScaleAnim`/`navPanAnim` `Animatable`s →
  per-frame `updateZoomAndPan` (the EXISTING debounced transform pipeline the
  minimap already drives), so the viewport rect, minimap box and EditorScreen
  state all stay in sync. `shouldAnimate` && `!reduceMotion` → spring
  (`MotionPolicy.SpringKind.CANVAS_PAN`); else snap.

## 4. Feature 3 — Layer blend quick presets

### 4.1 Policy: `services/LayerBlendPresetPolicy.kt` (pure JVM)

- `RENDERER_SUPPORTED_MODES` — the 12 mode keys the canvas renderer actually
  wires (used by the layer panel dropdown).
- `presets()` — the compact 5 (NORMAL / MULTIPLY / SCREEN / OVERLAY /
  SOFT_LIGHT) with human labels + one-line descriptions.
- `isSupportedByRenderer(key)` (case-insensitive gate so a preset chip never
  pushes an unwired blend string) and `displayLabel(key)` (preset label,
  falling back to the key).

### 4.2 UI (`EditorScreen.kt` layer panel)

- A labelled `FilterChip` row for the 5 presets above the existing blend
  dropdown. Every chip calls the same `onUpdateLayer(layer.copy(blendMode = …))`
  → `handleLayersChange` → `saveLayersGated` path the dropdown already uses, so
  per-layer blend persists on the existing `Layer.blendMode` field — no schema
  change. The dropdown's old hardcoded 12-mode literal now reads
  `LayerBlendPresetPolicy.RENDERER_SUPPORTED_MODES` (single source of truth).

## 5. Tests

| suite | tests | what it pins |
|---|---|---|
| `ColorRecentsPolicyTest` | 13 | cap/dedupe/move-to-front; favorites; encode/decode fail-closed; sanitize; prefs round-trip via `SettingsManager`; palette-included exclusion |
| `CanvasNavigationPolicyTest` | 13 | content-bounds on/off-note + page step budget; world-clamp; zoom-to-fit min/max clamp + 48px padding + centered pan; degenerate/zero-area/out-of-bounds → jump-home; jump-home identity; spring-kind/reduce-motion gate |
| `LayerBlendPresetPolicyTest` | 7 | exact 5-preset ordering; renderer-support gate (case-insensitive); labels; resolve/fallback; exact 12-mode dropdown list |

`gradle testDebugUnitTest`: **2339 tests, 1 failure** = the pre-existing
`Phase148UiFailureTextScrubTest` UNC-path failure (documented in AGENTS.md,
untouched, reproduced). `gradle assembleDebug`: **green**.

## 6. Constraints check

- No `.github/workflows/` edits. No Room schema change (prefs only). No new
  deps/assets → base-APK-size book unchanged. No decrypted-content logging in
  the new code. Reduce-motion honored (navigate snaps). 48dp touch targets via
  `minimumInteractiveComponentSize`. `.editorconfig`-consistent formatting.

## 7. Follow-ups (none required for phase 172)

- The `EditorScreen` minimap quick-action row is scrollable and has 2 items; if
  a future phase adds more minimap actions the `Go:` row layout should be
  audited at 360dp (same discipline as phase-166).
- "Brush recents" (title mention) is surfaced by the existing
  `BrushStudioDialog` brush presets; this phase scoped recents to colors per the
  PROMPT body — a separate brush-recents item can slot into the same
  `ColorRecentsPolicy`-style prefs pattern later if triage requests it.