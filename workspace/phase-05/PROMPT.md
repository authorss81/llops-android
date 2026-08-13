# Phase 5: UX & accessibility — data-loss, touch targets, feedback (QUICK WINS)

You are working on **InkFlow/Noteflow**, an offline notes + canvas Android app
(Jetpack Compose, Material 3). This phase fixes the highest-impact UX and
accessibility issues from the audit. Focus: eliminate a real data-loss path,
bring touch targets to Material minimums, respect system accessibility settings,
and replace the most user-visible Toasts with Snackbars. No new features, no
schema changes.

## Verified issues (from UX audit — fix each)

### 1. CRITICAL: Markdown editor discards unsaved edits on back
`MarkdownPreviewScreen.kt` (~:123) wires `BackHandler(onBack = onBack)` directly —
pressing system back exits WITHOUT saving. `onSaveContent` is only called on the
Save button (~:209) and version restore (~:373).

**Fix:** Make back flush `onSaveContent(contentText)` before navigating (call the
save then invoke the back callback), or save via `DisposableEffect` /
`LifecycleEventObserver`. This is the #1 data-loss path in a notes app.

### 2. Sub-48dp touch targets everywhere
Audit-measured offenders (all <48dp, Material 3 minimum):
- Editor back button `EditorScreen.kt:670` (40dp); voice-record `FilterChip`
  `EditorScreen.kt:758` (32dp)
- Layer-sheet visibility/lock `EditorScreen.kt:2914,:2926` (32dp); move/duplicate/
  merge/delete `:2993-3026` (28dp)
- Brush Studio wet-canvas pills `AnnotationCanvas.kt:1500,:1508` (28dp)
- Font-size/color controls `AnnotationCanvas.kt:823,:833,:843` (32dp), `:910` (28dp)
- Sticky-color swatches `AnnotationCanvas.kt:2699` (32dp)
- Audio playback play/rewind/forward `AudioPlaybackCard.kt:277,:287,:294`
  (36/32/32dp), speed chip `:317` (28dp)

**Fix:** Wrap in `Modifier.minimumInteractiveComponentSize()` (or bump to 48dp)
on every listed target. Keep the in-canvas embed controls that are intentionally
compact (24-32dp transformable cards) as-is unless they're primary actions.

### 3. ~40 legacy Toasts instead of Snackbars
Zero `SnackbarHost` codebase-wide; `Toast.makeText` appears 20× in
`EditorScreen.kt`, 18× in `HomeScreen.kt`, plus `BacklinksInspector.kt:1`,
`Dialogs.kt:1`. Toasts are transient, non-scratchable, invisible to TalkBack.

**Fix:** Add a root `Scaffold`-level `SnackbarHost` (in `MainActivity.kt` /
`NoteflowViewModel` snackbar state flow or a `SnackbarHostState` hoisted to the
root composable). Migrate the HIGHEST-VISIBILITY Toasts first: voice-record
permission denial, export results (`EditorScreen.kt:867,:894,:921`), restore
confirmations. Mechanical full migration of all 40 is acceptable in this phase
if time permits, but the visibility-critical ones are required.

### 4. Reduce-motion hardcoded off; system setting ignored
`Theme.kt:202` (`LocalReduceMotion provides false`) and `Motion.kt:11`
(`compositionLocalOf { false }`) never read the system setting. Animations at
`EditorScreen.kt:1159`, `TagExplorerView.kt:167`, `InteractiveTutorial.kt:143`
fire regardless. `Motion.kt` `MotionSystem` class is dead code.

**Fix:** Source `LocalReduceMotion` from the actual system setting (e.g.
`accessibilityManager.getRecommendedTimeoutMillis` / motion flags) and gate the
three listed animations on it. Remove or wire the dead `MotionSystem`.
`getRecommendedTimeoutMillis` is API 30+ — on API 26-29 fall back to
`accessibilityManager.isEnabled` + a motion-fingerprint check (per AGENTS.md
hardware rule: never silent degradation, but reduce-motion OFF on old devices is
an acceptable conservative default; keep animations short).

### 5. No haptics anywhere
Zero `LocalHapticFeedback`/`performHapticFeedback` matches. Pen-down, color snap,
undo/redo, layer ops, voice start/stop give no tactile feedback.

**Fix:** Add haptic feedback to the highest-value affordances: tool change, undo/
redo, layer add/delete, voice record start/stop, canvas stroke end (light).
Use `LocalHapticFeedback`; keep it subtle.

### 6. Status-bar icon polarity not theme-aware
`MainActivity.kt:83` `enableEdgeToEdge()` uses system-dark defaults, but the app
has its own SEPIA/AMOLED themes. In dark-system + SEPIA, status-bar icons can be
light-on-light.

**Fix:** Build `SystemBarStyle` per app theme (`isAppDark`) when calling
`enableEdgeToEdge()`, so icon polarity always matches the app's actual theme.

## Definition of done
- `gradle assembleDebug` succeeds.
- `gradle testDebugUnitTest` passes.
- Markdown back always saves content (no silent data loss).
- All listed touch targets ≥48dp or wrapped in `minimumInteractiveComponentSize`.
- Reduce-motion respects the system setting; the 3 listed animations are gated.
- Haptics on the listed affordances.
- Root SnackbarHost present; visibility-critical Toasts migrated.

## Constraints
- No new third-party dependencies.
- Do NOT change the DB schema.
- Do NOT edit `.github/workflows/`.
- Keep canvas behavior unchanged (this is UI/feedback polish, not canvas logic).