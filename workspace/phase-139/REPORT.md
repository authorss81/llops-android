# Phase 139 — Clipboard scrub coverage: every note-content copy surface is covered by an unconditional lock-time clip clear

**Status: DONE (2026-08-18)**

Closes the OPEN finding `R2-B1P-01` (MEDIUM) from `docs/security-report-round2.md`.

---

## R2-B1P-01 (MEDIUM) — clipboard-scrub on lock covered only 2 of the app's copy surfaces; the primary note-editor copy (and the OCR dialog's `SelectionContainer` copy) were untracked and survived every lock

### The root cause

`ClipboardGuard.recordCopy()` was stamped at exactly two sites —
`OcrResultDialog.kt:149` (OCR "Copy" button) and `MediaEmbedComponents.kt:353`
(code-block "Copy Code" button). `lock()` scrubbed via
`ClipboardGuard.scrubIfOwnCopy` which delegates to
`ClipboardScrubPolicy.shouldScrub` and **denies whenever `mostRecentCopyAtMs == 0L`**
(`ClipboardScrubPolicy.kt:32-33`) — i.e. a zero stamp = "nothing the app copied, or
the copy happened through an untracked path → leave the clip alone".

Two note-content copy surfaces were **platform-native** and stamped nothing:

1. the markdown editor's raw block editor is an `OutlinedTextField`
   (`HybridMarkdownEditor.kt:219`) whose long-press → Select-all → Copy writes the
   decrypted note body to the system clipboard through the Android selection menu —
   no Compose code runs at all;
2. the OCR dialog renders its result inside a `SelectionContainer`
   (`OcrResultDialog.kt:121`) whose native context-menu Copy has the same
   unstamped behavior, sitting right next to the stamped "Copy" button.

`lock()` (`NoteflowViewModel.kt:3649` at audit) therefore wiped nothing for these
copies — a decrypted note body copied via the editor selection had **no window at
all** (worse than the 60 s window caveat of B2-UI-2).

### Fix chosen — the finding's sanctioned alternative

The finding offers two options: (a) route every copy through a shared stamping
helper by intercepting the platform selection menus
(`LocalTextToolbar`/custom `SelectionContainer`), or (b) "given this app's threat
model — clear the primary clip **unconditionally** in `lock()` instead of only
'own copy within window'".

Option (b) was chosen. Intercepting Compose's platform selection Copy is brittle
(per-version text-toolbar machinery, and the editor/OCR native paths have no
Compose hook point at copy time), while the app's threat model is explicit: a
vault lock is a deliberate security boundary and decrypted note content must never
survive it. The lock cannot reliably know what is on the primary clip, so it
clears the whole clip, fail-closed. The **windowed** decision is retained on the
**ON_PAUSE** hook only, where a brief app switch must never wipe a foreign copy.

#### `ClipboardGuard.kt` — before → after

Before (`ClipboardGuard.kt:50-54` at audit): a single `scrubIfOwnCopy` that
denies on a zero/expired stamp and then clears:

```kotlin
fun scrubIfOwnCopy(context: Context?, windowMs: Long = ClipboardScrubPolicy.SCRUB_WINDOW_MS): Boolean {
    val copiedAt = mostRecentCopyAtMs
    if (!ClipboardScrubPolicy.shouldScrub(copiedAtMs = copiedAt, nowMs = System.currentTimeMillis(), windowMs = windowMs)) {
        return false         // ← a 0L stamp (untracked native copy) returns here → NOTHING cleared
    }
    return try { /* clearPrimaryClip API 28+ / empty setPrimaryClip API 26-27 */ ... }
}
```

After: the actual clear is in one private `clearPrimaryClip`, and the two public
scrub modes both route through it:

```kotlin
fun scrubIfOwnCopy(context: Context?, windowMs: Long = ClipboardScrubPolicy.SCRUB_WINDOW_MS): Boolean {
    val copiedAt = mostRecentCopyAtMs
    if (!ClipboardScrubPolicy.shouldScrub(copiedAtMs = copiedAt, nowMs = System.currentTimeMillis(), windowMs = windowMs)) return false
    return clearPrimaryClip(context)
}

fun scrubUnconditionally(context: Context?): Boolean {   // R2-B1P-01: the LOCK mode
    return clearPrimaryClip(context)                     // no stamp check, no window
}

private fun clearPrimaryClip(context: Context?): Boolean { ... }   // ClipboardGuard.kt:88
```

Both modes keep the API floor: `Build.VERSION.SDK_INT >= P` → `cm.clearPrimaryClip()`,
API 26-27 → `cm.setPrimaryClip(ClipData.newPlainText("", ""))`. Best-effort: any
platform failure returns false and is swallowed so the lock never breaks. On a
successful clear the stamp is forgotten (`mostRecentCopyAtMs = 0L`), keeping the
windowed ON_PAUSE decision consistent.

#### `NoteflowViewModel.lock()` — before → after

Before (`NoteflowViewModel.kt:3649` at audit):

```kotlin
ClipboardGuard.scrubIfOwnCopy(appContext)     // windowed, stamp-gated → left untracked native copies alone
```

After (`NoteflowViewModel.kt:4073`):

```kotlin
ClipboardGuard.scrubUnconditionally(appContext)   // clears the whole primary clip on EVERY lock path
```

Unchanged properties: still the first action of `lock()` — before
`repository.zeroizeKey()` and before the `if (settings.hasMasterPassword)` gate,
so every lock path (manual "Lock Vault Now", idle auto-lock, ON_STOP,
ACTION_SCREEN_OFF) and every vault kind (passwordless included) is covered.

#### ON_PAUSE retains the windowed decision

`MainActivity.kt:154` still runs `ClipboardGuard.scrubIfOwnCopy(this)` (windowed,
own-copy-within-60s only, defense-in-depth). The ON_PAUSE path is verified to
never call `scrubUnconditionally`. Both stamped copy sites keep `recordCopy()`,
so ON_PAUSE still clears the app's own tracked copies inside the window.

### Grep-pins (the finding's "grep-pin that every clipboard write in `ui/` is preceded by `recordCopy()`")

- `B2Ui2ClipboardScrubTest.every note-content copy stamps the guard before writing to
  the clipboard` no longer hard-codes the two known sites: it **walks all of `ui/`**
  and requires every `clipboardManager.setText(` call site to be preceded by a
  `ClipboardGuard.recordCopy()` in the same file.
- New `B2Ui2ClipboardScrubTest.every production clipboard write is routed through the
  guard — no raw system writes elsewhere` walks the whole `app/src/main` tree and
  fails if any raw `.setPrimaryClip(` / `.clearPrimaryClip(` exists outside
  `services/ClipboardGuard.kt` — so no future note-content surface can bypass the
  guard (and with it the lock-time scrub) by writing the system clipboard directly.

### Verification results

- `B2Ui2ClipboardScrubTest` — now **18 tests** (13 phase-72 + 5 new), all green.
  New tests:
  - `unconditional scrub clears a foreign unstamped copy - the editor native copy case`
    — `scrubUnconditionally` clears the primary clip (override seam) even when
    `mostRecentCopyAtMs == 0L` (an untracked editor/OCR native copy), then forgets
    the stamp.
  - `unconditional scrub clears a fresh stamped app copy too`
  - `an unconditional scrub failure is swallowed and returns false`
  - `the windowed path is retained on ON_PAUSE only - a foreign copy survives a brief
    app switch`
  - `every production clipboard write is routed through the guard…` (grep-pin above).
  - Updated `lock clears the clipboard unconditionally on every lock path before the
    DEK is dropped` — asserts `scrubUnconditionally(appContext)` is present, appears
    before `repository.zeroizeKey()` and before the `hasMasterPassword` gate, and
    that `lock()` does **not** call the windowed `scrubIfOwnCopy(appContext)`.
  - Updated `every in-app lock entry point routes through viewModel lock…` — asserts
    ON_PAUSE uses `scrubIfOwnCopy(this)` and that `scrubUnconditionally` never
    appears before `Lifecycle.Event.ON_STOP` in `MainActivity.kt`.
- `B1Db08DecryptFailureTest` — the lock-region boundary token updated from
  `ClipboardGuard.scrubIfOwnCopy(appContext)` to
  `ClipboardGuard.scrubUnconditionally(appContext)`; reset-before-teardown ordering
  assertions unchanged and green.
- `ClipboardScrubPolicy` untouched: `shouldScrub` still refuses a 0L/zero timestamp
  and enforces `SCRUB_WINDOW_MS = 60_000` — that is now the ON_PAUSE-only decision
  table, as the finding prescribed ("keep `ClipboardScrubPolicy.shouldScrub` for the
  windowed decision on ON_PAUSE").

Commands (Linux/CI, no gradle wrapper):

```
gradle testDebugUnitTest     # 1944 total (app 1894 + plugins:llm 50), 0 fail/0 err/0 skip
gradle assembleDebug         # BUILD SUCCESSFUL
```

## Definition of done

- [x] R2-B1P-01 closed with `file:line` before/after evidence (above):
      `ClipboardGuard.kt:50-54` → `ClipboardGuard.kt:84`/`:88` (new `scrubUnconditionally`
      + shared `clearPrimaryClip`); `NoteflowViewModel.kt:3649` → `:4073`;
      ON_PAUSE windowed hook retained at `MainActivity.kt:154`.
- [x] A decrypted note body copied via the editor selection (or the OCR dialog's
      `SelectionContainer`) is scrubbed on lock with NO window — the lock clears the
      whole primary clip unconditionally, matching the OCR/embed stamped behavior
      (and exceeding it: no stamp is even needed).
- [x] No platform-API floor regression — API 26-28 `setPrimaryClip(ClipData.newPlainText("", ""))`
      path kept inside the shared `clearPrimaryClip`; API 28+ `clearPrimaryClip()` kept.
      Pinned by the existing `production scrub still clears through the real system
      clipboard service` test.

## Constraints

- [x] No DB schema change; no new dependencies; `.github/workflows/` untouched.
- [x] No keys/passwords/decrypted note content logged. The change only clears the
      shared clipboard; it never writes, reads, or logs clipboard content.
- [x] FLAG_SECURE (`SecureWindowPolicy`) and `allowBackup=false` untouched.

## Residual notes (documented, NOT fixed here)

- **Trade-off of the unconditional lock clear:** an explicit lock (manual / idle /
  screen-off / ON_STOP) now wipes the primary clip even when the last copy came from
  another app. This is the deliberate fail-closed trade-off the finding sanctions for
  this threat model (the lock cannot distinguish a foreign copy from an unstamped
  decrypted note body — intercepting native selection Copy is brittle). The ON_PAUSE
  path remains windowed, so merely switching apps never wipes a foreign copy; only an
  actual lock does.
- **`:145`-class ON_PAUSE-only kill race:** a process kill between ON_PAUSE and ON_STOP
  of an unstamped editor copy is out of scope for any stamp-based design; unconditional
  clear cannot run if the process dies before a lock path. This is a sub-window of the
  same limitation B2-UI-2 documented.
- Phase-140 (R2-B1A-03, ON_PAUSE-only covers) remains OPEN — this phase covers the
  clipboard surface only, not screen exposure; do not fold it in here.