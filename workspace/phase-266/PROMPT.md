# Phase 266 — A11y: semantics + targets + scale + contrast + motion + nav

## Goal
WCAG 2.2 AA pass 1: unlabeled controls, tiny targets, ungated motion, unreadable states.

## Evidence
- CRITICAL interactive `Icon(contentDescription=null)`: `MainActivity.kt:880/1571/1659/1788/1897` restore/dashboard; `MarkdownPreviewScreen.kt:282/916/1620` clickables; `HomeScreen.kt:1160/1305/2891/3056` nav tiles, `2780+` dropdown anchors; `EditorScreen.kt` ~40 DropdownMenuItem leadingIcons (decorative OK, but icon-only chips fail). Fix: real labels or `semantics(mergeDescendants){}`.
- CRITICAL TalkBack traversal: `KnowledgeGraphScreen.kt:728-769` canvas dots zero semantics (only selected card `:1027` labeled); `AnnotationCanvas` ~1100 canvas no `contentDescription/stateDescription/customActions` (toolbar labeled, canvas silent); embeds/images no alt text; no LiveRegion beyond Snackbar.
- CRITICAL/HIGH targets: `EditorScreen.kt:2813 28dp/:2998 26dp/:4123 36dp`, `LockScreen.kt:210 32dp`, `MarkdownPreviewScreen.kt:284 16dp`, `HomeScreen.kt:1434 32dp` → 48dp via `minimumInteractiveComponentSize`.
- HIGH font scale: fixed `56dp` app bar, `32dp` chips, undismissable dialogs, `7sp` labels clip at 200%. Fix: `verticalScroll`, `heightIn(min=48dp)`, test fontScale=2.0.
- HIGH contrast: pin icon 40% (~2.1:1), disabled undo alpha 0.3 (~1.6:1 → use `enabled=false` + description), faded graph labels →0.3 alpha.
- HIGH motion: `KnowledgeGraphScreen.kt:318-363` 900ms + infinite pulse, `AnnotationCanvas:3753/4176/7728`, `FluidPageReveal`, `ConfettiOverlay:28`, `TagExplorerView:184` bypass `MotionSystem.spec/enter/exit` (theme plumbing exemplary, coverage ~60%).
- HIGH nav: no Navigation Compose (intentional) → no focusGroup/order/restorer on dual-pane; palette swipe undiscoverable (needs customActions); D-pad focus ring missing.

## Files
- All `ui/screens/*`, `ui/components/*`, `MainActivity.kt`, `theme/*`

## Tests
- `Phase266A11yTest`: every IconButton/clickable has non-null description (source scan); 48dp minimums (source pins); MotionSystem coverage for edited files.

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
