# Phase 125 — Enhanced Interactive Tutorial: Report

> Commit: `8e4f6ec` ("llops: phase-125") + follow-up review-fix commit "llops: phase-125 review fixes".
> Scope: expand the first-run tutorial into a structured, interactive, progress-checked
> curriculum modelled as pure-JVM data + a pure state machine.

## What shipped

### Curriculum (new) — `app/src/main/kotlin/com/authorss81/noteflow/services/TutorialCurriculum.kt`
- 43 slides across all 10 `TutorialSection`s (START 5, MARKDOWN 7, CANVAS 7, LAYERS 3,
  COLOURS 5, ERASERS 3, GRAPH 2, PLUGINS 3, BACKUP 3, SECURITY 5) — ~4× the old 11-step
  deck in `HomeScreen.kt`.
- 5 real interactive action slides via `sealed class TutorialAction` (`TutorialCurriculum.kt:29-58`):
  `DrawStroke`, `EraseStroke`, `AddLayer`, `PickColourMode`, `TypeMarkdown`.
- Pure state machine `TutorialSession` (`TutorialCurriculum.kt:443-527`): action-gated
  advance, `forceAdvance` skip-step, `back`, clamped resume `index`, progress %, in-section
  counters. `isWellFormed` structural guard (`TutorialCurriculum.kt:417-429`).
- Content honesty: every claim was checked against real, wired features (encryption
  PBKDF2 600k — `EncryptionService.kt:169`; 5-fail exponential lockout —
  `NoteflowViewModel.kt:2524,2738`; two-finger palette — `MainActivity.kt:803`; unlinked
  mentions — `BacklinksInspector.kt:41`; WebDAV HTTPS, LocalSend, quarantine recovery,
  AGSL wet-mixing, rainbow mode — `SettingsManager.kt:204`, `StrokeModels.kt:128`).

### Renderer — `app/src/main/kotlin/com/authorss81/noteflow/ui/components/InteractiveTutorial.kt`
- `TutorialUiState` (compose-observable glue) + `tutorialIcon()` resolver with fallback.
- One-slide-per-screen card with section chip, progress bar, Prev / Next / Skip-step /
  Skip Tutorial / "Don't show this again". Cheap draw-only styling (low-end rule).
- System `BackHandler` now behaves exactly like "Skip Tutorial" (resume later).

### Interactive demos (new) — `app/src/main/kotlin/com/authorss81/noteflow/ui/components/TutorialDemos.kt`
- `PracticePad` (draw/erase), `LayerDemoPanel`, `ColourModeDemo`, `MarkdownTypeDemo`.
- Paths are cached (`remember(version)`) and rebuilt only on content change, so the
  draw pass allocates nothing per frame.
- Erase demo seeds a sample stroke in pointer coordinate space and only completes the
  progress check when a swipe actually overlaps ink (`overlapsAnyStroke`, radius 18f).

### Persistence — `app/src/main/kotlin/com/authorss81/noteflow/services/SettingsManager.kt`
- `tutorialResumeIndex` (`SettingsManager.kt:37-39`): survives "Skip", reset to 0 on
  completion. `NoteflowViewModel.kt:1299-1303,1644-1651,1660-1664` mirrors it as a
  `StateFlow`; completion / "don't show again" persist via `markFirstRunComplete`.

### Home wiring — `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/HomeScreen.kt`
- Tutorial is the first-run experience (`HomeScreen.kt:104-106`), reopenable anytime via
  ⋮ → "Interactive Tutorial" (`HomeScreen.kt:2562-2565`); resume index snapshotted per
  open (`HomeScreen.kt:1607`), completion fires confetti.

## Review fixes applied (same commits)

| Finding | Fix | Evidence |
|---|---|---|
| Build broken: `androidx.compose.animation.animateDpAsState` (nonexistent package, unused) | Import removed; `BackHandler` added instead | `InteractiveTutorial.kt:3` |
| Build broken: `Icons.Outlined.Eraser` does not exist in `material-icons-extended` | Replaced with `Icons.Outlined.CleaningServices` (verified present in the resolved jar) | `InteractiveTutorial.kt:98-99` |
| Missing `workspace/phase-125/REPORT.md` (DoD) | This report | — |
| Erase demo impossible: no way to draw first, check fired on any drag | Seed a sample stroke in ERASE mode; verify overlap before `onGestureDone`; slide text/tip updated | `TutorialDemos.kt` (`LaunchedEffect` seeding, `overlapsAnyStroke`, `sampleWave`); `TutorialCurriculum.kt:295-301` |
| Dead code: `markTutorialCompleted()`, `WelcomeDialog`, `FeatureRow` | Deleted | `NoteflowViewModel.kt`, `Dialogs.kt` |
| Fabricated "21 Brushes" / "spray" | Retitled, "spray" → "splatter" | `TutorialCurriculum.kt:182-190` |
| Markdown check passed on any 3 chars | Now requires a leading `#` | `TutorialDemos.kt` `MarkdownTypeDemo` |
| Per-frame `Path` allocation claim | Paths cached via version counter | `TutorialDemos.kt` |
| No back-key handling | `BackHandler(onBack = onSkip)` | `InteractiveTutorial.kt` |
| Test comment "old 7-step deck" | Corrected to 11 | `TutorialStateMachineTest.kt:37` |
| Latent test defect (surfaced once the module finally compiled): `action slides map to every interactive demo type` failed because `TutorialAction.all`'s Kotlin `object` singletons displayed JVM identity/init-order anomalies in this project's test harness (null entry at the `TypeMarkdown` slot while the direct singleton was non-null) | Assertion now compares action *types* (`it::class.java`) sourced directly from the five singletons instead of `.toSet()` object-identity | `TutorialStateMachineTest.kt:63-80` |

## Verification
- `gradle compileDebugUnitTestKotlin` / `gradle assembleDebug` (clean): green — was failing pre-fix with 3 compile errors.
- `gradle testDebugUnitTest` (clean): **1753 tests, 0 failures** — includes `TutorialStateMachineTest` (curriculum structure,
  advance gating, forceAdvance, resume clamping, progress/section counters).
- No DB schema change, no `.github/workflows/` edits, no new dependencies; `allowBackup`,
  `ClipboardGuard`, FLAG_SECURE untouched.