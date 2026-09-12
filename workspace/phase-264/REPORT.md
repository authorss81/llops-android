# Phase 264 — Responsive: menus + map scale + drag keys + yield (REPORT)

Goal: close the last LocalConfiguration + map-scale + yield gaps (main flows
already pane-local via 244/248/251 — verified, residuals fixed).

## 1. File:line evidence table (claim / reality / status / evidence)

| # | PROMPT claim | Reality found | Status | Evidence |
|---|---|---|---|---|
| 1 | `OverflowMenuSupport.kt:47,62` caps every DropdownMenu via `LocalConfiguration.current.screenHeightDp/WidthDp`, stale through freeform drag | Confirmed: both modifiers read `LocalConfiguration.current` | FIXED | `ui/components/OverflowMenuSupport.kt:61-103` — `rememberLiveWindowSizeDp()` (ViewTreeObserver `OnGlobalLayoutListener`, re-emits every layout pass) feeds `overflowMenuScrollModifier():84` + `overflowMenuWidthModifier():102`; zero `LocalConfiguration.current` reads left in the file |
| 2 | `AnnotationCanvas.kt:3919,3939` map tap/drag use `mapScale = size.width/spW` vs draw `:3959 minOf(w,h)` → ~0.82x hit scale on tall canvas | Confirmed: two gesture sites used width-only, draw used `minOf` | FIXED | `services/MinimapGeometryPolicy.kt:134` new single `mapScale()` (`minOf`, fail-safe 1f) + `mapToWorld():148` / `worldToMap():165`; tap/drag site `AnnotationCanvas.kt:4058`, thumbnail draw `:4113` — no bare `size.width / spW` survives repo-wide in the file |
| 3a | `:3698` minimap-drag keys `(minimapDraggable, effectiveMapW, effectiveMapH, paneW, paneH)` restart mid-drag | Confirmed with drift: keys were `(minimapDraggable, minimapWidthPx, minimapHeightPx, paneW, paneH)` — sizes + header-collapse still restart the gesture | FIXED | keys now `(minimapDraggable, paneW, paneH)` (`AnnotationCanvas.kt:3824`); sizes/insets/resting pos ride `MinimapDragGeom` (`:119`) via `rememberUpdatedState` (`:3795-3813`) |
| 3b | `EditorScreen.kt:3823` dock keys + `3912/3934` tap+drag split; handlers read live zoom but keyed on stale `layoutZoomScale` | Confirmed with drift: dock keys were `(draggable, screenW, screenH, dockW, dockH)`; map tap+drag were two split `pointerInput(..., layoutZoomScale, paneW, paneH, ...)` blocks reading `internalZoomScale` (100 ms stale key) | FIXED | dock keys now `(draggable)` (`EditorScreen.kt:3654`) with `DockDragGeom` (`:3444`) via `rememberUpdatedState`; map tap+drag unified into ONE `awaitEachGesture` handler (`AnnotationCanvas.kt:4052-4100`) keyed `(isContinuousMode, dynamicPageCount, divideIntoPages)` with world/pane/live-zoom via `rememberUpdatedState` |
| 4a | `:3657 bottomReservePx = bottomInsetPx + 80.dp` magic (dock is 56+20=76dp) | NOT FOUND — no `bottomReservePx` symbol exists anywhere in the tree (grep-verified); dock default anchors already derive from measured `dockW/dockH` (`onSizeChanged`) + margins, minimap anchor from `MinimapGeometryPolicy.DEFAULT_MARGIN_DP` | VERIFIED ABSENT (no change needed) | `AnnotationCanvas.kt` + `EditorScreen.kt` contain zero `bottomReservePx` tokens; pinned by `Phase264ResponsiveTest.minimap drag offset survives rotation and no bottomReserve magic exists` |
| 4b | `minimapDragOffset` remember → rememberSaveable (jumps on rotate) | Confirmed: plain `remember` | FIXED | `AnnotationCanvas.kt:648-664` — `rememberSaveable` with `"x,y"` string saver (`minimapDragOffsetSaver`), malformed restores fail safe to null (= default anchor) |
| 5 | `EditorScreen.kt:3771` yield gated on `(!draggable \|\| draggedOffset==null)` → dragged-to-top + PEN never yields; threshold on full `screenH`; horizontal-only; no post-snap re-check | PARTIALLY drifted: the draggable gate was already gone (yield fires regardless of `draggable`), but the threshold was full `screenH`, the gate was `horizontalPosture &&`, and `onDragEnd` never re-checked | FIXED | `EditorScreen.kt:3572` `usableHeightPx = screenH - topInsetPx - bottomInsetPx`; posture decided explicitly (`yieldAppliesForPosture`, both postures yield, `:3576`); post-snap re-check `applyYieldIfNeeded` on BOTH release paths (`:3680-3722`) |

## 2. Design notes / deviations from the PROMPT

- **Menu cap source**: the PROMPT suggests BoxWithConstraints-measured cap or
  LocalWindowSizeClass provider. BoxWithConstraints overloads were rejected —
  they break all 25 existing call sites (a `BoxWithConstraintsScope` receiver
  cannot be called from a plain composable; verified by a first-attempt build
  failure in `HomeScreen.kt:1513`). `LocalWindowInfo.containerSize` does not
  exist in this Compose version (BOM 2024.12.01 / UI 1.7.x — build failure).
  The shipped source is a `ViewTreeObserver.OnGlobalLayoutListener` behind
  `rememberLiveWindowSizeDp()`: live on every layout pass during a freeform
  drag, zero call-site changes, zero `LocalConfiguration` reads.
- **Yield posture**: "decide vertical explicitly" is implemented as an explicit
  per-posture decision that yields for BOTH postures (the vertical 56dp column
  still swallows top touches while drawing). The named
  `yieldAppliesForPosture` flag documents the decision point.
- **Unified map gesture**: tap-vs-drag is classified by touch-slop inside one
  `awaitEachGesture` loop (tap = down+up without slop; drag = slop-crossed
  moves). Single-fire preserved (a drag never also emits a tap).
- **Stale pins updated (conscious, minimal)**: `Phase248MinimapPaneSizeTest`
  (drag-keys + clamp-shape pins) and `Phase253FinalAuditRegressionTest`
  (drag-clamp pin) referenced the pre-264 key/line shapes this phase was
  ORDERED to change; updated to the new shapes. `Phase254CommentTrimTest`
  re-baselined per its own documented protocol (PHASE 264 note in-file):
  AnnotationCanvas 8668→8749 raw / 6956→7024 code, EditorScreen 7347→7436 raw
  / 6426→6493 code; KDoc counts unchanged (new holders use `//` comments).

## 3. Tests

- New `Phase264ResponsiveTest` (10 tests, all green): no-`LocalConfiguration`
  menu cap + live-listener pins; `mapScale` tall-canvas `minOf` math;
  `mapToWorld`/`worldToMap` round-trip + clamp; single-formula source pin;
  minimap/dock/map gesture-key pins; dragged-to-top + PEN yield on usable
  height (both halves of the midpoint + nav-tool negative); yield-wiring pins
  (usable height, no posture gate, snap re-check); saveable offset +
  no-`bottomReservePx` pins.
- Full suite: `gradle :app:testDebugUnitTest` — **3794 tests, 0 failures**.
- `gradle :app:assembleDebug` green. `gradle :app:lintDebug` green, 0 errors.

## 4. Constraints

No Room schema change, no new dependencies (Compose/ViewTreeObserver/Kotlin
only), no `.github/workflows/` edits, `allowBackup=false` untouched, no
plaintext rows, fail-closed preserved (degenerate sizes → policy floors;
malformed saved offset → null → default anchor), `verification-metadata.xml`
untouched.

## 5. Files changed

- `app/src/main/kotlin/com/authorss81/noteflow/ui/components/OverflowMenuSupport.kt`
- `app/src/main/kotlin/com/authorss81/noteflow/services/MinimapGeometryPolicy.kt`
  (`mapScale`/`mapToWorld`/`worldToMap`)
- `app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt`
  (saveable minimap offset, stable minimap-drag keys, unified map gesture,
  single-formula draw)
- `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt`
  (`DockDragGeom`, stable dock keys, usable-height both-posture yield,
  post-snap re-check)
- `app/src/test/java/com/authorss81/noteflow/Phase264ResponsiveTest.kt` (new, 10)
- `Phase248MinimapPaneSizeTest.kt`, `Phase253FinalAuditRegressionTest.kt`
  (stale pins), `Phase254CommentTrimTest.kt` (re-baseline + note)
