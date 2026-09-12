# Phase 273 — Wire FloatingZoomWidget into EditorScreen — REPORT

`FloatingZoomWidget.kt` was ported but never composed (dead code). It is now
wired above the ink bar, mirroring AI Studio `EditorScreen.kt:2838-2871`.

## File:line evidence

| Claim | Evidence |
|---|---|
| Import added after the `AnnotationCanvas` import | `ui/screens/EditorScreen.kt:104` (`import com.authorss81.noteflow.ui.components.FloatingZoomWidget`) |
| Widget composed exactly once, inside the dock's `BoxWithConstraints` (so `shapeLandscape` is in scope), before the dock's own `AnimatedVisibility` | `ui/screens/EditorScreen.kt:2830-2873` (comment `:2830`, `AnimatedVisibility` `:2831-2840`, `FloatingZoomWidget(` `:2841`) |
| Visibility follows the ink bar (`HIDDEN_DRAWING` hides both), MotionSystem enter/exit | `ui/screens/EditorScreen.kt:2832-2834` |
| Positioned above the ink bar, posture-aware | `ui/screens/EditorScreen.kt:2835-2839` (`.align(BottomCenter).navigationBarsPadding().padding(bottom = if (shapeLandscape) 24.dp else 88.dp)`) |
| `onZoomChange` writes the editor's own `zoomScale` | `ui/screens/EditorScreen.kt:2843` |
| Fit-width math `screenW/1080f`, clamp `0.25f..5.0f`, pan-X reset | `ui/screens/EditorScreen.kt:2844-2849` |
| Fit-page math `screenH/(1528f+64f)`, clamp, pan-X reset | `ui/screens/EditorScreen.kt:2850-2855` |
| Reset `zoomScale = 1.0f`, pan-X reset | `ui/screens/EditorScreen.kt:2856-2859` |
| Reads the editor `isPdf` flag | `ui/screens/EditorScreen.kt:2860` |
| No new zoom state: `AnnotationCanvas` consumes the same `zoomScale`/`panOffset` | `ui/screens/EditorScreen.kt:2609-2610` (`zoomScale = zoomScale`, `panOffset = panOffset`) + `:2634-2635` (`onZoomScaleChanged`/`onPanOffsetChanged` write them back) |
| Widget itself unchanged (stateless UI over callbacks) | `ui/components/FloatingZoomWidget.kt` untouched |

Deviation from the literal prompt insertion point (before the `// Floating Tool
Dock` comment at `:2820`): that comment sits OUTSIDE the `BoxWithConstraints`
(which opens at `:2825`), so inserting there would leave `shapeLandscape`
out of scope. The block is instead the first child INSIDE the
`BoxWithConstraints` (right after the `shapeLandscape` val at `:2826-2829`),
satisfying the prompt's explicit scoping requirement ("inside the same
`BoxWithConstraints` scope (so `shapeLandscape` is visible)").

## Tests

- New `Phase273ZoomWidgetTest` (5 tests, pure JVM + source pins): import
  present; `FloatingZoomWidget(` composed exactly once; callbacks write
  `zoomScale`/`panOffset` (+ reset to `1.0f`, `isPdf` read); fit math
  `screenW / 1080f`, `screenH / (1528f + 64f)`, `coerceIn(0.25f, 5.0f)`; canvas
  consumes the same states.
- Collateral: `Phase254CommentTrimTest` is a deliberate line-count
  change-detector; the +34 raw / +32 code growth (1 import + widget block with
  one `//` comment + one blank as the only non-code lines) was re-baselined
  per its own documented procedure (`PHASE 273 RE-BASELINE` note +
  EditorScreen 7591 raw / 6597 code). No KDoc opener added, no provenance
  marker removed, no divider banner, no 2-blank run.

## Verification

- `gradle :app:assembleDebug` — green
- `gradle :app:testDebugUnitTest` — **3891 / 0 failures / 0 errors / 0 skipped**
- `gradle :app:lintDebug` — 0 errors

No Room schema change, no new dependencies, no `.github/workflows/` edits,
`verification-metadata.xml` untouched, no new permissions.

## Review fixes (FINDINGS 1–5)

1. Dead `isPdfOrDocument` param REMOVED (was declared at
   `FloatingZoomWidget.kt:57`, never read in the widget body): signature is
   now `(zoomScale, onZoomChange, onFitWidth, onFitPage, onResetZoom,
   modifier)` and the call site drops `isPdfOrDocument = isPdf`
   (`EditorScreen.kt`, `FloatingZoomWidget(` block). No other callers exist.
2. Fit viewports measure the LIVE window: new file-private
   `fitZoomViewportPx(context)` (`EditorScreen.kt`, above `EditorScreen`) —
   `WindowManager.currentWindowMetrics.bounds` on API 30+ (foldables /
   multi-window), `displayMetrics` fallback below (narrowly
   `@Suppress("DEPRECATION")`). Zero new imports (FQN style, per repo
   pattern); tap-driven only, never per frame. `onFitWidth`/`onFitPage` read
   `.first`/`.second`, keeping the pinned `screenW / 1080f`,
   `screenH / (1528f + 64f)`, `coerceIn(0.25f, 5.0f)` strings intact.
3. Zoom-band asymmetry documented in place (no code change — verified
   harmless: the canvas `LaunchedEffect(zoomScale, panOffset)` sync accepts
   external zoom verbatim, so the 0.25..5.0 widget band always matches the
   render despite the 0.5..4.0 pinch clamp).
4. Fit-math derivation documented in place: 1080x1528 = the canvas portrait
   page box (`AnnotationCanvas.kt:674-675`), +64f the inter-page gap stride;
   pan resets X only so the current page row is kept.
5. Tests: `Phase273ZoomWidgetTest` 5 → 6 (isPdf assertion replaced by an
   `isPdfOrDocument`-absence pin; new live-window test pins `.first` /
   `.second` + `currentWindowMetrics` + `VERSION_CODES.R` + the
   `displayMetrics` fallback). `Phase254CommentTrimTest` re-baselined per its
   own procedure (net EditorScreen +20 raw / +9 code: helper +14/+10,
   rationale comments +8/+0, dead-param removal -2/-2): EditorScreen 7611
   raw / 6606 code; AnnotationCanvas/HomeScreen untouched; no KDoc opener
   added, no divider banner, no 2-blank run.
