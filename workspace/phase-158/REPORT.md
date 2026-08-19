# Phase 158 report — Reading/focus mode, share-sheet capture polish + home quick-capture widget (deferred ROADMAP 22.5)

Status: **DONE** — all three features shipped. Verified `gradle testDebugUnitTest` = 2240 app tests
(2239 green; 1 PRE-EXISTING failure — see below) + `gradle assembleDebug` green.

## Features shipped (with `file:line` evidence)

### 1. Focus / reading mode for notes (`MarkdownPreviewScreen`)

- New pure-JVM decision table `app/src/main/kotlin/com/authorss81/noteflow/services/ReaderModePolicy.kt`:
  - `MAX_COLUMN_WIDTH_DP` = 680f (article measure), `BODY_LINE_HEIGHT_MULTIPLIER` = 1.35f,
    `shouldUseReaderLayout`, `readerLineHeightSp(baseFontSizeSp)` (proportional leading),
    `defaultReaderForCapturedNote(captureArrived)` (post-capture default), `READER_TOGGLE_LABEL`.
  - Constraint honored by the UI (and pinned by tests): no absolute `.sp`/`fontSize` in the
    policy — leading is derived from the ALREADY-SCALED theme font size, so system
    font-scale / accessibility is preserved.
- `MarkdownPreviewScreen` gains `initialReaderMode: Boolean = false` + `onConsumeReaderMode: () -> Unit = {}`
  (`MarkdownPreviewScreen.kt:146-150`); one-shot reader request is consumed on first composition
  (`LaunchedEffect(page.id)` at `:155-158`), so a later unlock never re-applies it.
- Top bar gains the reader toggle `FilterChip` (`:247-252`), INSTANT swap, no transition
  animation (reduce-motion satisfied by construction).
- Reader mode is read-only by construction: `if (readerMode) MarkdownRenderedContent(...readerMode = true)`
  renders a centered, width-capped (`widthIn(max = ReaderModePolicy.MAX_COLUMN_WIDTH_DP.dp)`),
  widened-leading preview — the hybrid editor (`HybridMarkdownEditor`) is NEVER composed in
  reader mode (`:592-604`), so long-press can never open an edit surface.
- Editing chrome is stripped in reader mode: Save (`:317-326`), Smart-Assistant (`:309-313`) and
  the plugin menu (`:350-357` wrapper) are hidden; History + Backlinks stay (read-only).
- Widened leading flows through a file-local `LocalReaderMode` CompositionLocal
  (`:146-149`) into the heading (`RenderBlocks`, `:1096-1108`) and body paragraph
  (`MarkdownParagraph`, `:1406-1414`) renderers — no recursive-signature threading.
- Reader mode is the default post-capture destination: the share-apply effect sets
  `readerModeRequestedFor = page.id` on a captured note (`MainActivity.kt:436-439`),
  `readerModeRequestedFor` is `rememberSaveable` (`:306-307`), both `MarkdownPreviewScreen`
  call sites pass `initialReaderMode`/`onConsumeReaderMode` (`:580-586`, `:687-693`).
- `.txt`/`sourceFileType == "text"` pages route to `MarkdownPreviewScreen` as well
  (`:564`, `:673`).

### 2. Share-sheet capture polish (22.5a)

- `PendingShareState.kt` (`services/PendingShareState.kt`):
  - New `ShareCaptureMode` enum (`NEW_NOTE` / `APPEND_TO_ACTIVE`, `fromToken` fails closed to NEW_NOTE).
  - `PendingShareConfirmState` carries `stagedAtMs`; `PendingShareState` carries `captureMode`.
  - `PendingSharePolicy` gains `CONFIRM_HOLD_EXPIRY_MS` = 10 min, `isExpired(stagedAtMs, nowMs)`,
    `resolveAppendTarget(hasActivePage, clipHasImages, mode)` (append ONLY for a text-only clip
    with an active page; anything else degrades honestly to `CREATE_NEW_NOTE`),
    `deferredAppliesNow(authenticated)`, and the NON-SECRET captured-marker payload
    (`capturedMarkerPayload` → flag + wall-clock stamp, never clip content).
- `SettingsManager.kt:328-334` persists the non-secret marker as
  `capturedSharePending` + `capturedSharePendingAtMs` only (boolean + stamp, never content).
- `NoteflowViewModel`:
  - `stagePendingShare` stamps `stagedAtMs` and sets the non-secret marker
    (`NoteflowViewModel.kt:1534-1544`).
  - `confirmPendingShare(captureMode: ShareCaptureMode = NEW_NOTE)` carries the mode into the
    deferred clip (`:1548-1557`).
  - `consumePendingShare` clears the marker on apply (`:1562-1567`); `cancelPendingShareConfirm`
    clears it on dismiss.
  - `lock()` drops both states AND the marker for password vaults (`:4424-4430`) — fail-closed,
    no content survives a lock, no stale "you had a capture" flag.
  - New `appendSharedContentToPage(page, sharedText, onDone)` (`:2241-2266`) reads the LATEST
    committed body via `readMarkdownNoteBody` (B2-UI-5 semantics — never clobbers a concurrent
    edit), then writes the combined body through `saveMarkdownNoteBody` (B2-UI-1 lock-gated,
    defers encrypted on a racing lock). Image clips never append (`resolveAppendTarget`).
- `MainActivity`:
  - Confirm dialog carries a new-vs-append choice (`RadioButton`s) with honest disabled state —
    append is disabled + explains why when there is no active note or the clip carries images
    (`MainActivity.kt:806-858`).
  - The hold expires after 10 min: a `LaunchedEffect` schedules the cancel at the deadline and
    re-checks so an already-answered confirm is never clobbered (`:766-776`).
  - The apply effect routes through `PendingSharePolicy.resolveAppendTarget` and either appends
    to the active note or creates a new note (`:424-441`), and still copies bytes ONLY on an
    authenticated frame (B1-PLAT-2/R2-B1P-05 intact).
  - The dialog stays gated under `authenticated` (never floats above LockScreen) and the choice
    is `rememberSaveable` (survives rotation; resets per incoming clip) (`:781-806`).

### 3. Home widget "New note" quick-capture (22.5b — LIGHTWEIGHT, in-base)

Path chosen: **in-base launcher-only AppWidget** (not a downloadable plugin) — it carries NO
vault data, NO Room/keystore code, NO periodic refresh, NO new permission; the base-APK-size
rule is satisfied because the widget adds ~1 small vector drawable + a RemoteViews builder.

- `services/WidgetLaunchPolicy.kt` — pure-JVM contract: `EXTRA_QUICK_CAPTURE`,
  `WIDGET_PROVIDER_CLASS`, `WIDGET_ROOT_VIEW_ID`, `WIDGET_INFO_XML`, geometry constants,
  `hasQuickCaptureExtra(extras: Map<String, Boolean?>, key)`, `WIDGET_PENDING_INTENT_REQUEST_CODE`,
  labels.
- `ui/widget/QuickCaptureWidget.kt` — `AppWidgetProvider` that builds a RemoteViews whose single
  click is a `PendingIntent` to `MainActivity` (`ACTION_MAIN`, launcher category,
  `FLAG_ACTIVITY_NEW_TASK or CLEAR_TOP`, `putExtra(EXTRA_QUICK_CAPTURE, true)`,
  `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE`). No vault/IO in the widget process (`:28-53`).
- Resources: `res/xml/quick_capture_widget_info.xml` (`updatePeriodMillis="0"`, 1x1/2x1,
  `home_screen`, resizeable), `res/layout/widget_quick_capture.xml` (icon + fixed label only),
  `res/drawable/widget_quick_capture_icon.xml` (vector), `res/values/strings.xml` label + desc.
- Manifest: receiver `QuickCaptureWidget` registered `android:exported="false"` with
  `APPWIDGET_UPDATE` action + provider meta-data (`AndroidManifest.xml:37-47`).
- `MainActivity` reads the extra via the policy (`handleQuickCaptureIntent`, `:948-962`,
  extras → typed `Map<String, Boolean?>`, true-only parse) in both `onCreate` and `onNewIntent`,
  sets the `quickCaptureRequested` flag, and a `LaunchedEffect(authenticated, quickCaptureRequested)`
  fires `addPage("New Page", onCreated = setActivePage)` ONLY once the vault is authenticated —
  a locked-vault tap never creates anything and the flag is consumed (`:447-452`).

## Honest defer/drop posture (chosen from the PROMPT options)

- Confirmed clips apply ONLY on an authenticated frame (`deferredAppliesNow(authenticated)`).
- Password-vault lock DROPS both the confirm and the deferred clip + clears the marker
  (`PendingSharePolicy.clearOnLock`) — fail-closed, no auto-apply at the next unlock, no content
  above the LockScreen.
- An un-confirmed hold expires after 10 minutes (`CONFIRM_HOLD_EXPIRY_MS`); the confirmed clip is
  exempt because it was explicitly human-approved and applies on the very next authenticated frame.
- The only thing ever persisted is the NON-SECRET captured marker (boolean + wall-clock stamp);
  clip content is never persisted at rest.

## Tests

- `Phase158ReaderModePolicyTest` (6) — reader toggle, 680dp column, proportional leading
  (13.5×1.35 / 20×1.35, fixed ratio), no absolute `.sp`/`fontSize` in policy source,
  captured-note reader default, stable label.
- `Phase158ShareCapturePolicyTest` (8) — mode flows confirm→deferred, `fromToken` fails closed,
  10-min expiry + zero-timestamp legacy, authenticated-only apply, lock drop for password vaults,
  append degradation (no active note / image clips → new note), non-secret marker
  (stable keys, no content field, toString never leaks content).
- `Phase158WidgetLaunchPolicyTest` (6) — extra contract, true-only parse (absent/false/null/other
  key don't fire), XML pins (`updatePeriodMillis="0"`, `home_screen`), widget code free of
  vault/ContentResolver/SharedPreferences references, manifest registration, MainActivity reads
  the policy extra, modest flat geometry.
- Updated existing pins for the new wiring: `B1Plat02ShareConfirmationTest`,
  `Phase140ShareStateLockTest` (confirm now `viewModel.confirmPendingShare(clipMode)` and
  `PendingSharePolicy.toPendingShare(request, …)`).

`gradle testDebugUnitTest` = 2240 tests: 2239 pass, 1 failure
`Phase148UiFailureTextScrubTest` (UNC-path redaction) — documented PRE-EXISTING baseline,
reproduced on a clean stash per AGENTS.md (phases 149-157), untouched by phase-158.
`gradle assembleDebug` green. No schema change, no new base-APK dependencies,
`.github/workflows/` untouched.

## Notes / scope decisions

- `MainActivity.kt` was edited by two independent session contexts (staged + working tree); the
  ORIGINAL `LifecycleEventObserver` ON_PAUSE block was accidentally dropped during an edit and
  restored byte-for-byte from HEAD — confirmed via `git diff` before finalizing.
- The `rememberSaveable` auth-gating from phase-140 was already present; this phase adds the
  mode choice, expiry, and marker persistence on top.
- Reader mode is also the quality bar for the phase-156 "Create note" onboarding step (a captured
  note opens in reader mode, not the editor).
