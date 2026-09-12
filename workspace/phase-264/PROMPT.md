# Phase 264 — Responsive: menus + map scale + drag keys + yield

## Goal
Close last LocalConfiguration + map-scale + yield gaps (main flows already pane-local via 244/248/251 — VERIFY, fix residuals).

## Evidence
- MEDIUM `OverflowMenuSupport.kt:47,62`: `LocalConfiguration.current.screenHeightDp/WidthDp` caps every DropdownMenu → stale through freeform drag until config pulse. Fix: BoxWithConstraints-measured cap or LocalWindowSizeClass provider.
- HIGH `AnnotationCanvas.kt:3919,3939` map tap/drag `mapScale = size.width/spW` vs draw `:3959 minOf(w,h)` → tall infinite canvas renders 0.82x hit scale (~118px error on 1528x4000). Fix: single `minOf` (or aspectFit scale) everywhere + `mapTapRoundTrip` JVM test.
- HIGH `:3698 pointerInput(minimapDraggable, effectiveMapW, effectiveMapH, paneW, paneH)`: measure-driven keys restart gesture mid-drag (header collapse/zoom HUD wrap). Fix: keys `(minimapDraggable, paneW, paneH)` + `rememberUpdatedState` reads. Same `EditorScreen.kt:3823` dock keys + `3912/3934` tap+drag split (unify via `awaitEachGesture`); handlers read `internalZoomScale` but keyed on `layoutZoomScale` (100ms stale pan).
- MEDIUM `:3657 bottomReservePx = bottomInsetPx + 80.dp` magic (dock is 56+20=76dp) → 52dp waste w/ nav bars. Derive from measured dockH+margin. MEDIUM `minimapDragOffset` remember→rememberSaveable (else jumps on rotate).
- HIGH `EditorScreen.kt:3771` yield `if(horizontalPosture && (!draggable || draggedOffset==null) && shouldYield(...))` → dragged-to-top bar + PEN never yields (stays blocking). Fix: drop draggable guard (yield whenever `shouldYield`), decide vertical explicitly; re-check after snap (`3842-3869`); base threshold on `screenH-topInset-bottomInset`.

## Files
- `ui/components/OverflowMenuSupport.kt`, `ui/components/AnnotationCanvas.kt`, `ui/screens/EditorScreen.kt`

## Tests
- `Phase264ResponsiveTest`: no LocalConfiguration in menu cap (source pin); mapScale single formula (JVM round-trip); yield fires when dragged-to-top + PEN (JVM policy test).

## Constraints
- No Room schema change / migration
- No new dependencies (pure Kotlin/Compose only)
- No `.github/workflows/` edits
- Follow AGENTS.md hard rules (allowBackup=false stays, no plaintext rows, fail closed)
- `verification-metadata.xml` untouched

## DoD
- `gradle :app:assembleDebug` green
- `gradle :app:testDebugUnitTest` green (all new + existing, 0 failures)
- `gradle :app:lintDebug` 0 errors
- `workspace/phase-NNN/REPORT.md` with file:line evidence table (claim / reality / status / evidence)
