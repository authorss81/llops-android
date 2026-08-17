# Phase 158: Reading mode, focus zoom & quick-capture/home polish (deferred ROADMAP 22.5) [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/phase-status.md` +
`docs/ARCHITECTURE.md` + ROADMAP.md "22.5" first.** This is a PRODUCT feature
phase picking up the deferred ROADMAP 22.5 items (share-sheet capture + home
widget) plus a fast reading/focus mode. Must ship with the security model intact
(encrypted vault, fail-closed lock, `allowBackup=false`, no cloud).

## Features (2-3 related, bundle deeply)

1. **Focus/reading mode for notes:** in `MarkdownPreviewScreen`, a "reader"
   toggle: larger leading/column-width, no editing chrome, long-press-safe
   (reading is read-only), respects `reduce-motion` + font-scale settings. Where
   a share intent arrives (`MainActivity.kt:659-722` `readShareIntent`),
   reading mode is the default post-capture destination.
2. **Share-sheet capture polish (22.5a):** the existing ACTION_SEND flow
   (`pendingShareConfirm`/`pendingShare`, `MainActivity.kt:109-114,586-612,
   659-722`) already captures shared text; complete it with (a) `rememberSaveable`
   + auth-gating of the confirm dialog (from phase-140 — apply here if not yet
   present), (b) a "capture as new note vs append to current" choice, (c) the
   deferred clip applying only after unlock (per-session expiry) vs. dropping on
   lock — pick the honest behavior: deferred applies at next unlock ONLY if the
   sender is still on screen or the note is still worth keeping (persist a
   "captured" pending item, non-secret).
3. **Home widget "New note" quick-capture (22.5b — LIGHTWEIGHT):** a 1×1/2×1
   AppWidget that opens the app straight to a new note (no content on the widget,
   no vault access in the widget process — the widget is a launcher shortcut with
   an explicit `EXTRAS` flag read by `MainActivity`). If a full AppWidgetProvider
   proves heavy for the base APK, ship it as a **downloadable signature-verified
   plugin** per the base-APK-size rule (a `WidgetCapability` serving the same
   intent) — flag which path was chosen in REPORT.md.

## UI/UX + plugin ideas

- Quick-capture FAB on Home (existing Add-Page FAB `HomeScreen`) gains a
  long-press "Capture share" shortcut.
- Reading mode sets the quality bar for the phase-156 onboarding "Create note"
  step.

## Verification

- Unit tests for new pure-JVM logic (reader-mode settings decision; pending-capture
  policy — defer/expire/apply; widget-intent parsing). Follow repo test layout
  `app/src/test`.
- `gradle testDebugUnitTest` then `gradle assembleDebug`, report in
  `workspace/phase-158/REPORT.md`.

## Definition of done

- All three features shipped with `file:line` evidence. Share captures are
  auth-gated + saveable + honestly deferred or dropped on lock (no content above
  LockScreen). The widget, if built in-base, is a launcher-only shortcut (no
  vault data); if heavy, it's a plugin.
- New tests green + no existing test regressed.

## Constraints

- NO DB schema change. Do NOT edit `.github/workflows/`. No new base-APK deps
  unless via the downloadable-plugin path (base-APK-size hard rule).
- Never log/display decrypted content when locked. Keep reduce-motion + low-end
  rules (widget updates are cheap, no periodic refresh with content).