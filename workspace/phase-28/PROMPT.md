# Phase 28: Sticker & emoji libraries + Glass (glassmorphism) theme [DONE]
You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app. It already has a small sticker pack (Phase 13: `StickerCatalog`,
`CanvasStickyNote`, stickers/rotation) and themed surfaces (Phase 19 palette).
This phase enriches the sticker/emoji library and adds a new **Glass** theme.

## 1. Sticker & emoji libraries
- ENHANCE the existing sticker system with a much richer, meaningfully-curated
  set for a note-taking app. Add **emoji stickers** (render the platform's emoji
  as canvas stickers — offline, free, no assets) AND a larger curated sticker
  catalog organized by category (e.g. Notes/Marks: stars, hearts, flags, arrows,
  highlights; Moods; Symbols; Shapes).
- Emoji rendering via the platform font (`Text` with an emoji char) — NO image
  assets, NO network, NO new permission. Keep APK small.
- New stickers must behave like existing ones: place on tap, drag, resize,
  rotate (Phase 13 rotation), persist through save/load.
- Provide a searchable/filterable sticker+emoji picker reachable in the canvas
  UI (not dead).
- Pure-JVM tests: catalog validity (unique ids, valid category names), emoji
  glyph→sticker mapping, persistence round-trip of a sticker/emoji item.

## 2. Glass (glassmorphism) theme
- Add a new theme mode **GLASS** to `AppThemeMode` + `isAppDarkTheme` +
  `Color.kt`: translucent "frosted glass" panels (blurred, semi-transparent
  surfaces with soft borders/highlights) over a colorful ambient background.
  Apply to the main surfaces/dialogs/sheets (modals, toolbars, sidebar) using
  Compose `Modifier.blur` + translucent `Surface` colors — pure Compose, no new
  deps.
- It must look genuinely "glass": background content shows through panels with a
  frosted blur; borders are subtle light lines; readable text contrast
  guaranteed in both light/dark ambient.
- Respect performance: `blur` is expensive — apply to static surfaces and
  dialogs, NOT to the drawing canvas (the canvas must stay fully opaque for
  accurate color). Gate blur usage behind a setting if low-end devices struggle
  (respect `DeviceCompatibilityManager`).
- Reachable via the existing theme selector. Persist selection. No dead UI.

## Definition of done
- `gradle assembleDebug` succeeds; `gradle testDebugUnitTest` passes with new
  tests: catalog validity, sticker/emoji round-trip, glass-theme color-role
  generation (panel colors derive from ambient with valid contrast).
- Rich sticker+emoji pack functional on canvas with drag/resize/rotate/persist.
- GLASS theme selectable, persists, renders frosted panels on dialogs/sheets,
  and does NOT blur the drawing canvas.
- Low-end devices get an acceptable fallback (reduced/no blur) without breakage.

## Constraints
- NO new third-party dependencies. NO new permissions. NO `INTERNET`. No image
  assets added.
- Do NOT change the DB schema (stickers/emoji persist via the existing canvas
  item path).
- Do NOT edit `.github/workflows/`.
- Respect API 26+ (Compose blur works via RenderEffect; guard older API).
- Be honest: if blur on some Android version is unreliable, fall back to a
  semi-transparent non-blurred panel and document it — never claim glass where
  it isn't rendered.
