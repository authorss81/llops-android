# Phase 266 — A11y pass 1: semantics + targets + scale + contrast + motion + nav — REPORT

Date: 2026-09-12. Goal: WCAG 2.2 AA pass 1 over `ui/screens/*`, `ui/components/*`, `MainActivity`, `theme/*`.
Tests: `Phase266A11yTest` (14). Full suite **3813 / 0 failures** (3799 + 14), `assembleDebug` green, `lintDebug` 0 errors.

## 1. Claim / reality / status / evidence

| # | PROMPT claim | Reality at HEAD (verified, not memory) | Status | Evidence |
|---|---|---|---|---|
| 1 | CRITICAL: `Icon(contentDescription=null)` on MainActivity restore/dashboard + Markdown clickables + Home nav tiles + Editor DropdownMenuItem icons | All sampled sites are DECORATIVE-with-text (correct null): restore `Icon+Text("Choose Backup & Restore")` (`MainActivity.kt:1550/1638/1767`), outline row `Icon+Text("Outline (n)")` + a LABELED expand/collapse icon (`MarkdownPreviewScreen.kt:282-296`), Serif chip `label="Serif"` (`:940-950`), Home notebook row `Icon+Text(nb.name)` (`:2946`), empty-state art under a heading (`MainActivity.kt:880`), status rows `Icon+title+message` (`:1876`). DropdownMenuItem leadingIcons sit beside item text. Null is CORRECT per TalkBack guidance there. | NO CHANGE (rule pinned, not "fixed") | `A11yPolicy.nullAllowedForDecorative` + §3 allowlist; pins in `Phase266A11yTest` |
| 2 | CRITICAL: TalkBack traversal — graph canvas dots zero semantics; AnnotationCanvas silent; no LiveRegion beyond Snackbar | Graph already had phase-210 mirrors (`GraphSemanticNodeOverlay`, `.clearAndSetSemantics{}`, "Open note" actions) — verified present. AnnotationCanvas root genuinely had NO semantics — FIXED. Graph card had no LiveRegion — FIXED. | FIXED (2 gaps) | `AnnotationCanvas.kt` root `.semantics{}` (~:1798); `KnowledgeGraphScreen.kt` card `.semantics { liveRegion = Polite }` (~:880) |
| 3 | CRITICAL/HIGH targets: Editor 28/26/36dp, Lock 32dp, Markdown 16dp, Home 32dp → 48dp | ALL already ride `minimumInteractiveComponentSize()` (48dp hit): Editor `:2731/:2881/:4308`, Lock biometric `:180-190` (32dp is the glyph, hit is 48dp), Markdown 16dp glyphs are decorative FilterChip/row icons (chip row itself is the 48dp target). Prompt confused glyph size with hit area. | NO CHANGE (pinned against removal) | `Phase266A11yTest.pre-existing 48dp hit areas stay pinned` |
| 4 | HIGH font scale: fixed 56dp app bar, 32dp chips, undismissable dialogs, 7sp labels clip at 200% | 56dp rows contain fixed-size icons/IconButtons (no text clipping); dialogs scroll (`MarkdownPreviewScreen` content `verticalScroll`, graph card controls `horizontalScroll`); the ONE genuine defect is the 7sp "Blend" dock captions (`EditorScreen.kt:4215/4265`) — unreadable even at 1.0x. | FIXED (7sp → 10sp ×2) | `EditorScreen.kt` ×2 `fontSize = 10.sp` + phase-266 comment |
| 5 | HIGH contrast: pin 40% (~2.1:1), disabled undo alpha 0.3, faded graph labels →0.3 | Pin 40% REAL (`HomeScreen.kt:3318`, unpinned state is tappable ⇒ meaningful ⇒ needs 3:1). "Disabled undo alpha 0.3" NOT FOUND (no `alpha = 0.3` in EditorScreen; undo uses `enabled=`). Graph `0.25f*fade` is a decorative halo, `0.12f` fade a deliberately de-emphasized filtered-out node (not text); `MarkdownPreviewScreen :1764` 0.3 is a divider; banner `0.12f` fills are decorative washes. | FIXED (pin 0.4 → 0.6) | `HomeScreen.kt:3318` → `A11yPolicy.UNPINNED_ICON_ALPHA` |
| 6 | HIGH motion: graph 900ms + infinite pulse, AnnotationCanvas sites, FluidPageReveal, Confetti 1800ms, TagExplorer bypass MotionSystem | Graph 900/600ms launches are INSIDE `if (!reduceMotion)` branches (manually gated, verified `:339/:356`); FluidPageReveal reduce-gated; canvas nav/zoom/sticky springs reduce-gated (`shouldAnimate`/`snap()`); TagExplorer uses `MotionSystem.enter/exit` (claim wrong). GENUINE gaps: ConfettiOverlay `tween(1800)` with NO gate; graph pulse `rememberInfiniteTransition` whose only read was already draw-gated but not policy-owned. | FIXED (2 gaps) | `ConfettiOverlay.kt` early-return via `A11yPolicy.shouldAnimate(LocalReduceMotion.current)`; graph pulse condition routed through `A11yPolicy.shouldAnimate(reduceMotion)` |
| 7 | HIGH nav: no focusGroup/order/restorer on dual-pane; palette swipe undiscoverable; no D-pad ring | Verified: ZERO `focusGroup/focusOrder/focusRestorer/focusProperties` in the tree (Compose UI 1.7.6 API surface), navigation is intentionally `mutableStateOf` (AGENTS.md: nav-model changes need user approval — out of scope). Palette swipe entry (`MainActivity.detectTwoFingerSwipeDown`) has a Home-bar keyboard-icon alternative. | DEFERRED with rationale (needs nav-model proposal + user approval; no silent restructure) | This § + `A11yPolicy` KDoc honesty note |

## 2. What changed (6 production edits + 1 policy + 1 suite)

1. NEW `services/A11yPolicy.kt` (pure JVM, 89 lines): `MIN_TOUCH_TARGET_DP=48`, `MIN_LABEL_TEXT_SP=10`,
   `UNPINNED_ICON_ALPHA=0.6f` (+ `LEGACY_UNPINNED_ICON_ALPHA=0.4f` for the direction pin),
   `MAX_AMBIENT_ANIMATION_MS=1000`, `shouldAnimate` / `nullAllowedForDecorative` /
   `meetsTouchTarget` / `meetsLabelSize` / `meetsMotionBudget` / `canvasContentDescription` /
   `canvasStateDescription`. No Compose/Android imports.
2. `ConfettiOverlay.kt`: early return when `!shouldAnimate(LocalReduceMotion.current)` — the 1800ms
   celebratory flight is suppressible (WCAG 2.3.3 animation-from-interactions).
3. `KnowledgeGraphScreen.kt`: pulse-draw condition `!reduceMotion` → `A11yPolicy.shouldAnimate(reduceMotion)`
   (single motion switch; the infinite transition only feeds this draw-phase read, so gating the read
   stops all motion — no recomposition cost either way since the read is in draw scope). Selected-node
   Card gains `.semantics { liveRegion = LiveRegionMode.Polite }` (first LiveRegion beyond Snackbar).
4. `HomeScreen.kt:3318`: unpinned pin tint `0.4f` → `A11yPolicy.UNPINNED_ICON_ALPHA` (0.6f, clears 3:1).
5. `EditorScreen.kt` ×2: "Blend" caption `7.sp` → `10.sp` (= `MIN_LABEL_TEXT_SP`), comment-marked.
6. `AnnotationCanvas.kt` root `BoxWithConstraints`: `.semantics { contentDescription; stateDescription }`
   via the policy (committed-stroke count only — never content/titles). Deliberately does NOT read
   `activePoints`: it mutates per pen sample and would subscribe composition to per-sample recomposition
   (pinned by test: the semantics block contains no `activePoints` token).
7. NEW `Phase266A11yTest` (14): 7 policy unit tests + 7 source pins (confetti gate, pulse gate + settle
   branch, card liveRegion, pin alpha + legacy-0.4 absence, no-`7.sp`, canvas semantics + no-per-sample
   read, pre-existing 48dp hit-area retention).
8. `Phase254CommentTrimTest` PHASE-266 RE-BASELINE (deliberate, measured): AnnotationCanvas 8742/7013 →
   8754/7020 (+12 raw/+7 code: import + semantics block); EditorScreen 7436/6491 → 7440/6491 (+4 raw/+0 code:
   comment-only caption floor); HomeScreen unchanged (same-line swap). KDoc/provenance/blank invariants untouched.

## 3. Decorative-null allowlist (why mass-relabelling was refused)

A TalkBack label on an icon whose Row/Button/MenuItem already exposes the same meaning DOUBLE-announces
("Restore. Choose Backup and Restore. Button"). The rule, now executable in `A11yPolicy`:
null ⇔ `hasTextSibling || isPureIllustration`; icon-ONLY controls must be labeled (all sampled icon-only
controls already are: pin "Pin/Unpin", overflow "More Options", zoom "Zoom In/Out", brush-studio icons,
`tool.label` dock icons, graph overlay "Open note"). Future icon-only additions must follow the same rule.

## 4. Verification

- `gradle :app:testDebugUnitTest` **3813 / 0 failures / 0 errors / 0 skipped** (3799 baseline + 14 new;
  incl. re-baselined `Phase254CommentTrimTest`), `TEST-...Phase266A11yTest.xml`: 14/0/0.
- `gradle :app:assembleDebug` green. `gradle :app:lintDebug` 0 errors.
- No schema change, no new deps, `.github/workflows/` + `verification-metadata.xml` untouched,
  `allowBackup=false` untouched, no secrets/logging touched, base-APK-size rule intact.

## 5. Deferred (honest)

 Dual-pane `focusGroup/order/restorer`, palette-swipe `customActions`, D-pad focus ring: structural
 navigation-model work → requires a user-approved proposal per AGENTS.md (MAJOR ARCHITECTURAL CHANGE).
 Snapshot/2.0x-font-scale device DoD needs hardware (CI has no emulator); structural guards
 (`verticalScroll`/`horizontalScroll`/`heightIn` presence, `maxLines`+ellipsis) hold by pin where added.

 ## 6. Review-fix round (2026-09-12, 9 findings)

 1. `canvasContentDescription(committedStrokes)` ignored its param → now
    parameterless `canvasContentDescription()`; count lives only in
    `canvasStateDescription`. Call site + tests updated.
 2. Graph pulse `!reduceMotion` → `shouldAnimate()` was behavior-neutral and
    left the infinite transition ticking → the transition is now only CREATED
    when motion is allowed, else a static `0f` phase (no animation, no
    recomposition cost under reduce-motion).
 3. Dead policy surface wired into prod: `CELEBRATION_DURATION_MS=800`
    (budget enforced by construction — ConfettiOverlay `tween()` uses it) and
    `MIN_LABEL_TEXT_SP` referenced by EditorScreen captions + the color-picker
    hex label; `meets*` remain documented audit contracts pinned by tests.
 4. Contrast honesty: 0.6f pin alpha reworded as estimate (not lab-measured);
    `Blend` captions AND the presets empty-state hint dropped their
    `0.7f`/`0.7f`-alpha washes to full-strength `onSurfaceVariant`.
 5. Label floor raised 10sp → 11sp (= `labelSmall` default); no-200%-device
    verification stated honestly in `A11yPolicy` KDoc.
 6. Canvas semantics pinned non-merging: no `clearAndSetSemantics`, no
    `mergeDescendants=true` (children stay traversable); graph-dot
    `customActions` stay deferred with the nav model (needs user approval).
 7. Tests hardened beyond `contains`: budgeted-duration pin (`tween(1800`
    absence), conditional-transition pin, non-merge pins, policy-floor
    reference pin. Pure-JVM limit (no Robolectric per phase-239) documented.
 8. Confetti reduce-motion path documented as decorative-only suppression;
    file now uses imports instead of fully-qualified names.
 9. Scope shortfall acknowledged: icon/48dp/MotionSystem items were
    allowlist+pin work; remaining nav items need a user-approved proposal.
 Re-baseline: AnnotationCanvas 8758/7020, EditorScreen 7445/6491 (comment-only
 growth), HomeScreen untouched.
