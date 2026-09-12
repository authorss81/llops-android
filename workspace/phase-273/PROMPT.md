# Phase 273 — Wire FloatingZoomWidget into EditorScreen (port from AI Studio)

## Goal
`FloatingZoomWidget.kt` was ported to main but is never composed — dead code. Wire it above the ink bar exactly like AI Studio so users get zoom out/in, % readout with presets, fit-width, fit-page, reset.

## Evidence (verified on main HEAD `3710a3a`; reference: AI Studio `EditorScreen.kt:104,2838-2871`)
- Main HAS the file (`ui/components/FloatingZoomWidget.kt:50`) but grep `FloatingZoomWidget` in main `EditorScreen.kt` = zero hits. AI Studio wires it at `EditorScreen.kt:2848` inside the same `BoxWithConstraints` as the dock.
- Main already has everything the wiring needs: `context` (`EditorScreen.kt:293`), `isPdf` (`:809`), `zoomScale`/`panOffset` state (`:818-819`), `shapeLandscape` in scope (`:2737-2741`), `toolbarState`/`HIDDEN_DRAWING` (`:2743`), `AnimatedVisibility`/`fadeIn`/`fadeOut` imports (`:9-12`), `navigationBarsPadding` via `layout.*` (`:29`, used at `:5068`).

## Fix (mirror AI Studio `:2838-2871`, adapted)
1. Import: add `import com.authorss81.noteflow.ui.components.FloatingZoomWidget` after the `AnnotationCanvas` import (`:103`).
2. Insert before the `// Floating Tool Dock (Phase 35)` comment (`:2733`), inside the same `BoxWithConstraints` scope (so `shapeLandscape` is visible):
```
            // Floating Zoom Controls Widget — positioned above the ink bar
            AnimatedVisibility(
                visible = toolbarState != FloatingToolbarState.HIDDEN_DRAWING,
                enter = com.authorss81.noteflow.theme.MotionSystem.enter(fadeIn()),
                exit = com.authorss81.noteflow.theme.MotionSystem.exit(fadeOut()),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = if (shapeLandscape) 24.dp else 88.dp)
            ) {
                FloatingZoomWidget(
                    zoomScale = zoomScale,
                    onZoomChange = { newZoom -> zoomScale = newZoom },
                    onFitWidth = {
                        val screenW = context.resources.displayMetrics.widthPixels.toFloat()
                        val targetZoom = (screenW / 1080f).coerceIn(0.25f, 5.0f)
                        zoomScale = targetZoom
                        panOffset = Offset(0f, panOffset.y)
                    },
                    onFitPage = {
                        val screenH = context.resources.displayMetrics.heightPixels.toFloat()
                        val targetZoom = (screenH / (1528f + 64f)).coerceIn(0.25f, 5.0f)
                        zoomScale = targetZoom
                        panOffset = Offset(0f, panOffset.y)
                    },
                    onResetZoom = {
                        zoomScale = 1.0f
                        panOffset = Offset(0f, panOffset.y)
                    },
                    isPdfOrDocument = isPdf
                )
            }
```
   (`Alignment`/`navigationBarsPadding`/`dp`/`padding` all resolve via existing `layout.*` import; `Offset` already imported.)
3. Verify `zoomScale`/`panOffset` are the same states passed to `AnnotationCanvas` as `layoutZoomScale`/`layoutPanOffset` (they are the editor's transform source of truth — do NOT introduce new zoom state).

## Tests (pure JVM + source pins)
- `Phase273ZoomWidgetTest`: import present; `FloatingZoomWidget(` composed in `EditorScreen.kt` exactly once; callbacks write `zoomScale`/`panOffset` (source pins); fit math `screenW/1080f`, `screenH/(1528f+64f)`, clamp `0.25f..5.0f` present.

## Constraints
- No Room schema change / migration
- No new dependencies
- No `.github/workflows/` edits
- Follow AGENTS.md (widget is stateless UI over existing zoom state; no new permissions)
- `verification-metadata.xml` untouched

## DoD
- `gradle :app:assembleDebug` green
- `gradle :app:testDebugUnitTest` green (all new + existing, 0 failures)
- `gradle :app:lintDebug` 0 errors
- `workspace/phase-273/REPORT.md` with file:line evidence table
