# Phase 72 — B2-UI-2: In-app lock paths leave decrypted note content on the system clipboard (scrub ran only on ON_PAUSE)

## Finding

`docs/security-report.md` B2-UI-2 (MEDIUM). `ClipboardGuard.scrubIfOwnCopy` was the
ONLY clipboard scrub and it was wired to `Lifecycle.Event.ON_PAUSE`
(`MainActivity.kt:145`). The in-app lock paths — the manual "Lock Vault Now" button
(`Dialogs.kt:673`), the foreground idle auto-lock (`MainActivity.kt:255`) and the
`ACTION_SCREEN_OFF` receiver (`MainActivity.kt:122`) — all call `viewModel.lock()`
directly, and because the app stays foregrounded ON_PAUSE may never fire. Result:
after a user copies decrypted note content to the system clipboard (a code block via
`MediaEmbedComponents.kt:352-354`, OCR text via `OcrResultDialog.kt:149-150`) and then
locks in-app on a no-keyguard device, the decrypted snippet sits on the shared,
plaintext system clipboard where ANY installed app (clipboard-reader /
"smart-paste" apps) can read it. B1-PLAT-4 governs *when* the lock fires; this is the
distinct gap that *when the lock does fire in-app, the clipboard is not cleared*.

## What changed (file:line)

**The fix is centralized in `lock()` so every lock path scrubs** — no per-call-site
wiring that a future path could forget.

- **`NoteflowViewModel.kt:3218-3234`** — `lock()` gained
  `ClipboardGuard.scrubIfOwnCopy(appContext)` as its FIRST statement:

  ```
  Before:  fun lock() {
               repository.zeroizeKey()
               // B1-AUTH-02 … dispose() …
               if (settings.hasMasterPassword) { … }
               …
           }
  After:   fun lock() {
               // B2-UI-2 … comment …
               ClipboardGuard.scrubIfOwnCopy(appContext)   // :3229
               repository.zeroizeKey()                     // :3237 (was the first stmt)
               if (settings.hasMasterPassword) { … }
               …
           }
  ```

  The scrub runs BEFORE `repository.zeroizeKey()` and BEFORE the
  `if (settings.hasMasterPassword)` gate, so it is unconditional — it also covers a
  passwordless vault's "lock" (the clipboard holds decrypted content regardless of
  vault kind). One call site covers all four entry points:
  `MainActivity.kt:122` (ACTION_SCREEN_OFF), `:149` (ON_STOP), `:255` (idle
  auto-lock) and `Dialogs.kt:673` (manual).

- **NEW `app/src/main/kotlin/com/authorss81/noteflow/services/ClipboardScrubPolicy.kt`**
  (pure JVM — `Long` only, no android classes): the single decide → clear → forget
  decision table.
  - `SCRUB_WINDOW_MS = 60_000L` — the existing scrub window, now shared with the guard.
  - `shouldScrub(copiedAtMs, nowMs, windowMs)` — true only for a genuine (non-zero)
    app copy still inside the window. A zero timestamp ("never copied" or "already
    scrubbed") and an expired copy are NEVER cleared.

- **`services/ClipboardGuard.kt`** — refactored onto the policy +
  Android-bound write kept here.
  - `scrubIfOwnCopy(context: Context?, windowMs)` now **returns Boolean**
    (true = primary clip cleared + the copy timestamp forgotten; false = nothing
    touched) and routes the actual clear through the real system service: API 28+
    `cm.clearPrimaryClip()`, API 26-27 fallback `cm.setPrimaryClip(ClipData.newPlainText("", ""))`
    (unchanged platform behavior — the API-26 floor is covered, no new fallback
    needed). Any platform failure is swallowed (best-effort — the lock path can
    never break). The `context` parameter became nullable purely so the pure-JVM
    tests can exercise the decision through the seam without an Android `Context`.
  - `internal clearPrimaryClipOverride: (() -> Unit)?` — the pure-JVM test seam.
    Production leaves it null, so the real `ClipboardManager` path is always used;
    the source-pin test locks that in.
  - The "clear the guard timestamp on scrub so foreign copies aren't wiped"
    requirement (the finding's fix note) is preserved: `mostRecentCopyAtMs = 0L`
    after a successful scrub, so a later foreign copy survives the NEXT lock.
  - `recordCopy()` unchanged; both note-content copy sources still stamp it before
    their clipboard write (`OcrResultDialog.kt:149-150`, `MediaEmbedComponents.kt:352-354`).
  - **Globally unchanged:** `allowBackup=false`, FLAG_SECURE, `MainActivity.kt:145`
    ON_PAUSE scrub (retained as defense-in-depth — once scrubbed the guard is
    empty, so the double clear is a no-op).

## The vulnerability path (before/after)

```
Before:  clipboardManager.setText(AnnotatedString(codeText))   // user copies code
         viewModel.lock()                 // manual / idle / ON_STOP / screen-off
         // ON_PAUSE may NEVER fire (app stays foregrounded)
         // → decrypted snippet stays on the system clipboard, readable by ANY app

After:   clipboardManager.setText(AnnotatedString(codeText))   // user copies code
         viewModel.lock()
           → ClipboardGuard.scrubIfOwnCopy(appContext)   // :3229, before the DEK drop
               shouldScrub(now - copiedAt ≤ 60_000, copiedAt ≠ 0)?
                 yes → clearPrimaryClip() (or empty setPrimaryClip on API 26-27)
                       mostRecentCopyAtMs = 0   // foreign copies never wiped later
                 no  → nothing touched (foreign / expired / no app copy)
```

## Verification

- `gradle :app:testDebugUnitTest --tests "com.authorss81.noteflow.B2Ui2ClipboardScrubTest"`
  → **BUILD SUCCESSFUL, 13 tests green**.
- `gradle :app:testDebugUnitTest` → **1374 test methods completed, 1372 green, 2 failed**.
  The 2 failures are the documented pre-existing `B1Plat01ReleaseSigningTest` asserts on
  `docs/RELEASE.md` + `app/build.gradle.kts` signing config (present and untouched since
  phase-55, documented in phases 55-71; neither file is touched by this phase). Proven
  unrelated: the same 2 assertions fail identically on a **stashed clean tree**
  (`git stash` → run → same failures → `git stash pop`). Pre-fix baseline was 1361
  total / 1359 green; this phase adds 13 tests with the same pre-existing failures — no
  regression.
- `gradle :app:assembleDebug` → **BUILD SUCCESSFUL** (57 task outcomes). Debug APK
  173,744,974 B, SHA-256 `3c1cc73414944ebecffa1539ddc75578c8c4d405a077dac549b4f81fe5819e5a`.

## New test coverage (`app/src/test/.../B2Ui2ClipboardScrubTest.kt`, 13 tests)

Behavior — the pure-JVM policy (`ClipboardScrubPolicy`):
- never copied (0 timestamp) → never scrub.
- fresh app copy inside the window → scrub.
- app copy exactly at the window boundary (≤) → scrub.
- app copy just past the window → no scrub.

Behavior — the decide → clear → forget sequence via the internal
`clearPrimaryClipOverride` seam:
- `recordCopy()` then `scrubIfOwnCopy` → primary clip cleared exactly once AND the
  copy timestamp is forgotten (`mostRecentCopyAtMs == 0`).
- a lock with no app copy → nothing cleared (foreign clipboard untouched).
- an expired app copy → nothing cleared.
- a foreign copy made after a previous scrub survives the NEXT lock (the finding's
  "clear the guard timestamp on scrub" invariant).
- a platform clear failure is swallowed → `false`, never a crash on the lock path.

Source pins (the Android-bound wiring):
- `lock()` calls `ClipboardGuard.scrubIfOwnCopy(appContext)` BEFORE
  `repository.zeroizeKey()` and before the `if (settings.hasMasterPassword)` gate
  (extracted `fun lock() { … }` block from `NoteflowViewModel.kt`).
- MainActivity has ≥ 3 `viewModel.lock()` calls (ON_STOP + idle auto-lock +
  ACTION_SCREEN_OFF) and the ON_PAUSE scrub is retained; Dialogs "Lock Vault Now"
  routes through `viewModel.lock()` — no path needs its own scrub.
- `ClipboardGuard.kt` still clears through the real system service
  (`getSystemService(Context.CLIPBOARD_SERVICE)`, `clearPrimaryClip()` /
  `setPrimaryClip(ClipData.newPlainText("", ""))`) — the seam is never reachable in
  production.
- both note-content copy sources stamp `ClipboardGuard.recordCopy()` BEFORE the
  clipboard write (`OcrResultDialog.kt`, `MediaEmbedComponents.kt` ordering pins).

## Checksums / secrets handling

- No keys, passwords, decrypted note content, clipboard text, or app-private paths are
  logged or persisted by this change (the scrub only clears the shared clipboard the
  app itself populated).
- `allowBackup=false`, `ClipboardGuard`, and FLAG_SECURE kept intact.
- No ciphertext/length diagnostics added; `mostRecentCopyAtMs` is a non-secret
  timestamp that already existed.

## Out of scope (documented, not fixed here)

- The 60-second window heuristic itself: if the user copies foreign text within 60 s
  of an app copy and then locks, the app copy (not the guard) still wins and the
  clipboard is cleared. Tracking *foreign* copies would require observing the system
  clipboard (a `OnPrimaryClipChangedListener`, which Android 10+ gates behind a
  user-facing paste indicator), or scrubbing the clipboard on EVERY primary-clip
  change while the vault is open. Both are product/policy changes beyond this
  one-finding phase; the finding's prescribed "clear the guard timestamp on scrub"
  mitigation is implemented.
- B2-UI-3 (unsynchronized `lastSavedStrokeHash` + concurrent saves), B2-UI-4 (unlock
  never re-establishes session flows), B2-UI-5 (non-atomic markdown `File.writeText`)
  are separate findings with their own phases (73/74) — not touched here.
- `LocalClipboardManager` (Compose) writes elsewhere in the app that are NOT
  note-content copies were left alone; a repo-wide `setText`/`setPrimaryClip` scan
  (`grep` for `ClipboardManager|clipboardManager|clearPrimaryClip|setPrimaryClip`)
  confirms the only two decrypted-note copy sources are the ones pinned above.

## Constraints honored

- No DB schema change, no migration (not applicable — UI/guard only).
- No new dependencies. `.github/workflows/` untouched.
- Do-not-fix rule: only the B2-UI-2 surface was touched; the related scenarios above
  are documented and left to their own phases.
- API floor (26+): the clear path kept `clearPrimaryClip` for API 28+ and the
  `setPrimaryClip` empty-clip fallback for API 26-27, both best-effort — no newer-API
  requirement was introduced.